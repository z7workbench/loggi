//! JNI bridge between the loggi engine and the Kotlin/JVM desktop UI.
//!
//! M5+ contract (thin marshalling only — no engine logic lives here):
//!
//! - `openFile(path)` spawns indexing on a native thread and returns a handle
//!   id immediately; `indexProgress(id)` polls `[done, total, ready]`.
//! - `readLines(id, start, count, ByteBuffer, IntArray)` fills a direct
//!   ByteBuffer with one contiguous raw chunk plus per-line content offsets.
//! - `searchStart/searchPoll/searchCancel` run searches on a native thread;
//!   results are polled (100 ms cadence from the UI), never callback-driven,
//!   so no GlobalRef is ever touched from a native thread.
//! - `matchInLine` reuses a cached `HighlightMatcher` per (pattern, options).
//! - every handle owns every session; `closeFile` cancels + joins all searches.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use jni::JNIEnv;
use jni::objects::{JByteArray, JByteBuffer, JClass, JIntArray, JLongArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jintArray, jlong, jlongArray, jstring};
use loggi_engine::{
    AtomicFlag, FileIndex, HighlightMatcher, IndexOptions, SearchEngine, SearchOptions,
    SearchStatus, SharedIndex,
};

/// The engine version string.
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_version<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let out = env
        .new_string(env::version())
        .unwrap_or_else(|_| JString::default());
    out.into_raw()
}

// ---------------------------------------------------------------------------
// Handle registry
// ---------------------------------------------------------------------------

static HANDLES: Mutex<Option<HashMap<i64, Arc<HandleInner>>>> = Mutex::new(None);
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

/// Live handle for one open file. The id is a stable primitive (never a
/// GlobalRef) so native threads can touch it safely.
struct HandleInner {
    /// Set when the UI closed the file; aborts an in-flight index.
    closed: AtomicBool,
    /// Abort signal for the initial indexing pass.
    index_cancel: AtomicFlag,
    state: Mutex<OpenState>,
}

enum OpenState {
    /// Initial indexing runs on its own native thread; progress is polled.
    Indexing(Arc<IndexProgress>),
    Ready(ReadyState),
    Failed(String),
}

struct IndexProgress {
    done: AtomicU64,
    total: AtomicU64,
    ready: AtomicBool,
}

struct ReadyState {
    shared: SharedIndex,
    /// Current search engine + the shared-index generation it was built on.
    /// Searches clone the `Arc`; a rebuild swaps the slot without disturbing
    /// a running search (it holds its own Arc).
    engine: Mutex<EngineSlot>,
    /// Cached highlight matchers, keyed by (pattern, ignore_case, use_regex).
    matchers: Mutex<HashMap<(String, bool, bool), Arc<HighlightMatcher>>>,
    /// In-flight searches by search id.
    searches: Mutex<HashMap<u64, SearchSession>>,
}

struct EngineSlot {
    engine: Arc<SearchEngine>,
    generation: u64,
}

/// Shared search state touched by both the search thread and `searchPoll`.
struct SessionShared {
    stop: AtomicFlag,
    /// Set by the search thread when `search_with` returned.
    done: AtomicBool,
    cancelled: AtomicBool,
    /// Per-stripe match batches, drained by `searchPoll` in order.
    queue: Mutex<std::collections::VecDeque<Vec<u64>>>,
    /// (matches_found, processed_lines, total_lines).
    status: Mutex<(u64, u64, u64)>,
}

/// One in-flight search: shared state + the thread to join on cancel.
struct SearchSession {
    shared: Arc<SessionShared>,
    /// The thread runs `search_with` with an emitter pushing into `queue`.
    thread: Option<std::thread::JoinHandle<()>>,
}

fn register(inner: Arc<HandleInner>) -> i64 {
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed) as i64;
    let mut reg = HANDLES.lock().unwrap();
    reg.get_or_insert_with(HashMap::new).insert(id, inner);
    id
}

