//! File watching with XXH64 header/tail change detection.
//!
//! An OS-level `notify` watcher triggers recomputation; a polling fallback
//! (default 1 s) keeps network drives and filesystems without native events
//! working. Change detection compares the file size plus hashes of the first
//! and last `hash_size` bytes: growth with an unchanged head means data was
//! appended; any head/size change means truncation or rewrite.

use std::fs::File;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use std::thread::JoinHandle;
use std::time::Duration;

use notify::{Config, RecommendedWatcher, RecursiveMode, Watcher};

/// Result of a change check since the previous state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChangeKind {
    Unchanged,
    /// Data was appended; partial reindex + incremental search apply.
    DataAdded,
    /// File shrank or was rewritten; a full reindex applies.
    Truncated,
}

/// Watcher configuration.
#[derive(Debug, Clone)]
pub struct WatchConfig {
    /// Use OS file events (default true). Polling always runs as a fallback.
    pub use_notify: bool,
    /// Polling interval; `None` disables polling entirely.
    pub poll_interval: Option<Duration>,
    /// Bytes hashed at the head and tail of the file (default 5 MiB).
    pub hash_size: usize,
}

impl Default for WatchConfig {
    fn default() -> Self {
        WatchConfig {
            use_notify: true,
            poll_interval: Some(Duration::from_secs(1)),
            hash_size: 5 << 20,
        }
    }
}

/// Snapshot of a file's size + head/tail hashes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileState {
    pub size: u64,
    head: u64,
    tail: u64,
    /// True when the file fits in the head hash window (small files); used to
    /// classify growth as appends even though the "head" hash changed.
    whole: bool,
}

impl FileState {
    /// Hash the current file state. A missing file reports size 0.
    pub fn capture(path: &Path, hash_size: usize) -> FileState {
        let file = match File::open(path) {
            Ok(f) => f,
            Err(_) => {
                return FileState {
                    size: 0,
                    head: 0,
                    tail: 0,
                    whole: false,
                };
            }
        };
        let size = file.metadata().map(|m| m.len()).unwrap_or(0);
        if size == 0 {
            return FileState {
                size: 0,
                head: 0,
                tail: 0,
                whole: true,
            };
        }
        let head_n = size.min(hash_size as u64) as usize;
        let mut head_buf = vec![0u8; head_n];
        let _ = pread_all(&file, &mut head_buf, 0);
        let whole = size <= 2 * hash_size as u64;
        let (head, tail) = if whole {
            let h = xxhash_rust::xxh64::xxh64(&head_buf, 0);
            (h, h)
        } else {
            let mut tail_buf = vec![0u8; hash_size];
            let _ = pread_all(&file, &mut tail_buf, size - hash_size as u64);
            (
                xxhash_rust::xxh64::xxh64(&head_buf, 0),
                xxhash_rust::xxh64::xxh64(&tail_buf, 0),
            )
        };
        FileState {
            size,
            head,
            tail,
            whole,
        }
    }

    /// Classify the transition from `prev` to this state.
    pub fn change_since(&self, prev: &FileState) -> ChangeKind {
        if self.size == prev.size {
            if self.head == prev.head && self.tail == prev.tail {
                ChangeKind::Unchanged
            } else {
                ChangeKind::Truncated
            }
        } else if self.size < prev.size {
            ChangeKind::Truncated
        } else {
            // Grew. A changed head normally means a rewrite, except for files
            // small enough that the head window covers the whole content.
            if self.head == prev.head || prev.whole {
                ChangeKind::DataAdded
            } else {
                ChangeKind::Truncated
            }
        }
    }
}

fn pread_all(file: &File, buf: &mut [u8], offset: u64) -> io::Result<usize> {
    let mut got = 0;
    while got < buf.len() {
        let n = crate::util::pread(file, &mut buf[got..], offset + got as u64)?;
        if n == 0 {
            break;
        }
        got += n;
    }
    Ok(got)
}

/// Pollable watcher for one file.
pub struct FileWatcher {
    path: PathBuf,
    kind: Arc<Mutex<ChangeKind>>,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
    _notify: Option<RecommendedWatcher>,
}

