//! Lazy line reading: one contiguous `pread` per visible chunk, positioned by
//! the index. The UTF-8 fast path returns raw byte views; other encodings are
//! decoded lossily per chunk (chunk boundaries are code-unit aligned).

use std::fs::File;
use std::io;
use std::sync::Arc;

use crate::index::FileIndex;
use crate::util::pread;

/// Result of a lazy read: the byte range of the file that landed in `buf`.
#[derive(Debug, Clone, Copy)]
pub struct LineRead {
    /// First line requested (== the `start` argument).
    pub start_line: u64,
    /// Exclusive end line actually read (may be < start+count if the byte
    /// budget was hit — call again with `end_line` to continue).
    pub end_line: u64,
    /// File byte offset of `buf[0]`.
    pub byte_start: u64,
    /// Bytes available in the caller's buffer.
    pub byte_len: u64,
}

/// Index-positioned reader with a per-read byte cap so multi-GB single lines
/// never blow the chunk buffer.
#[derive(Debug, Clone)]
pub struct LazyReader {
    index: Arc<FileIndex>,
    budget: usize,
}

impl LazyReader {
    pub fn new(index: Arc<FileIndex>) -> Self {
        LazyReader {
            index,
            budget: 8 << 20,
        }
    }

    /// Reader with a custom per-read byte cap.
    pub fn with_budget(index: Arc<FileIndex>, budget: usize) -> Self {
        LazyReader { index, budget }
    }

    pub fn index(&self) -> &Arc<FileIndex> {
        &self.index
    }

    /// Read lines `[start, start+count)` into `out` with one contiguous read
    /// (segmented if the byte range exceeds the budget). Returns the byte range
    /// covered; line slices are `out[a..b]` where `a = idx.line_start(i) -
    /// read.byte_start` and `b = idx.line_end(i) - read.byte_start`.
    pub fn read_lines(&self, start: u64, count: u64, out: &mut Vec<u8>) -> io::Result<LineRead> {
        let n = self.index.line_count();
        let start = start.min(n);
        let mut end = start.saturating_add(count).min(n);
        if start == end {
            out.clear();
            return Ok(LineRead {
                start_line: start,
                end_line: end,
                byte_start: 0,
                byte_len: 0,
            });
        }
        let byte_start = self.index.line_start(start);
        let mut byte_end = self.index.line_end(end - 1);
        // Cap the byte range; drop whole lines from the end until it fits.
        while byte_end - byte_start > self.budget as u64 && end > start {
            end -= 1;
            byte_end = self.index.line_end(end - 1);
        }
        if end == start {
            out.clear();
            return Ok(LineRead {
                start_line: start,
                end_line: end,
                byte_start,
                byte_len: 0,
            });
        }
        out.clear();
        out.resize((byte_end - byte_start) as usize, 0);
        let f = File::open(self.index.path())?;
        let mut got = 0usize;
        while got < out.len() {
            let n = pread(&f, &mut out[got..], byte_start + got as u64)?;
            if n == 0 {
                break;
            }
            got += n;
        }
        out.truncate(got);
        Ok(LineRead {
            start_line: start,
            end_line: end,
            byte_start,
            byte_len: got as u64,
        })
    }

    /// Slice the content bytes of line `i` (in `[start_line, end_line)`) out of
    /// the buffer produced by `read_lines`, excluding its terminator.
    pub fn line_view<'b>(&self, read: &LineRead, buf: &'b [u8], line: u64) -> &'b [u8] {
        debug_assert!(line >= read.start_line && line < read.end_line);
        let a = (self.index.line_start(line) - read.byte_start) as usize;
        let b = (self.index.line_content_end(line) - read.byte_start) as usize;
        &buf[a.min(buf.len())..b.min(buf.len())]
    }

    /// Read up to `budget` bytes of a single line, starting at `byte_offset`
    /// within the line. Returns `(next_offset, done)` where `done` is true when
    /// the line's content was fully covered. Multi-GB single lines are read in
    /// bounded segments this way.
    pub fn read_line_chunk(
        &self,
        line: u64,
        byte_offset: u64,
        out: &mut Vec<u8>,
    ) -> io::Result<(u64, bool)> {
        let n = self.index.line_count();
        if line >= n {
            out.clear();
            return Ok((0, true));
        }
        let start = self.index.line_start(line);
        let content_len = self.index.line_content_end(line) - start;
        let off = byte_offset.min(content_len);
        let want = (content_len - off).min(self.budget as u64) as usize;
        out.clear();
        out.resize(want, 0);
        let f = File::open(self.index.path())?;
        let mut got = 0;
        while got < want {
            let r = pread(&f, &mut out[got..], start + off + got as u64)?;
            if r == 0 {
                break;
            }
            got += r;
        }
        out.truncate(got);
        let next = off + got as u64;
        Ok((next, next >= content_len))
    }

    /// Decode a raw line slice to UTF-8, stripping a trailing CR, expanding
    /// tabs (display path).
    pub fn decode_line(&self, raw: &[u8], tab_stop: usize, out: &mut String) {
        let raw = strip_cr(raw);
        if self.index.encoding().is_utf8() {
            if tab_stop == 0 {
                out.push_str(unsafe { std::str::from_utf8_unchecked(raw) });
            } else {
                expand_tabs_str(raw, tab_stop, out);
            }
        } else {
            let mut decoded = String::new();
            self.index.encoding().decode_to_string(raw, &mut decoded);
            if tab_stop == 0 {
                out.push_str(&decoded);
            } else {
                expand_tabs_str(decoded.as_bytes(), tab_stop, out);
            }
        }
    }
}