/// Look up a handle and run `body` with `$inner` bound to it. Missing handle
/// → throws. `$body` runs inside a closure, so `return None` exits the body
/// (evaluating to `None`) rather than the enclosing extern fn.
macro_rules! with_handle {
    ($env:expr, $id:expr, $inner:ident => $body:block) => {{
        let reg = HANDLES.lock().unwrap();
        match reg.as_ref().and_then(|m| m.get(&$id)) {
            Some($inner) => {
                // `return None` inside `$body` must exit the body (not the
                // enclosing extern fn); clippy's lint is deliberate here.
                #[allow(clippy::redundant_closure_call)]
                let result = (|| -> Option<_> { $body })();
                result
            }
            None => {
                let _ = $env.throw_new("java/lang/IllegalStateException", "invalid file handle");
                None
            }
        }
    }};
}

fn search_opts(pattern: &str, ignore_case: bool, use_regex: bool) -> SearchOptions {
    SearchOptions {
        patterns: vec![pattern.to_string()],
        ignore_case,
        use_regex,
        start_line: None,
        end_line: None,
        max_results: Some(1_000_000),
    }
}

/// Snapshot of a ready handle's index (None while indexing/failed).
fn ready_index(inner: &HandleInner) -> Option<Arc<FileIndex>> {
    let st = inner.state.lock().unwrap();
    match &*st {
        OpenState::Ready(r) => Some(r.shared.snapshot()),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// open / progress / info / refresh / close
// ---------------------------------------------------------------------------

/// Open a file: spawn indexing on a native thread, return the handle id
/// immediately. Poll `indexProgress` until ready.
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_openFile<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jlong {
    let path: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", "bad path string");
            return 0;
        }
    };
    let progress = Arc::new(IndexProgress {
        done: AtomicU64::new(0),
        total: AtomicU64::new(0),
        ready: AtomicBool::new(false),
    });
    let inner = Arc::new(HandleInner {
        closed: AtomicBool::new(false),
        index_cancel: AtomicFlag::new(),
        state: Mutex::new(OpenState::Indexing(progress.clone())),
    });
    let id = register(inner.clone());
    let inner2 = inner.clone();
    let progress2 = progress.clone();
    let _ = std::thread::Builder::new()
        .name(format!("loggi-index-{id}"))
        .spawn(move || {
            let p = std::path::PathBuf::from(path);
            let total = std::fs::metadata(&p).map(|m| m.len()).unwrap_or(0);
            progress2.total.store(total, Ordering::Relaxed);
            let prog = progress2.clone();
            let opts = IndexOptions {
                progress: Some(Arc::new(move |pr| {
                    prog.done.store(pr.done, Ordering::Relaxed);
                })),
                cancel: Some(inner2.index_cancel.clone()),
                ..Default::default()
            };
            let result = SharedIndex::open(&p, &opts);
            let mut st = inner2.state.lock().unwrap();
            match result {
                Ok(shared) => {
                    let generation = shared.generation();
                    let engine = SearchEngine::new(shared.snapshot());
                    *st = OpenState::Ready(ReadyState {
                        shared,
                        engine: Mutex::new(EngineSlot {
                            engine: Arc::new(engine),
                            generation,
                        }),
                        matchers: Mutex::new(HashMap::new()),
                        searches: Mutex::new(HashMap::new()),
                    });
                }
                Err(e) => {
                    *st = OpenState::Failed(e.to_string());
                }
            }
            drop(st);
            progress2.ready.store(true, Ordering::Relaxed);
        });
    id
}

