//! File indexing: a compressed line-offset index built by a streaming scan.
//!
//! Memory model (per `docs/PLAN.md`): the file itself is never loaded. The index
//! stores end-of-line byte offsets, delta-compressed in blocks of 128 lines
//! (varint deltas + per-block absolute base) for ~1.5-2 bytes/line, with O(1)
//! random access. Partial re-indexing resumes from the last indexed offset so
//! follow-tail cost is O(new data).

use std::fs::File;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};

use memchr::memchr_iter;
use thiserror::Error;

use crate::encoding::{TextEncoding, detect};
use crate::util::{AtomicFlag, Progress, pread};

/// Lines per delta-compressed index block.
pub const BLOCK_SIZE: usize = 128;

#[derive(Error, Debug)]
pub enum IndexError {
    #[error("io error: {0}")]
    Io(#[from] io::Error),
    #[error("operation cancelled")]
    Cancelled,
    #[error("file unchanged since last index")]
    Unchanged,
}

/// Options for indexing and re-indexing.
pub struct IndexOptions {
    /// Streaming read block size in bytes (default 8 MiB).
    pub read_budget: usize,
    /// Run encoding detection (BOM + UTF-8 + chardetng) on the file head.
    pub detect_encoding: bool,
    /// Forced encoding override (skips detection when set).
    pub encoding: Option<TextEncoding>,
    /// Called with throttled (~100 ms) progress while scanning.
    pub progress: Option<Arc<dyn Fn(Progress) + Send + Sync>>,
    /// Cooperative cancellation; checked per streaming block.
    pub cancel: Option<AtomicFlag>,
}

impl Default for IndexOptions {
    fn default() -> Self {
        IndexOptions {
            read_budget: 8 << 20,
            detect_encoding: true,
            encoding: None,
            progress: None,
            cancel: None,
        }
    }
}

/// An immutable snapshot of a file's line index.
///
/// Lines are 0-based. `line_end(i)` is the byte offset one past the line's
/// terminator (LF for byte encodings, LF code unit for UTF-16/32); a final line
/// without a trailing newline ends at the file's last full code unit.
#[derive(Debug)]
pub struct FileIndex {
    path: PathBuf,
    /// Last scanned byte offset (== file size when the file was stable).
    size: u64,
    encoding: TextEncoding,
    /// Number of lines.
    line_count: u64,
    /// Longest line in raw bytes (CR included, terminator excluded).
    max_line_len: u64,
    /// Absolute end-of-line offset of the first line of each full block.
    bases: Vec<u64>,
    /// Start offset into `deltas` for each full block; len = bases.len()+1.
    delta_offs: Vec<u64>,
    /// LEB128 deltas between consecutive line-end offsets, per block.
    deltas: Vec<u8>,
    /// Uncompressed end-of-line offsets of the (possibly partial) last block.
    pending: Vec<u64>,
    /// Byte offset just past the last stored LF, or 0 for empty files.
    last_lf_end: u64,
    /// Bytes of BOM skipped before line 0.
    bom_width: u64,
}

/// A re-indexable, thread-safe handle to the current index of one file.
///
/// Readers take cheap `Arc` snapshots; refresh swaps in a new index atomically,
/// so a reader never observes a half-updated index.
#[derive(Debug, Clone)]
pub struct SharedIndex {
    path: PathBuf,
    inner: Arc<RwLock<Arc<FileIndex>>>,
    /// Bumped whenever the index is replaced (used to invalidate search caches).
    generation: Arc<AtomicU64>,
}

/// A point-in-time summary of a file and its index.
#[derive(Debug, Clone)]
pub struct FileInfo {
    pub path: PathBuf,
    pub size: u64,
    pub line_count: u64,
    pub max_line_len: u64,
    pub encoding: TextEncoding,
    pub index_bytes: u64,
    pub index_time: Duration,
    pub index_throughput_mibs: f64,
}

impl FileIndex {
    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn size(&self) -> u64 {
        self.size
    }

    pub fn encoding(&self) -> TextEncoding {
        self.encoding
    }

    pub fn line_count(&self) -> u64 {
        self.line_count
    }

    pub fn max_line_len(&self) -> u64 {
        self.max_line_len
    }

