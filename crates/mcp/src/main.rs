//! loggi-mcp: a stdio JSON-RPC (MCP-style) server over the loggi engine.
//!
//! Protocol: newline-delimited JSON-RPC 2.0. Tools:
//! - `file_info`  {path}
//! - `read_lines` {path, start, count}
//! - `search` {path, pattern, ignore_case, regex, limit, start_line, end_line}
//!   → `{search_id}`; results stream as `loggi/search_batch` notifications
//!   with progress, finished by `loggi/search_done`.
//! - `cancel` {search_id}
//!
//! Searches run on worker threads with request-scoped cancellation; the
//! server stays responsive to concurrent requests. Files are re-indexed
//! incrementally when they grow between calls.

use std::collections::HashMap;
use std::io::{BufRead, Write};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use loggi_engine::TextEncoding;
use loggi_engine::index::SharedIndex;
use loggi_engine::reader::LazyReader;
use loggi_engine::search::{SearchEngine, SearchOptions};
use serde_json::{Value, json};

/// One open file: a shared index + engine rebuilt when the file grows.
struct Session {
    shared: SharedIndex,
    engine: Arc<Mutex<SearchEngine>>,
}

struct Server {
    sessions: Mutex<HashMap<PathBuf, Arc<Session>>>,
    /// search_id -> cancellation flag
    searches: Mutex<HashMap<u64, Arc<loggi_engine::AtomicFlag>>>,
    next_search_id: AtomicU64,
    out: Mutex<std::io::Stdout>,
}

impl Server {
    fn new() -> Self {
        Server {
            sessions: Mutex::new(HashMap::new()),
            searches: Mutex::new(HashMap::new()),
            next_search_id: AtomicU64::new(1),
            out: Mutex::new(std::io::stdout()),
        }
    }

    fn send(&self, msg: &Value) {
        let mut out = self.out.lock().unwrap();
        let _ = writeln!(out, "{}", msg);
        let _ = out.flush();
    }

    fn notify(&self, method: &str, params: Value) {
        self.send(&json!({ "jsonrpc": "2.0", "method": method, "params": params }));
    }

    fn result(&self, id: Value, result: Value) {
        self.send(&json!({ "jsonrpc": "2.0", "id": id, "result": result }));
    }

    fn error(&self, id: Value, code: i64, message: impl Into<String>) {
        self.send(&json!({ "jsonrpc": "2.0", "id": id, "error": { "code": code, "message": message.into() } }));
    }

    /// Get (or open + index) a session for `path`. Returns an error string on
    /// failure.
    fn session(&self, path: &str) -> Result<Arc<Session>, String> {
        let path = PathBuf::from(path);
        {
            let sessions = self.sessions.lock().unwrap();
            if let Some(s) = sessions.get(&path) {
                return Ok(s.clone());
            }
        }
        let shared = SharedIndex::open(&path, &Default::default()).map_err(|e| e.to_string())?;
        let engine = SearchEngine::new(shared.snapshot());
        let session = Arc::new(Session {
            shared,
            engine: Arc::new(Mutex::new(engine)),
        });
        self.sessions.lock().unwrap().insert(path, session.clone());
        Ok(session)
    }

    /// Refresh the session's index if the file changed; rebuilds the engine.
    fn refresh(&self, session: &Arc<Session>) {
        if let Ok(true) = session.shared.refresh(&Default::default()) {
            let engine = SearchEngine::new(session.shared.snapshot());
            *session.engine.lock().unwrap() = engine;
        }
    }

    fn tool_file_info(this: &Arc<Server>, id: Value, params: &Value) {
        let path = match params.get("path").and_then(Value::as_str) {
            Some(p) => p,
            None => return this.error(id, -32602, "missing path"),
        };
        let session = match this.session(path) {
            Ok(s) => s,
            Err(e) => return this.error(id, -32602, e),
        };
        this.refresh(&session);
        let t = Instant::now();
        let info = session.shared.info();
        this.result(
            id,
            json!({
                "path": path,
                "size": info.size,
                "line_count": info.line_count,
                "max_line_len": info.max_line_len,
                "encoding": encoding_name(info.encoding),
                "index_bytes": info.index_bytes,
                "index_time_s": t.elapsed().as_secs_f64(),
            }),
        );
    }