/// Poll indexing progress: `[done, total, ready]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_indexProgress<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> jlongArray {
    let out = match env.new_long_array(3) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    with_handle!(&mut env, id, inner => {
        let st = inner.state.lock().unwrap();
        let (done, total, ready) = match &*st {
            OpenState::Indexing(p) => (
                p.done.load(Ordering::Relaxed),
                p.total.load(Ordering::Relaxed),
                p.ready.load(Ordering::Relaxed) as u64,
            ),
            OpenState::Ready(r) => {
                // Note: do NOT call `ready_index` here — the state lock is
                // already held by this function (Mutex is not reentrant).
                let done = r.shared.snapshot().size();
                (done, done, 1u64)
            }
            OpenState::Failed(_) => (0u64, 0u64, 1u64),
        };
        let vals = [done as jlong, total as jlong, ready as jlong];
        let _ = env.set_long_array_region(&out, 0, &vals);
        Some(())
    });
    out.into_raw()
}

/// Indexing failure message, or an empty string when ready/not failed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_indexError<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> jstring {
    let msg: Option<String> = with_handle!(&mut env, id, inner => {
        let st = inner.state.lock().unwrap();
        match &*st {
            OpenState::Failed(e) => Some(e.clone()),
            _ => Some(String::new()),
        }
    });
    let out = env
        .new_string(msg.unwrap_or_default())
        .unwrap_or_else(|_| JString::default());
    out.into_raw()
}

/// File info: `[size, lineCount, maxLineLen, indexBytes, indexTimeMillis,
/// encoding(0..5), lineFeedWidth]`. Encoding codes match the Kotlin bridge.
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_fileInfo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> jlongArray {
    let out = match env.new_long_array(7) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    with_handle!(&mut env, id, inner => {
        match ready_index(inner) {
            Some(idx) => {
                let enc = match idx.encoding() {
                    loggi_engine::TextEncoding::Utf8 => 0,
                    loggi_engine::TextEncoding::Utf16Le => 1,
                    loggi_engine::TextEncoding::Utf16Be => 2,
                    loggi_engine::TextEncoding::Utf32Le => 3,
                    loggi_engine::TextEncoding::Utf32Be => 4,
                    loggi_engine::TextEncoding::Other(_) => 5,
                };
                let vals = [
                    idx.size() as jlong,
                    idx.line_count() as jlong,
                    idx.max_line_len() as jlong,
                    idx.index_bytes() as jlong,
                    0,
                    enc as jlong,
                    idx.encoding().line_feed_width() as jlong,
                ];
                let _ = env.set_long_array_region(&out, 0, &vals);
            }
            None => {
                let _ = env.throw_new("java/lang/IllegalStateException", "file not ready");
            }
        }
        Some(())
    });
    out.into_raw()
}

/// Charset name for non-Unicode encodings (empty string for the Unicode family).
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_encodingName<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> jstring {
    let name: Option<String> = with_handle!(&mut env, id, inner => {
        ready_index(inner).map(|idx| match idx.encoding() {
            loggi_engine::TextEncoding::Other(e) => e.name().to_string(),
            _ => String::new(),
        })
    });
    let out = env
        .new_string(name.unwrap_or_default())
        .unwrap_or_else(|_| JString::default());
    out.into_raw()
}

/// Check the file for append/truncate and re-index incrementally. Returns
/// `true` when the index changed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_refresh<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) -> jboolean {
    let changed: Option<jboolean> = with_handle!(&mut env, id, inner => {
        let mut st = inner.state.lock().unwrap();
        match &mut *st {
            OpenState::Ready(r) => match r.shared.refresh(&IndexOptions::default()) {
                Ok(changed) => Some(changed as jboolean),
                Err(_) => Some(0),
            },
            _ => Some(0),
        }
    });
    changed.unwrap_or(0)
}

/// Close a file: cancel and join every in-flight search, drop the engine.
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_closeFile<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
) {
    let inner = {
        let mut reg = HANDLES.lock().unwrap();
        reg.as_mut().and_then(|m| m.remove(&id))
    };
    if let Some(inner) = inner {
        inner.closed.store(true, Ordering::Relaxed);
        inner.index_cancel.set();
        let sessions = {
            let mut st = inner.state.lock().unwrap();
            match &mut *st {
                OpenState::Ready(r) => std::mem::take(&mut *r.searches.lock().unwrap()),
                _ => HashMap::new(),
            }
        };
        for (_, s) in sessions {
            s.shared.stop.set();
            if let Some(t) = s.thread {
                let _ = t.join();
            }
        }
    }
    let _ = env;
}

