//! `loggi tail`: print the last N lines, optionally following appended data.

use std::io::Write;
use std::path::Path;
use std::time::Duration;

use anyhow::Result;
use loggi_engine::index::IndexOptions;
use loggi_engine::index::SharedIndex;
use loggi_engine::reader::LazyReader;

/// Print the last `lines` lines of `file`. With `follow`, keep printing lines
/// as they are appended (watch + partial reindex, O(new data) per batch).
pub fn cmd_tail(file: &Path, lines: u64, follow: bool) -> Result<()> {
    if !file.is_file() {
        anyhow::bail!("no such file: {}", file.display());
    }
    let opts = IndexOptions {
        progress: None,
        ..Default::default()
    };
    let shared = SharedIndex::open(file, &opts)?;
    let mut idx = shared.snapshot();
    let reader = LazyReader::new(idx.clone());

    let start = idx.line_count().saturating_sub(lines);
    print_range(&reader, start, idx.line_count())?;
    let mut last_line = idx.line_count();

    if !follow {
        return Ok(());
    }
    let watcher = loggi_engine::FileWatcher::new(file, &loggi_engine::WatchConfig::default())?;
    loop {
        let mut changed = false;
        loop {
            match watcher.poll() {
                loggi_engine::ChangeKind::Unchanged => break,
                loggi_engine::ChangeKind::DataAdded => changed = true,
                loggi_engine::ChangeKind::Truncated => {
                    anyhow::bail!("file truncated; restart to re-tail");
                }
            }
        }
        if changed {
            let _ = shared.refresh(&opts);
            idx = shared.snapshot();
            let reader = LazyReader::new(idx.clone());
            let n = idx.line_count();
            if n > last_line {
                print_range(&reader, last_line, n)?;
                last_line = n;
            }
        }
        std::thread::sleep(Duration::from_millis(200));
    }
}

fn print_range(reader: &LazyReader, start: u64, end: u64) -> Result<()> {
    let mut buf = Vec::new();
    let mut pos = start;
    let stdout = std::io::stdout();
    let mut w = stdout.lock();
    while pos < end {
        let read = reader.read_lines(pos, (end - pos).min(10_000), &mut buf)?;
        if read.end_line <= pos {
            break;
        }
        let mut text = String::new();
        for line in pos..read.end_line {
            let raw = reader.line_view(&read, &buf, line);
            text.clear();
            reader.decode_line(raw, 0, &mut text);
            w.write_all(text.as_bytes())?;
            w.write_all(b"\n")?;
        }
        pos = read.end_line;
    }
    w.flush()?;
    Ok(())
}