    fn tool_read_lines(this: &Arc<Server>, id: Value, params: &Value) {
        let (path, start, count) = match (
            params.get("path").and_then(Value::as_str),
            params.get("start").and_then(Value::as_u64),
            params.get("count").and_then(Value::as_u64).unwrap_or(100),
        ) {
            (Some(p), Some(s), c) => (p, s, c),
            _ => return this.error(id, -32602, "missing path/start"),
        };
        let session = match this.session(path) {
            Ok(s) => s,
            Err(e) => return this.error(id, -32602, e),
        };
        this.refresh(&session);
        let engine = session.engine.lock().unwrap();
        let reader = LazyReader::new(engine.index().clone());
        let mut buf = Vec::new();
        let read = match reader.read_lines(start, count.min(100_000), &mut buf) {
            Ok(r) => r,
            Err(e) => return this.error(id, -32603, e.to_string()),
        };
        let mut lines = Vec::new();
        for line in read.start_line..read.end_line {
            let raw = reader.line_view(&read, &buf, line);
            let mut text = String::new();
            reader.decode_line(raw, 0, &mut text);
            lines.push(json!({ "line_number": line, "text": text }));
        }
        this.result(
            id,
            json!({
                "start_line": read.start_line,
                "end_line": read.end_line,
                "byte_start": read.byte_start,
                "byte_len": read.byte_len,
                "lines": lines,
            }),
        );
    }

    fn tool_search(this: &Arc<Server>, id: Value, params: &Value) {
        let path = match params.get("path").and_then(Value::as_str) {
            Some(p) => p,
            None => return this.error(id, -32602, "missing path"),
        };
        let pattern = match params.get("pattern").and_then(Value::as_str) {
            Some(p) => p,
            None => return this.error(id, -32602, "missing pattern"),
        };
        let session = match this.session(path) {
            Ok(s) => s,
            Err(e) => return this.error(id, -32602, e),
        };
        this.refresh(&session);

        let opts = SearchOptions {
            patterns: vec![pattern.to_string()],
            ignore_case: params
                .get("ignore_case")
                .and_then(Value::as_bool)
                .unwrap_or(false),
            use_regex: params.get("regex").and_then(Value::as_bool).unwrap_or(true),
            start_line: params.get("start_line").and_then(Value::as_u64),
            end_line: params.get("end_line").and_then(Value::as_u64),
            max_results: params.get("limit").and_then(Value::as_u64),
        };

        let search_id = this.next_search_id.fetch_add(1, Ordering::Relaxed);
        let cancel = Arc::new(loggi_engine::AtomicFlag::new());
        this.searches
            .lock()
            .unwrap()
            .insert(search_id, cancel.clone());

        let engine = session.engine.clone();
        let this_c = this.clone();
        let search_id_c = search_id;
        let cancel_c = cancel.clone();
        let limit = opts.max_results;
        std::thread::Builder::new()
            .name(format!("loggi-mcp-search-{search_id}"))
            .spawn(move || {
                let engine = engine.lock().unwrap();
                let mut emitted = 0u64;
                let res = engine.search_with(&opts, |status, lines| {
                    // Batches may overshoot `limit`; emit only the first N
                    // lines (batches arrive sorted by line number).
                    let remaining = limit.map(|l| l.saturating_sub(emitted)).unwrap_or(u64::MAX);
                    let take = (lines.len() as u64).min(remaining) as usize;
                    if take > 0 {
                        this_c.notify(
                            "loggi/search_batch",
                            json!({
                                "search_id": search_id_c,
                                "lines": &lines[..take],
                                "status": {
                                    "matches_found": status.matches_found,
                                    "processed_lines": status.processed_lines,
                                    "total_lines": status.total_lines,
                                    "done": status.done,
                                },
                            }),
                        );
                        emitted += take as u64;
                    }
                    if let Some(l) = limit {
                        emitted < l
                    } else {
                        !cancel_c.is_set()
                    }
                });
                match res {
                    Ok(r) => this_c.notify(
                        "loggi/search_done",
                        json!({
                            "search_id": search_id_c,
                            "matches": r.matches.len(),
                            "cancelled": r.cancelled,
                            "elapsed_s": r.elapsed.as_secs_f64(),
                        }),
                    ),
                    Err(e) => this_c.notify(
                        "loggi/search_done",
                        json!({ "search_id": search_id_c, "error": e.to_string() }),
                    ),
                }
                this_c.searches.lock().unwrap().remove(&search_id_c);
            })
            .ok();

        this.result(
            id,
            json!({
                "search_id": search_id,
                "searching": true,
                "path": path,
            }),
        );
    }