impl FileWatcher {
    /// Start watching `path` (the file need not exist yet).
    pub fn new(path: impl Into<PathBuf>, config: &WatchConfig) -> io::Result<Self> {
        let path = path.into();
        let kind = Arc::new(Mutex::new(ChangeKind::Unchanged));
        let stop = Arc::new(AtomicBool::new(false));
        let mut notify_watcher = None;

        let (tx, rx) = mpsc::channel::<notify::Result<notify::Event>>();
        if config.use_notify
            && let Ok(mut w) = RecommendedWatcher::new(
                move |ev: notify::Result<notify::Event>| {
                    let _ = tx.send(ev);
                },
                Config::default(),
            )
        {
            // Watch the parent directory: editors replace files, so the
            // file path itself is not always touched.
            if let Some(parent) = path.parent() {
                let _ = w.watch(parent, RecursiveMode::NonRecursive);
            }
            notify_watcher = Some(w);
        }

        let poll = config.poll_interval.unwrap_or(Duration::from_millis(250));
        let path_w = path.clone();
        let kind_w = kind.clone();
        let stop_w = stop.clone();
        let hash_size = config.hash_size;
        // Capture the baseline before the thread starts, so writes that happen
        // right after `new` returns are still detected.
        let last = FileState::capture(&path, hash_size);
        let thread = std::thread::Builder::new()
            .name("loggi-watch".into())
            .spawn(move || {
                let mut last = last;
                let mut last_check = std::time::Instant::now();
                loop {
                    if stop_w.load(Ordering::Relaxed) {
                        break;
                    }
                    // A matching OS event triggers an immediate check; the
                    // poll interval guarantees a check even when the event
                    // stream is noisy or absent (network drives).
                    let ev_match = match rx.recv_timeout(poll) {
                        Ok(ev) => match ev {
                            Ok(ev) => ev
                                .paths
                                .iter()
                                .any(|p| p == &path_w || p.starts_with(&path_w)),
                            Err(_) => true,
                        },
                        Err(mpsc::RecvTimeoutError::Timeout) => false,
                        Err(mpsc::RecvTimeoutError::Disconnected) => false,
                    };
                    if !ev_match && last_check.elapsed() < poll {
                        continue;
                    }
                    last_check = std::time::Instant::now();
                    let cur = FileState::capture(&path_w, hash_size);
                    if cur != last {
                        let change = cur.change_since(&last);
                        last = cur;
                        if change != ChangeKind::Unchanged {
                            *kind_w.lock().unwrap() = change;
                        }
                    }
                }
            })?;

        Ok(FileWatcher {
            path,
            kind,
            stop,
            thread: Some(thread),
            _notify: notify_watcher,
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Latest change since the previous call (resets to `Unchanged`).
    pub fn poll(&self) -> ChangeKind {
        let mut k = self.kind.lock().unwrap();
        let out = *k;
        *k = ChangeKind::Unchanged;
        out
    }
}

impl Drop for FileWatcher {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn change_detection() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("f.log");
        std::fs::write(&p, b"aaaa\n").unwrap();
        let s1 = FileState::capture(&p, 16);
        std::fs::write(&p, b"aaaa\nbbbb\n").unwrap();
        let s2 = FileState::capture(&p, 16);
        assert_eq!(s2.change_since(&s1), ChangeKind::DataAdded);
        std::fs::write(&p, b"cccc\n").unwrap();
        let s3 = FileState::capture(&p, 16);
        assert_eq!(s3.change_since(&s2), ChangeKind::Truncated);
        assert_eq!(s3.change_since(&s3), ChangeKind::Unchanged);
    }

    #[test]
    fn watcher_polls_growth() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("grow.log");
        std::fs::write(&p, b"line1\n").unwrap();
        let w = FileWatcher::new(&p, &WatchConfig::default()).unwrap();
        std::fs::write(&p, b"line1\nline2\nline3\n").unwrap();
        let mut got = ChangeKind::Unchanged;
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while std::time::Instant::now() < deadline {
            got = w.poll();
            if got == ChangeKind::DataAdded {
                break;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        assert_eq!(got, ChangeKind::DataAdded);
    }
}