// ---------------------------------------------------------------------------
// readLines
// ---------------------------------------------------------------------------

/// Read lines `[start, start+count)` into `buf` (direct ByteBuffer) and fill
/// `offsets` (size count+1) with content-start offsets of each line relative
/// to the buffer. Returns `[endLine, byteStart, byteLen]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_readLines<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    start: jlong,
    count: jlong,
    buf: JByteBuffer<'local>,
    offsets: JIntArray<'local>,
) -> jlongArray {
    let out = match env.new_long_array(3) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    with_handle!(&mut env, id, inner => {
        let idx = match ready_index(inner) {
            Some(i) => i,
            None => {
                let _ = env.throw_new("java/lang/IllegalStateException", "file not ready");
                return None;
            }
        };
        let reader = loggi_engine::LazyReader::new(idx.clone());
        let count = (count as u64).clamp(0, 1 << 20);
        let mut bytes = Vec::new();
        let read = match reader.read_lines(start as u64, count, &mut bytes) {
            Ok(r) => r,
            Err(e) => {
                let _ = env.throw_new("java/io/IOException", e.to_string());
                return None;
            }
        };
        let n = (read.end_line - read.start_line) as usize;
        let cap = env
            .get_direct_buffer_capacity(&buf)
            .unwrap_or(bytes.len().min(1 << 20));
        let want = bytes.len().min(cap);
        if want > 0 {
            let addr = match env.get_direct_buffer_address(&buf) {
                Ok(a) => a,
                Err(e) => {
                    let _ = env.throw_new("java/lang/IllegalStateException", e.to_string());
                    return None;
                }
            };
            unsafe {
                std::ptr::copy_nonoverlapping(bytes.as_ptr(), addr, want);
            }
        }
        // Per-line content offsets (BOM excluded for line 0 by the engine).
        let mut offs: Vec<i32> = Vec::with_capacity(n + 1);
        for k in 0..n {
            offs.push((idx.line_start(read.start_line + k as u64) - read.byte_start) as i32);
        }
        offs.push(if n > 0 {
            (idx.line_content_end(read.end_line - 1) - read.byte_start) as i32
        } else {
            0
        });
        let olen = env.get_array_length(&offsets).unwrap_or(0) as usize;
        offs.truncate(olen);
        let _ = env.set_int_array_region(&offsets, 0, &offs);
        let vals = [
            read.end_line as jlong,
            read.byte_start as jlong,
            want as jlong,
        ];
        let _ = env.set_long_array_region(&out, 0, &vals);
        Some(())
    });
    out.into_raw()
}

// ---------------------------------------------------------------------------
// search lifecycle
// ---------------------------------------------------------------------------