    pub fn bom_width(&self) -> u64 {
        self.bom_width
    }

    /// Memory used by the index itself (bases + deltas + pending block).
    pub fn index_bytes(&self) -> u64 {
        (self.bases.len() * 8
            + self.delta_offs.len() * 8
            + self.deltas.len()
            + self.pending.len() * 8) as u64
    }

    /// Number of stored line-end offsets (lines with a real terminator).
    #[inline]
    pub fn stored_ends(&self) -> u64 {
        self.bases.len() as u64 * BLOCK_SIZE as u64 + self.pending.len() as u64
    }

    /// True when the last line has a trailing line feed.
    #[inline]
    pub fn last_line_has_lf(&self) -> bool {
        self.size == self.last_lf_end
    }

    /// Byte offset of the end-of-line terminator of line `i` (exclusive end).
    pub fn line_end(&self, line: u64) -> u64 {
        debug_assert!(line < self.line_count);
        let stored = self.stored_ends();
        if line < stored {
            let n = line as usize;
            let b = n / BLOCK_SIZE;
            let k = n % BLOCK_SIZE;
            if b < self.bases.len() {
                let mut acc = self.bases[b];
                let mut p = self.delta_offs[b] as usize;
                let end = self.delta_offs[b + 1] as usize;
                for _ in 0..k {
                    acc += read_varint(&self.deltas, &mut p);
                }
                debug_assert!(p <= end);
                acc
            } else {
                self.pending[k]
            }
        } else {
            self.size - self.size % self.encoding.line_feed_width() as u64
        }
    }

    /// Byte offset of the start of line `i`.
    pub fn line_start(&self, line: u64) -> u64 {
        if line == 0 {
            self.bom_width
        } else {
            self.line_end(line - 1)
        }
    }

    /// True when line `i` has a real terminator (as opposed to the final line
    /// of a file that does not end with a newline).
    pub fn line_has_lf(&self, line: u64) -> bool {
        line < self.stored_ends() || self.last_line_has_lf()
    }

    /// Byte offset just past line `i`'s content, excluding its terminator.
    pub fn line_content_end(&self, line: u64) -> u64 {
        let end = self.line_end(line);
        if self.line_has_lf(line) {
            end - self.encoding.line_feed_width() as u64
        } else {
            end
        }
    }

    /// Content byte length of line `i` (terminator and BOM excluded, CR
    /// included).
    pub fn line_len(&self, line: u64) -> u64 {
        self.line_content_end(line) - self.line_start(line)
    }

    /// Content bytes of line `i`, read into `buf`; returns the line slice.
    pub fn line_bytes<'b>(&self, line: u64, buf: &'b mut Vec<u8>) -> io::Result<&'b [u8]> {
        let (start, end) = (self.line_start(line), self.line_content_end(line));
        buf.clear();
        buf.resize((end - start) as usize, 0);
        let f = File::open(&self.path)?;
        let mut got = 0;
        while got < buf.len() {
            let n = pread(&f, &mut buf[got..], start + got as u64)?;
            if n == 0 {
                break;
            }
            got += n;
        }
        buf.truncate(got);
        Ok(&buf[..got])
    }

    /// A point-in-time summary. `index_time` is supplied by the builder.
    pub fn to_info(&self, index_time: Duration) -> FileInfo {
        let mibs = if index_time.is_zero() {
            0.0
        } else {
            self.size as f64 / index_time.as_secs_f64() / (1 << 20) as f64
        };
        FileInfo {
            path: self.path.clone(),
            size: self.size,
            line_count: self.line_count,
            max_line_len: self.max_line_len,
            encoding: self.encoding,
            index_bytes: self.index_bytes(),
            index_time,
            index_throughput_mibs: mibs,
        }
    }
}

/// Index a file from scratch. Returns an immutable index snapshot.
pub fn index_file(path: &Path, opts: &IndexOptions) -> Result<FileIndex, IndexError> {
    let mut s = Scanner::new(path, opts)?;
    s.scan_from(s.bom_width)?;
    let mut idx = s.finish()?;
    idx.path = path.to_path_buf();
    Ok(idx)
}