    fn tool_cancel(this: &Arc<Server>, id: Value, params: &Value) {
        let sid = match params.get("search_id").and_then(Value::as_u64) {
            Some(s) => s,
            None => return this.error(id, -32602, "missing search_id"),
        };
        if let Some(flag) = this.searches.lock().unwrap().get(&sid) {
            flag.set();
            this.result(id, json!({ "cancelled": true }));
        } else {
            this.result(
                id,
                json!({ "cancelled": false, "reason": "unknown search_id" }),
            );
        }
    }

    fn handle(this: &Arc<Server>, msg: Value) {
        let id = msg.get("id").cloned();
        let method = msg.get("method").and_then(Value::as_str).unwrap_or("");
        match method {
            "initialize" => this.result(
                id.unwrap_or(Value::Null),
                json!({
                    "protocolVersion": "2024-11-05",
                    "capabilities": { "tools": {} },
                    "serverInfo": { "name": "loggi-mcp", "version": env!("CARGO_PKG_VERSION") },
                }),
            ),
            "notifications/initialized" | "initialized" => {}
            "ping" => this.result(id.unwrap_or(Value::Null), json!({})),
            "tools/list" => {
                let tools = vec![
                    json!({
                        "name": "file_info",
                        "description": "Index a log file (lazy) and return size, line count, max line length, encoding.",
                        "inputSchema": { "type": "object", "properties": {
                            "path": { "type": "string" }
                        }, "required": ["path"] }
                    }),
                    json!({
                        "name": "read_lines",
                        "description": "Read lines [start, start+count) from an indexed file (0-based line numbers).",
                        "inputSchema": { "type": "object", "properties": {
                            "path": { "type": "string" },
                            "start": { "type": "integer" },
                            "count": { "type": "integer" }
                        }, "required": ["path", "start"] }
                    }),
                    json!({
                        "name": "search",
                        "description": "Search a file. Returns a search_id; match batches stream via loggi/search_batch notifications and finish with loggi/search_done.",
                        "inputSchema": { "type": "object", "properties": {
                            "path": { "type": "string" },
                            "pattern": { "type": "string" },
                            "ignore_case": { "type": "boolean" },
                            "regex": { "type": "boolean" },
                            "limit": { "type": "integer" },
                            "start_line": { "type": "integer" },
                            "end_line": { "type": "integer" }
                        }, "required": ["path", "pattern"] }
                    }),
                    json!({
                        "name": "cancel",
                        "description": "Cancel a running search by search_id.",
                        "inputSchema": { "type": "object", "properties": {
                            "search_id": { "type": "integer" }
                        }, "required": ["search_id"] }
                    }),
                ];
                this.result(id.unwrap_or(Value::Null), json!({ "tools": tools }));
            }
            "tools/call" => {
                let name = msg
                    .pointer("/params/name")
                    .and_then(Value::as_str)
                    .unwrap_or("");
                let params = msg
                    .pointer("/params/arguments")
                    .cloned()
                    .unwrap_or(Value::Null);
                let id = id.unwrap_or(Value::Null);
                match name {
                    "file_info" => Server::tool_file_info(this, id, &params),
                    "read_lines" => Server::tool_read_lines(this, id, &params),
                    "search" => Server::tool_search(this, id, &params),
                    "cancel" => Server::tool_cancel(this, id, &params),
                    _ => this.error(id, -32601, format!("unknown tool: {name}")),
                }
            }
            _ => {
                if !method.is_empty()
                    && let Some(id) = id
                {
                    this.error(id, -32601, format!("unknown method: {method}"));
                }
            }
        }
    }
}

fn encoding_name(e: TextEncoding) -> &'static str {
    match e {
        TextEncoding::Utf8 => "UTF-8",
        TextEncoding::Utf16Le => "UTF-16LE",
        TextEncoding::Utf16Be => "UTF-16BE",
        TextEncoding::Utf32Le => "UTF-32LE",
        TextEncoding::Utf32Be => "UTF-32BE",
        TextEncoding::Other(enc) => enc.name(),
    }
}

fn main() {
    let server = Arc::new(Server::new());
    let stdin = std::io::stdin();
    for line in stdin.lock().lines() {
        let line = match line {
            Ok(l) => l,
            Err(_) => break,
        };
        if line.trim().is_empty() {
            continue;
        }
        let msg: Value = match serde_json::from_str(&line) {
            Ok(v) => v,
            Err(_) => continue,
        };
        let this = server.clone();
        std::thread::spawn(move || Server::handle(&this, msg));
    }
}