/// Start a search; returns a search id. Runs on a native thread; results are
/// drained by `searchPoll`. Cancellation via `searchCancel`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_searchStart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    pattern: JString<'local>,
    ignore_case: jboolean,
    use_regex: jboolean,
) -> jlong {
    let pattern: String = match env.get_string(&pattern) {
        Ok(s) => s.into(),
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", "bad pattern");
            return 0;
        }
    };
    let search_id: Option<jlong> = with_handle!(&mut env, id, inner => {
        let mut st = inner.state.lock().unwrap();
        let r = match &mut *st {
            OpenState::Ready(r) => r,
            _ => {
                let _ = env.throw_new("java/lang/IllegalStateException", "file not ready");
                return None;
            }
        };
        // Swap in a fresh engine when the shared index generation advanced.
        let generation = r.shared.generation();
        {
            let mut slot = r.engine.lock().unwrap();
            if slot.generation != generation {
                *slot = EngineSlot {
                    engine: Arc::new(SearchEngine::new(r.shared.snapshot())),
                    generation,
                };
            }
        }
        let engine = r.engine.lock().unwrap().engine.clone();
        let opts = search_opts(&pattern, ignore_case != 0, use_regex != 0);
        let search_id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let shared = Arc::new(SessionShared {
            stop: AtomicFlag::new(),
            done: AtomicBool::new(false),
            cancelled: AtomicBool::new(false),
            queue: Mutex::new(std::collections::VecDeque::new()),
            status: Mutex::new((0, 0, 0)),
        });
        let shared2 = shared.clone();
        let t = std::thread::Builder::new()
            .name(format!("loggi-search-{search_id}"))
            .spawn(move || {
                let result = engine.search_with(&opts, |status: SearchStatus, lines: &[u64]| {
                    if !lines.is_empty() {
                        shared2.queue.lock().unwrap().push_back(lines.to_vec());
                    }
                    {
                        let mut s = shared2.status.lock().unwrap();
                        *s = (
                            status.matches_found,
                            status.processed_lines,
                            status.total_lines,
                        );
                    }
                    // Keep consuming while not cancelled; always accept the
                    // final (done) batch so the engine returns normally.
                    !shared2.stop.is_set() || status.done
                });
                if let Ok(res) = result {
                    shared2.cancelled.store(res.cancelled, Ordering::Relaxed);
                } else {
                    shared2.cancelled.store(true, Ordering::Relaxed);
                }
                shared2.done.store(true, Ordering::Relaxed);
            })
            .expect("failed to spawn search thread");
        r.searches.lock().unwrap().insert(
            search_id,
            SearchSession {
                shared,
                thread: Some(t),
            },
        );
        Some(search_id as jlong)
    });
    search_id.unwrap_or(0)
}

/// Drain up to `out.size` matched lines into `out`. Returns
/// `[matchesFound, processedLines, totalLines, done, cancelled, linesReturned]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_searchPoll<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    search_id: jlong,
    out: JLongArray<'local>,
) -> jlongArray {
    let meta = match env.new_long_array(6) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    let finished: Option<bool> = with_handle!(&mut env, id, inner => {
        let st = inner.state.lock().unwrap();
        let r = match &*st {
            OpenState::Ready(r) => r,
            _ => {
                let _ = env.throw_new("java/lang/IllegalStateException", "file not ready");
                return None;
            }
        };
        let session = match r.searches.lock().unwrap().get(&(search_id as u64)) {
            Some(s) => s.shared.clone(),
            None => {
                // Session already finished and removed: report done.
                let vals = [0i64, 0, 0, 1, 0, 0];
                let _ = env.set_long_array_region(&meta, 0, &vals);
                return Some(true);
            }
        };
        let cap = env.get_array_length(&out).unwrap_or(0) as usize;
        let mut drained: Vec<u64> = Vec::new();
        let queue_empty = {
            let mut q = session.queue.lock().unwrap();
            while drained.len() < cap && !q.is_empty() {
                let batch = q.pop_front().unwrap();
                let take = (cap - drained.len()).min(batch.len());
                drained.extend_from_slice(&batch[..take]);
                if take < batch.len() {
                    q.push_front(batch[take..].to_vec());
                }
            }
            q.is_empty()
        };
        if !drained.is_empty() {
            let arr: Vec<jlong> = drained.iter().map(|&l| l as jlong).collect();
            let _ = env.set_long_array_region(&out, 0, &arr);
        }
        let (matches_found, processed, total) = *session.status.lock().unwrap();
        let thread_done = session.done.load(Ordering::Relaxed);
        let cancelled = session.cancelled.load(Ordering::Relaxed);
        // Report `done` only when the search thread finished AND the queue was
        // fully drained — otherwise a fast final poll would lose queued lines.
        let done = thread_done && queue_empty;
        let finished = done;
        let vals = [
            matches_found as jlong,
            processed as jlong,
            total as jlong,
            done as jlong,
            cancelled as jlong,
            drained.len() as jlong,
        ];
        let _ = env.set_long_array_region(&meta, 0, &vals);
        Some(finished)
    });
    if finished.unwrap_or(false) {
        // Remove the finished session (the thread already exited).
        let reg = HANDLES.lock().unwrap();
        if let Some(inner) = reg.as_ref().and_then(|m| m.get(&id)) {
            let mut st = inner.state.lock().unwrap();
            if let OpenState::Ready(r) = &mut *st {
                r.searches.lock().unwrap().remove(&(search_id as u64));
            }
        }
    }
    meta.into_raw()
}