/// Re-index a file that has grown or shrunk since `old` was built.
///
/// - size unchanged → `IndexError::Unchanged`
/// - truncated → full re-index
/// - appended → partial re-index resumed from the last indexed offset (the
///   final line without a terminator is rescanned so it merges with new data)
pub fn reindex_partial(
    path: &Path,
    old: &FileIndex,
    opts: &IndexOptions,
) -> Result<FileIndex, IndexError> {
    let new_size = crate::util::file_size(path)?;
    if new_size == old.size {
        return Err(IndexError::Unchanged);
    }
    if new_size < old.size {
        return index_file(path, opts);
    }
    let mut s = Scanner::new(path, opts)?;
    s.resume(old);
    s.scan_from(old.last_lf_end)?;
    let mut idx = s.finish()?;
    idx.path = path.to_path_buf();
    Ok(idx)
}

impl SharedIndex {
    pub fn open(path: impl Into<PathBuf>, opts: &IndexOptions) -> Result<Self, IndexError> {
        let path = path.into();
        let idx = index_file(&path, opts)?;
        Ok(SharedIndex {
            path,
            inner: Arc::new(RwLock::new(Arc::new(idx))),
            generation: Arc::new(AtomicU64::new(0)),
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Cheap, consistent snapshot of the current index.
    pub fn snapshot(&self) -> Arc<FileIndex> {
        self.inner.read().unwrap().clone()
    }

    /// Generation bumped on every index replacement.
    pub fn generation(&self) -> u64 {
        self.generation.load(Ordering::Relaxed)
    }

    /// Check the file for changes and refresh the index if needed.
    ///
    /// Returns `Ok(true)` when a new index was installed, `Ok(false)` when the
    /// file is unchanged. Truncation triggers a full re-index; append triggers
    /// a partial re-index from the last indexed offset.
    pub fn refresh(&self, opts: &IndexOptions) -> Result<bool, IndexError> {
        let old = self.snapshot();
        let new = match reindex_partial(&self.path, &old, opts) {
            Err(IndexError::Unchanged) => return Ok(false),
            other => other?,
        };
        self.generation.fetch_add(1, Ordering::Relaxed);
        *self.inner.write().unwrap() = Arc::new(new);
        Ok(true)
    }

    /// Info of the current snapshot; `index_time` is best-effort.
    pub fn info(&self) -> FileInfo {
        let idx = self.snapshot();
        idx.to_info(Duration::ZERO)
    }
}

/// Streaming scanner that builds the compressed index.
struct Scanner {
    path: PathBuf,
    file: File,
    size: u64,
    encoding: TextEncoding,
    bom_width: u64,
    line_count: u64,
    max_line_len: u64,
    bases: Vec<u64>,
    delta_offs: Vec<u64>,
    deltas: Vec<u8>,
    pending: Vec<u64>,
    last_lf_end: u64,
    last_end: u64,
    budget: usize,
    progress: Option<Arc<dyn Fn(Progress) + Send + Sync>>,
    cancel: Option<AtomicFlag>,
    last_progress: Instant,
    scanned: u64,
}

impl Scanner {
    fn new(path: &Path, opts: &IndexOptions) -> Result<Self, IndexError> {
        let file = File::open(path)?;
        let size = file.metadata()?.len();
        let mut bom_width = 0u64;
        let encoding = match opts.encoding {
            Some(e) => e,
            None if opts.detect_encoding => {
                let mut head = [0u8; 64 * 1024];
                let n = pread(&file, &mut head, 0)?;
                let (enc, bom) = detect(&head[..n]);
                bom_width = bom as u64;
                enc
            }
            None => TextEncoding::Utf8,
        };
        Ok(Scanner {
            path: path.to_path_buf(),
            file,
            size,
            encoding,
            bom_width,
            line_count: 0,
            max_line_len: 0,
            bases: Vec::new(),
            delta_offs: vec![0],
            deltas: Vec::new(),
            pending: Vec::new(),
            last_lf_end: 0,
            last_end: 0,
            budget: opts.read_budget.max(64 * 1024),
            progress: opts.progress.clone(),
            cancel: opts.cancel.clone(),
            last_progress: Instant::now(),
            scanned: 0,
        })
    }

    /// Carry over compressed state from a previous index for partial re-index.
    fn resume(&mut self, old: &FileIndex) {
        self.bases = old.bases.clone();
        self.delta_offs = old.delta_offs.clone();
        self.deltas = old.deltas.clone();
        self.pending = old.pending.clone();
        self.last_lf_end = old.last_lf_end;
        self.last_end = old.last_lf_end;
        // The final line without a terminator is rescanned (it merges with new
        // data), so the line count restarts from the stored ends.
        self.line_count = old.stored_ends();
        self.max_line_len = old.max_line_len;
        self.scanned = old.size;
    }

    /// Push one line-end offset (absolute, already validated as a real LF).
    #[inline]
    fn push_end(&mut self, end: u64) {
        self.pending.push(end);
        if self.pending.len() == BLOCK_SIZE {
            let base = self.pending[0];
            let mut prev = base;
            for &v in &self.pending[1..] {
                write_varint(&mut self.deltas, v - prev);
                prev = v;
            }
            self.bases.push(base);
            self.delta_offs.push(self.deltas.len() as u64);
            self.pending.clear();
        }
    }

    fn scan_from(&mut self, start: u64) -> Result<(), IndexError> {
        let width = self.encoding.line_feed_width() as u64;
        let mut pos = start;
        let mut line_start = start;
        let eof = self.size;
        // carry: up to width-1 unverified tail bytes from the previous block
        let mut carry = Vec::with_capacity(4);
        let mut buf = vec![0u8; self.budget];
        let mut combined: Vec<u8> = Vec::new();

        loop {
            if let Some(cancel) = &self.cancel
                && cancel.is_set()
            {
                return Err(IndexError::Cancelled);
            }
            let read_len = (eof - pos).min(self.budget as u64) as usize;
            if read_len == 0 {
                break;
            }
            let n = pread(&self.file, &mut buf[..read_len], pos)?;
            if n == 0 {
                break;
            }
            let combined_len = carry.len() + n;
            combined.clear();
            combined.resize(combined_len, 0);
            combined[..carry.len()].copy_from_slice(&carry);
            combined[carry.len()..].copy_from_slice(&buf[..n]);
            // absolute file offset of combined[0]
            let base_abs = pos - carry.len() as u64;
            for lf in memchr_iter(b'\n', &combined[..combined_len]) {
                // LFs whose pair bytes are not fully present are deferred into
                // the carry buffer and re-examined with the next block; at EOF
                // an incomplete pair is not a line feed.
                if lf + 1 + self.encoding.lf_pair_after() > combined_len {
                    continue;
                }
                let lf_abs = base_abs + lf as u64;
                if !self.encoding.is_lf_at(&combined[..combined_len], lf) {
                    continue;
                }
                // Line end = one byte past the LF code unit (for little-endian
                // encodings the pair bytes follow the 0x0A byte).
                let line_end = lf_abs + 1 + self.encoding.lf_pair_after() as u64;
                let len = line_end - width - line_start;
                if len > self.max_line_len {
                    self.max_line_len = len;
                }
                self.push_end(line_end);
                self.last_lf_end = line_end;
                self.line_count += 1;
                line_start = line_end;
            }
            let tail_keep = (width - 1) as usize;
            if combined_len >= tail_keep {
                carry.clear();
                carry.extend_from_slice(&combined[combined_len - tail_keep..]);
            }
            pos += n as u64;
            self.scanned = pos;

            if let Some(progress) = &self.progress
                && self.last_progress.elapsed() >= Duration::from_millis(100)
            {
                self.last_progress = Instant::now();
                progress(Progress {
                    done: pos,
                    total: eof,
                });
            }
        }

        // Final line without a trailing LF: clamp to a whole code unit.
        let final_end = self.size - self.size % width;
        if line_start < final_end {
            let len = final_end - line_start;
            if len > self.max_line_len {
                self.max_line_len = len;
            }
            self.line_count += 1;
        }
        Ok(())
    }

    fn finish(self) -> Result<FileIndex, IndexError> {
        let size = self.scanned;
        Ok(FileIndex {
            path: self.path,
            size,
            encoding: self.encoding,
            line_count: self.line_count,
            max_line_len: self.max_line_len,
            bases: self.bases,
            delta_offs: self.delta_offs,
            deltas: self.deltas,
            pending: self.pending,
            last_lf_end: self.last_lf_end,
            bom_width: self.bom_width,
        })
    }
}

#[inline]
fn read_varint(bytes: &[u8], pos: &mut usize) -> u64 {
    let mut shift = 0u32;
    let mut out = 0u64;
    loop {
        let b = bytes[*pos];
        *pos += 1;
        out |= ((b & 0x7f) as u64) << shift;
        if b & 0x80 == 0 {
            return out;
        }
        shift += 7;
        debug_assert!(shift < 64);
    }
}

#[inline]
fn write_varint(out: &mut Vec<u8>, mut v: u64) {
    while v >= 0x80 {
        out.push((v as u8) | 0x80);
        v >>= 7;
    }
    out.push(v as u8);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn write_file(name: &str, bytes: &[u8]) -> std::path::PathBuf {
        let dir = Box::leak(Box::new(tempfile::tempdir().unwrap()));
        let p = dir.path().join(name);
        let mut f = File::create(&p).unwrap();
        f.write_all(bytes).unwrap();
        p
    }

    #[test]
    fn basic_utf8_index() {
        let p = write_file("a.log", b"one\ntwo\nthree");
        let idx = index_file(&p, &IndexOptions::default()).unwrap();
        assert_eq!(idx.line_count(), 3);
        assert_eq!(idx.line_len(0), 3);
        assert_eq!(idx.line_len(2), 5);
        assert_eq!(idx.line_start(1), 4);
        assert_eq!(idx.line_end(2), 13);
        assert_eq!(idx.size(), 13);
        assert!(!idx.last_line_has_lf());
        assert_eq!(idx.max_line_len(), 5);
        assert_eq!(idx.encoding(), TextEncoding::Utf8);
    }

    #[test]
    fn trailing_lf_and_crlf() {
        let p = write_file("b.log", b"a\r\nb\r\n");
        let idx = index_file(&p, &IndexOptions::default()).unwrap();
        assert_eq!(idx.line_count(), 2);
        assert_eq!(idx.line_len(0), 2); // "a\r" includes CR
        assert!(idx.last_line_has_lf());
    }

    #[test]
    fn empty_and_only_lf() {
        let p = write_file("empty.log", b"");
        let idx = index_file(&p, &IndexOptions::default()).unwrap();
        assert_eq!(idx.line_count(), 0);
        let p = write_file("lf.log", b"\n");
        let idx = index_file(&p, &IndexOptions::default()).unwrap();
        assert_eq!(idx.line_count(), 1);
        assert_eq!(idx.line_len(0), 0);
    }

    #[test]
    fn huge_single_line() {
        let p = write_file("huge.log", &vec![b'x'; 10 << 20]); // 10 MiB, no LF
        let idx = index_file(&p, &IndexOptions::default()).unwrap();
        assert_eq!(idx.line_count(), 1);
        assert_eq!(idx.line_len(0), 10 << 20);
        assert_eq!(idx.max_line_len(), 10 << 20);
    }

    #[test]
    fn block_boundaries() {
        // > BLOCK_SIZE lines to exercise delta blocks
        let mut bytes = Vec::with_capacity(400 * 20);
        for i in 0..400u32 {
            bytes.extend_from_slice(format!("line-{i:04}\n").as_bytes());
        }
        let p = write_file("blocks.log", &bytes);
        let idx = index_file(&p, &IndexOptions::default()).unwrap();
        assert_eq!(idx.line_count(), 400);
        assert_eq!(idx.bases.len(), 3); // 400 / 128 = 3 full blocks
        assert_eq!(idx.pending.len(), 400 - 3 * 128);
        for i in 0..400u64 {
            let (s, e) = (idx.line_start(i), idx.line_content_end(i));
            assert_eq!(
                &bytes[s as usize..e as usize],
                format!("line-{i:04}").as_bytes()
            );
        }
        assert!(idx.index_bytes() < 400 * 8);
    }

    #[test]
    fn utf16le_index() {
        let utf16: Vec<u8> = b"alpha\nbeta\ngamma".iter().flat_map(|&b| [b, 0]).collect();
        let p = write_file("u16.log", &utf16);
        let idx = index_file(&p, &IndexOptions::default()).unwrap();
        assert_eq!(idx.encoding(), TextEncoding::Utf16Le);
        assert_eq!(idx.line_count(), 3);
        assert_eq!(idx.line_len(0), 10);
        assert_eq!(idx.line_start(1), 12);
        assert_eq!(idx.line_end(2), utf16.len() as u64);
        assert_eq!(idx.max_line_len(), 10);
    }

    #[test]
    fn utf16be_index() {
        let utf16: Vec<u8> = b"alpha\nbeta\ngamma".iter().flat_map(|&b| [0, b]).collect();
        let p = write_file("u16be.log", &utf16);
        let idx = index_file(&p, &IndexOptions::default()).unwrap();
        assert_eq!(idx.encoding(), TextEncoding::Utf16Be);
        assert_eq!(idx.line_count(), 3);
        assert_eq!(idx.line_len(0), 10);
        assert_eq!(idx.line_start(1), 12);
        assert_eq!(idx.line_end(2), utf16.len() as u64);
    }

    #[test]
    fn utf16_lone_byte_lf_ignored() {
        // 0x0A byte inside a code unit (e.g. U+4A0A "䨊" LE = 0A 4A) is not a line feed
        let bytes = [b'a', 0, 0x0A, 0x4A, 0x0A, 0, b'b', 0];
        let p = write_file("u16trap.log", &bytes);
        let idx = index_file(&p, &IndexOptions::default()).unwrap();
        assert_eq!(idx.line_count(), 2);
        assert_eq!(idx.line_len(0), 4); // "a" + U+4A0A
        assert_eq!(idx.line_start(1), 6);
    }

    #[test]
    fn bom_is_skipped() {
        let p = write_file("bom.log", b"\xEF\xBB\xBFhello\nworld");
        let idx = index_file(&p, &IndexOptions::default()).unwrap();
        assert_eq!(idx.bom_width, 3);
        assert_eq!(idx.line_start(0), 3);
        assert_eq!(idx.line_count(), 2);
        let mut buf = Vec::new();
        assert_eq!(idx.line_bytes(0, &mut buf).unwrap(), b"hello");
    }

    #[test]
    fn partial_reindex_grows_and_truncates() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("grow.log");
        std::fs::write(&p, b"one\ntwo\n").unwrap();
        let idx1 = index_file(&p, &IndexOptions::default()).unwrap();
        assert_eq!(idx1.line_count(), 2);

        // append, no trailing LF at first
        let mut f = std::fs::OpenOptions::new().append(true).open(&p).unwrap();
        f.write_all(b"three").unwrap();
        drop(f);
        let idx2 = reindex_partial(&p, &idx1, &IndexOptions::default()).unwrap();
        assert_eq!(idx2.line_count(), 3);
        assert_eq!(idx2.line_len(2), 5);
        assert_eq!(idx2.size(), 13);

        // append LF -> fake line becomes LF-terminated
        let mut f = std::fs::OpenOptions::new().append(true).open(&p).unwrap();
        f.write_all(b"four\n").unwrap();
        drop(f);
        let idx3 = reindex_partial(&p, &idx2, &IndexOptions::default()).unwrap();
        assert_eq!(idx3.line_count(), 3);
        // the previous fake line merged with the new data
        assert_eq!(idx3.line_len(2), 9);
        assert!(idx3.last_line_has_lf());

        // truncate -> full reindex
        std::fs::write(&p, b"x\n").unwrap();
        let idx4 = reindex_partial(&p, &idx3, &IndexOptions::default()).unwrap();
        assert_eq!(idx4.line_count(), 1);
        assert_eq!(idx4.line_len(0), 1);
    }

    #[test]
    fn unchanged_returns_error() {
        let p = write_file("same.log", b"a\nb\n");
        let idx = index_file(&p, &IndexOptions::default()).unwrap();
        assert!(matches!(
            reindex_partial(&p, &idx, &IndexOptions::default()),
            Err(IndexError::Unchanged)
        ));
    }

    #[test]
    fn cancel_works() {
        let p = write_file("cancel.log", &vec![b'x'; 32 << 20]);
        let flag = AtomicFlag::new();
        let opts = IndexOptions {
            cancel: Some(flag.clone()),
            ..Default::default()
        };
        flag.set();
        assert!(matches!(index_file(&p, &opts), Err(IndexError::Cancelled)));
    }
}