/// Strip a trailing `\r` from a line (does not allocate; returns a slice).
pub fn strip_cr(line: &[u8]) -> &[u8] {
    match line.last() {
        Some(b'\r') => &line[..line.len() - 1],
        _ => line,
    }
}

/// Expand tabs to spaces at `tab_stop` column intervals (default 8).
pub fn expand_tabs(line: &[u8], tab_stop: usize, out: &mut Vec<u8>) {
    let mut col = 0usize;
    for &b in line {
        if b == b'\t' && tab_stop > 0 {
            let pad = tab_stop - col % tab_stop;
            out.extend(std::iter::repeat_n(b' ', pad));
            col += pad;
        } else {
            out.push(b);
            col = if b == b'\n' || b == b'\r' { 0 } else { col + 1 };
        }
    }
}

fn expand_tabs_str(line: &[u8], tab_stop: usize, out: &mut String) {
    let mut tmp = Vec::with_capacity(line.len() + line.len() / 4);
    expand_tabs(line, tab_stop, &mut tmp);
    out.push_str(unsafe { std::str::from_utf8_unchecked(&tmp) });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::index::{IndexOptions, index_file};
    use std::io::Write;

    fn make_index(bytes: &[u8]) -> Arc<FileIndex> {
        let dir = Box::leak(Box::new(tempfile::tempdir().unwrap()));
        let p = dir.path().join("f.log");
        let mut f = File::create(&p).unwrap();
        f.write_all(bytes).unwrap();
        Arc::new(index_file(&p, &IndexOptions::default()).unwrap())
    }

    #[test]
    fn contiguous_chunk_read() {
        let bytes = b"aaa\nbbbbbb\ncc\ndddd\n";
        let idx = make_index(bytes);
        let reader = LazyReader::new(idx.clone());
        let mut buf = Vec::new();
        let read = reader.read_lines(0, 4, &mut buf).unwrap();
        assert_eq!(read.start_line, 0);
        assert_eq!(read.end_line, 4);
        assert_eq!(read.byte_start, 0);
        assert_eq!(&buf, &bytes[..]);
        for i in 0..4 {
            let v = reader.line_view(&read, &buf, i);
            assert_eq!(
                v,
                match i {
                    0 => b"aaa".as_slice(),
                    1 => b"bbbbbb",
                    2 => b"cc",
                    3 => b"dddd",
                    _ => unreachable!(),
                }
            );
        }
    }

    #[test]
    fn partial_chunk_at_start() {
        let bytes = b"aaa\nbbbbbb\ncc\ndddd\n";
        let idx = make_index(bytes);
        let reader = LazyReader::new(idx);
        let mut buf = Vec::new();
        let read = reader.read_lines(2, 1, &mut buf).unwrap();
        assert_eq!(read.end_line, 3);
        assert_eq!(read.start_line, 2);
        assert_eq!(&buf, b"cc\n");
        assert_eq!(reader.line_view(&read, &buf, 2), b"cc");
    }

    #[test]
    fn budget_caps_byte_range() {
        let line = vec![b'x'; 1000];
        let mut bytes = Vec::new();
        for _ in 0..100 {
            bytes.extend_from_slice(&line);
            bytes.push(b'\n');
        }
        let idx = make_index(&bytes);
        let reader = LazyReader::with_budget(idx.clone(), 10_000);
        let mut buf = Vec::new();
        let mut pos = 0u64;
        while pos < 100 {
            let read = reader.read_lines(pos, 100 - pos, &mut buf).unwrap();
            assert!(read.end_line > pos, "no progress");
            assert!(buf.len() <= 10_000);
            pos = read.end_line;
        }
        assert_eq!(pos, 100);
    }

    #[test]
    fn huge_single_line_segmented() {
        let idx = make_index(&vec![b'x'; 1 << 20]);
        let reader = LazyReader::with_budget(idx.clone(), 64 * 1024);
        let mut buf = Vec::new();
        let mut off = 0u64;
        let mut total = 0usize;
        loop {
            let (next, done) = reader.read_line_chunk(0, off, &mut buf).unwrap();
            assert!(buf.len() <= 64 * 1024);
            total += buf.len();
            if done {
                break;
            }
            off = next;
        }
        assert_eq!(total, 1 << 20);
    }

    #[test]
    fn cr_strip_and_tab_expand() {
        assert_eq!(strip_cr(b"ab\r"), b"ab");
        let mut out = Vec::new();
        expand_tabs(b"a\tb", 8, &mut out);
        assert_eq!(&out, b"a       b");
        let mut out = Vec::new();
        expand_tabs(b"1234567\tx", 8, &mut out);
        assert_eq!(&out, b"1234567 x");
    }

    #[test]
    fn decode_utf16_line() {
        let utf16: Vec<u8> = "héllo\n"
            .encode_utf16()
            .flat_map(|u| u.to_le_bytes())
            .collect();
        let idx = make_index(&utf16);
        assert!(!idx.encoding().is_utf8());
        let reader = LazyReader::new(idx);
        let mut buf = Vec::new();
        let read = reader.read_lines(0, 1, &mut buf).unwrap();
        let raw = reader.line_view(&read, &buf, 0);
        let mut s = String::new();
        reader.decode_line(raw, 0, &mut s);
        assert_eq!(s, "héllo");
    }
}