/// Cancel a search: set the stop flag and join the search thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_searchCancel<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    search_id: jlong,
) {
    let session: Option<SearchSession> = with_handle!(&mut env, id, inner => {
        let mut st = inner.state.lock().unwrap();
        match &mut *st {
            OpenState::Ready(r) => r.searches.lock().unwrap().remove(&(search_id as u64)),
            _ => None,
        }
    });
    if let Some(s) = session {
        s.shared.stop.set();
        if let Some(t) = s.thread {
            let _ = t.join();
        }
    }
}

// ---------------------------------------------------------------------------
// highlight matching (M7)
// ---------------------------------------------------------------------------

/// Match positions within one decoded (UTF-8) line, using a cached matcher:
/// returns a flattened `[start0, end0, start1, end1, ...]` int array.
///
/// # Safety
/// `line` must be a valid JNI jbyteArray for the duration of the call.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_top_z7workbench_loggi_jni_LoggiBridge_matchInLine<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jlong,
    pattern: JString<'local>,
    ignore_case: jboolean,
    use_regex: jboolean,
    line: jbyteArray,
) -> jintArray {
    let pattern: String = match env.get_string(&pattern) {
        Ok(s) => s.into(),
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", "bad pattern");
            return std::ptr::null_mut();
        }
    };
    // SAFETY: the raw handle comes from the JVM and stays valid for this
    // call27a9line2019s duration; from_raw is the standard jni pattern.
    let line_arr = unsafe { JByteArray::from_raw(line) };
    let line_len = env.get_array_length(&line_arr).unwrap_or(0) as usize;
    let mut line_bytes: Vec<i8> = vec![0; line_len];
    if line_len > 0 {
        let _ = env.get_byte_array_region(&line_arr, 0, &mut line_bytes);
    }
    let line_bytes: Vec<u8> = line_bytes.into_iter().map(|b| b as u8).collect();
    let key = (pattern.clone(), ignore_case != 0, use_regex != 0);
    let positions: Option<Vec<(u32, u32)>> = with_handle!(&mut env, id, inner => {
        let st = inner.state.lock().unwrap();
        let r = match &*st {
            OpenState::Ready(r) => r,
            _ => {
                let _ = env.throw_new("java/lang/IllegalStateException", "file not ready");
                return None;
            }
        };
        let matcher = {
            let mut cache = r.matchers.lock().unwrap();
            if let Some(m) = cache.get(&key) {
                m.clone()
            } else {
                let opts = search_opts(&pattern, ignore_case != 0, use_regex != 0);
                match HighlightMatcher::new(&opts) {
                    Ok(m) => {
                        let m = Arc::new(m);
                        cache.insert(key, m.clone());
                        m
                    }
                    Err(e) => {
                        let _ = env.throw_new("java/lang/IllegalArgumentException", e.to_string());
                        return None;
                    }
                }
            }
        };
        Some(matcher.positions_in_line(&line_bytes))
    });
    let flat: Vec<jint> = positions
        .unwrap_or_default()
        .iter()
        .flat_map(|&(s, e)| [s as jint, e as jint])
        .collect();
    let out = match env.new_int_array(flat.len() as i32) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    if !flat.is_empty() {
        let _ = env.set_int_array_region(&out, 0, &flat);
    }
    out.into_raw()
}

mod env {
    pub const fn version() -> &'static str {
        concat!(env!("CARGO_PKG_NAME"), " ", env!("CARGO_PKG_VERSION"))
    }
}
