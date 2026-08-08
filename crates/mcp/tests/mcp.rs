//! End-to-end MCP server test: spawn the binary and drive JSON-RPC over stdio.

use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};

struct McpClient {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
}

impl McpClient {
    fn start() -> McpClient {
        let mut child = Command::new(env!("CARGO_BIN_EXE_loggi-mcp"))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        let stdin = child.stdin.take().unwrap();
        let stdout = BufReader::new(child.stdout.take().unwrap());
        McpClient {
            child,
            stdin,
            stdout,
        }
    }

    fn send(&mut self, msg: &serde_json::Value) {
        writeln!(self.stdin, "{msg}").unwrap();
        self.stdin.flush().unwrap();
    }

    fn recv(&mut self) -> serde_json::Value {
        let mut line = String::new();
        self.stdout.read_line(&mut line).unwrap();
        serde_json::from_str(&line).unwrap_or(serde_json::Value::Null)
    }

    fn request(&mut self, id: u64, method: &str, params: serde_json::Value) -> serde_json::Value {
        self.send(
            &serde_json::json!({ "jsonrpc": "2.0", "id": id, "method": method, "params": params }),
        );
        loop {
            let msg = self.recv();
            if msg.get("id") == Some(&serde_json::json!(id)) {
                return msg;
            }
            // skip notifications
        }
    }
}

impl Drop for McpClient {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn corpus() -> std::path::PathBuf {
    let dir = Box::leak(Box::new(tempfile::tempdir().unwrap()));
    let p = dir.path().join("app.log");
    let mut f = std::fs::File::create(&p).unwrap();
    for i in 0..50u64 {
        writeln!(f, "INFO  request {i:04} ok").unwrap();
    }
    for i in 0..5u64 {
        writeln!(f, "ERROR request {i:04} boom").unwrap();
    }
    p
}

#[test]
fn handshake_and_tools_list() {
    let mut c = McpClient::start();
    let r = c.request(1, "initialize", serde_json::json!({}));
    assert_eq!(r["result"]["serverInfo"]["name"], "loggi-mcp");
    assert_eq!(r["result"]["protocolVersion"], "2024-11-05");
    let r = c.request(2, "tools/list", serde_json::json!({}));
    let names: Vec<&str> = r["result"]["tools"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t["name"].as_str().unwrap())
        .collect();
    assert_eq!(names, vec!["file_info", "read_lines", "search", "cancel"]);
}

#[test]
fn file_info_and_read_lines() {
    let p = corpus();
    let mut c = McpClient::start();
    let r = c.request(
        1,
        "tools/call",
        serde_json::json!({ "name": "file_info", "arguments": { "path": p } }),
    );
    assert_eq!(r["result"]["line_count"], 55);
    assert_eq!(r["result"]["encoding"], "UTF-8");
    let r = c.request(
        2,
        "tools/call",
        serde_json::json!({ "name": "read_lines", "arguments": { "path": p, "start": 50, "count": 2 } }),
    );
    assert_eq!(r["result"]["end_line"], 52);
    assert_eq!(r["result"]["lines"][0]["text"], "ERROR request 0000 boom");
    assert_eq!(r["result"]["lines"][1]["line_number"], 51);
}

#[test]
fn search_streams_batches_and_done() {
    let p = corpus();
    let mut c = McpClient::start();
    let r = c.request(
        1,
        "tools/call",
        serde_json::json!({ "name": "search", "arguments": { "path": p, "pattern": "ERROR", "regex": false } }),
    );
    let search_id = r["result"]["search_id"].as_u64().unwrap();
    // Consume notifications until search_done.
    let mut total = 0u64;
    loop {
        let msg = c.recv();
        match msg["method"].as_str() {
            Some("loggi/search_batch") => {
                assert_eq!(msg["params"]["search_id"].as_u64().unwrap(), search_id);
                total += msg["params"]["lines"].as_array().unwrap().len() as u64;
            }
            Some("loggi/search_done") => {
                eprintln!("DONE: {}", msg);
                assert_eq!(msg["params"]["search_id"].as_u64().unwrap(), search_id);
                eprintln!("DONE: {}", msg);
                assert_eq!(msg["params"]["matches"], 5);
                break;
            }
            other => panic!("unexpected message: {other:?}"),
        }
    }
    assert_eq!(total, 5);
}

#[test]
fn search_respects_limit_and_cancel() {
    let p = corpus();
    let mut c = McpClient::start();
    let r = c.request(
        1,
        "tools/call",
        serde_json::json!({ "name": "search", "arguments": { "path": p, "pattern": "request", "regex": false, "limit": 3 } }),
    );
    let search_id = r["result"]["search_id"].as_u64().unwrap();
    let mut total = 0u64;
    loop {
        let msg = c.recv();
        match msg["method"].as_str() {
            Some("loggi/search_batch") => {
                total += msg["params"]["lines"].as_array().unwrap().len() as u64;
            }
            Some("loggi/search_done") => {
                assert_eq!(msg["params"]["search_id"].as_u64().unwrap(), search_id);
                assert_eq!(msg["params"]["matches"], 3);
                break;
            }
            _ => {}
        }
    }
    assert_eq!(total, 3);

    // Cancel an unknown search id is a no-op; cancelling a finished one is too.
    let r = c.request(
        2,
        "tools/call",
        serde_json::json!({ "name": "cancel", "arguments": { "search_id": 9999 } }),
    );
    assert_eq!(r["result"]["cancelled"], false);
}

#[test]
fn error_on_missing_file() {
    let mut c = McpClient::start();
    let r = c.request(
        1,
        "tools/call",
        serde_json::json!({ "name": "file_info", "arguments": { "path": "/nonexistent/xx.log" } }),
    );
    assert!(r["error"].is_object());
}
