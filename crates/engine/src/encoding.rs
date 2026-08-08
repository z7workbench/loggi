//! File encoding detection and lossy decoding.
//!
//! Detection order (M0: BOM + UTF-8 validation; M2: chardetng full auto-detection):
//! BOM first, then UTF-8 validation over a head sample, then chardetng.

use chardetng::EncodingDetector;
use encoding_rs::{Encoding, UTF_8, UTF_16BE, UTF_16LE};

/// Text encoding of a log file.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TextEncoding {
    Utf8,
    Utf16Le,
    Utf16Be,
    Utf32Le,
    Utf32Be,
    /// Any other encoding from `encoding_rs` (Windows-1252, Shift_JIS, ...).
    Other(&'static Encoding),
}

impl TextEncoding {
    /// Bytes consumed by a line-feed character in this encoding (1, 2 or 4).
    pub fn line_feed_width(self) -> usize {
        match self {
            TextEncoding::Utf8 | TextEncoding::Other(_) => 1,
            TextEncoding::Utf16Le | TextEncoding::Utf16Be => 2,
            TextEncoding::Utf32Le | TextEncoding::Utf32Be => 4,
        }
    }

    /// Bytes of the LF code unit that follow the 0x0A byte (little-endian).
    /// Big-endian encodings keep them before the 0x0A byte, so a scan only
    /// needs to defer incomplete pairs for little-endian encodings.
    pub fn lf_pair_after(self) -> usize {
        match self {
            TextEncoding::Utf16Le | TextEncoding::Utf32Le => self.line_feed_width() - 1,
            _ => 0,
        }
    }

    /// True when the file bytes are directly usable as UTF-8 (`&str` views).
    pub fn is_utf8(self) -> bool {
        matches!(self, TextEncoding::Utf8)
    }

    /// Byte offset of a BOM at the start of `head`, if present.
    pub fn bom_width(self) -> usize {
        match self {
            TextEncoding::Utf8 => 3,
            TextEncoding::Utf16Le | TextEncoding::Utf16Be => 2,
            TextEncoding::Utf32Le | TextEncoding::Utf32Be => 4,
            TextEncoding::Other(_) => 0,
        }
    }

    /// Is the LF byte at `pos` (within a chunk starting at absolute offset 0 of a
    /// read block) a real line feed for this encoding? Single-byte encodings
    /// never contain 0x0A inside a multi-byte character, so this is only a check
    /// for UTF-16/32 where a lone 0x0A byte may be half of another code unit.
    #[inline]
    pub fn is_lf_at(self, bytes: &[u8], pos: usize) -> bool {
        match self {
            TextEncoding::Utf8 | TextEncoding::Other(_) => true,
            TextEncoding::Utf16Le => bytes.get(pos + 1) == Some(&0),
            TextEncoding::Utf16Be => pos > 0 && bytes[pos - 1] == 0,
            TextEncoding::Utf32Le => {
                pos.is_multiple_of(4) && bytes.get(pos + 1..pos + 4) == Some(&[0, 0, 0][..])
            }
            TextEncoding::Utf32Be => pos % 4 == 3 && pos >= 3 && bytes[pos - 3..pos] == [0, 0, 0],
        }
    }

    /// Decode `bytes` lossily into UTF-8. Chunk boundaries are line boundaries
    /// (and therefore code-unit aligned), so a fresh decoder is safe per chunk.
    pub fn decode_to_string(self, bytes: &[u8], out: &mut String) {
        match self {
            TextEncoding::Utf8 => out.push_str(&UTF_8.decode_without_bom_handling(bytes).0),
            TextEncoding::Utf16Le => out.push_str(&UTF_16LE.decode_without_bom_handling(bytes).0),
            TextEncoding::Utf16Be => out.push_str(&UTF_16BE.decode_without_bom_handling(bytes).0),
            TextEncoding::Utf32Le => decode_utf32(bytes, true, out),
            TextEncoding::Utf32Be => decode_utf32(bytes, false, out),
            TextEncoding::Other(e) => out.push_str(&e.decode_without_bom_handling(bytes).0),
        }
    }
}

/// Minimal lossy UTF-32 decoder (encoding_rs does not cover UTF-32).
fn decode_utf32(bytes: &[u8], little_endian: bool, out: &mut String) {
    let units = bytes.chunks_exact(4).count();
    let mut i = 0usize;
    while i + 4 <= bytes.len() {
        let b = &bytes[i..i + 4];
        let u = if little_endian {
            u32::from_le_bytes([b[0], b[1], b[2], b[3]])
        } else {
            u32::from_be_bytes([b[0], b[1], b[2], b[3]])
        };
        i += 4;
        match char::from_u32(u) {
            Some(c) => out.push(c),
            None => out.push('\u{FFFD}'),
        }
    }
    if i < bytes.len() {
        out.push('\u{FFFD}');
    }
    let _ = units;
}

/// Detect the encoding from the head of a file.
///
/// Order: BOM → NUL-byte-pattern UTF-16/32 detection → UTF-8 validation →
/// chardetng (legacy single-byte encodings). chardetng alone cannot be trusted
/// for UTF-16: it returns UTF-8/windows-1252 on NUL-heavy input.
pub fn detect(head: &[u8]) -> (TextEncoding, usize) {
    if head.starts_with(&[0xEF, 0xBB, 0xBF]) {
        return (TextEncoding::Utf8, 3);
    }
    // UTF-32LE BOM before UTF-16LE BOM (it is a prefix).
    if head.starts_with(&[0xFF, 0xFE, 0x00, 0x00]) {
        return (TextEncoding::Utf32Le, 4);
    }
    if head.starts_with(&[0xFF, 0xFE]) {
        return (TextEncoding::Utf16Le, 2);
    }
    if head.starts_with(&[0xFE, 0xFF]) {
        return (TextEncoding::Utf16Be, 2);
    }
    if head.starts_with(&[0x00, 0x00, 0xFE, 0xFF]) {
        return (TextEncoding::Utf32Be, 4);
    }
    if let Some(enc) = nul_pattern(head) {
        return (enc, 0);
    }
    if std::str::from_utf8(head).is_ok() {
        return (TextEncoding::Utf8, 0);
    }
    let mut det = EncodingDetector::new();
    det.feed(head, true);
    let enc = match det.guess(None, true) {
        e if e == UTF_8 => TextEncoding::Utf8,
        e if e == UTF_16LE => TextEncoding::Utf16Le,
        e if e == UTF_16BE => TextEncoding::Utf16Be,
        e => TextEncoding::Other(e),
    };
    (enc, 0)
}

/// Guess UTF-16/32 from the distribution of NUL bytes: in UTF-16LE every odd
/// byte is 0x00 (even for BE); in UTF-32 three of every four bytes are 0x00
/// for BMP content. Returns `None` when the pattern is not dominant.
fn nul_pattern(head: &[u8]) -> Option<TextEncoding> {
    let len = head.len() as u64;
    if len < 8 {
        return None;
    }
    let mut mods = [0u64; 4];
    for (i, &b) in head.iter().enumerate() {
        if b == 0 {
            mods[i % 4] += 1;
        }
    }
    let odd = mods[1] + mods[3];
    let even = mods[0] + mods[2];
    let q = len / 8;
    if odd > len / 4 {
        if mods[1] > q && mods[2] > q && mods[3] > q {
            Some(TextEncoding::Utf32Le)
        } else {
            Some(TextEncoding::Utf16Le)
        }
    } else if even > len / 4 {
        if mods[0] > q && mods[1] > q && mods[2] > q {
            Some(TextEncoding::Utf32Be)
        } else {
            Some(TextEncoding::Utf16Be)
        }
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_boms() {
        assert_eq!(detect(&[0xEF, 0xBB, 0xBF, b'h']).0, TextEncoding::Utf8);
        assert_eq!(detect(&[0xFF, 0xFE, 0x0A, 0x00]).0, TextEncoding::Utf16Le);
        assert_eq!(detect(&[0xFE, 0xFF, 0x00, 0x0A]).0, TextEncoding::Utf16Be);
        assert_eq!(detect(&[0xFF, 0xFE, 0x00, 0x00]).0, TextEncoding::Utf32Le);
        assert_eq!(detect(&[0x00, 0x00, 0xFE, 0xFF]).0, TextEncoding::Utf32Be);
        // BOM widths
        assert_eq!(detect(&[0xEF, 0xBB, 0xBF, b'h']).1, 3);
        assert_eq!(detect(&[0xFF, 0xFE, 0x0A, 0x00]).1, 2);
        assert_eq!(detect(b"no bom here").1, 0);
    }

    #[test]
    fn utf8_validation_and_chardetng() {
        assert_eq!(detect(b"hello world\n").0, TextEncoding::Utf8);
        // ASCII-heavy UTF-16LE without BOM is caught by the NUL pattern.
        let ascii16: Vec<u8> = b"alpha\nbeta\ngamma\n"
            .iter()
            .flat_map(|&b| [b, 0])
            .collect();
        assert_eq!(detect(&ascii16).0, TextEncoding::Utf16Le);
        // CJK UTF-16LE: chardetng cannot guess it without a BOM (known
        // limitation, documented in docs/PLAN.md M2) — a BOM fixes it.
        let cjk16: Vec<u8> = "你好世界，这是一个很长的日志行\n"
            .encode_utf16()
            .flat_map(|u| u.to_le_bytes())
            .collect();
        let (enc, _bom) = detect(&cjk16);
        assert_ne!(enc, TextEncoding::Utf8);
        let mut with_bom = vec![0xFF, 0xFE];
        with_bom.extend_from_slice(&cjk16);
        assert_eq!(detect(&with_bom), (TextEncoding::Utf16Le, 2));
    }

    #[test]
    fn utf8_missing_bom_but_valid() {
        // GBK-ish bytes with no BOM: not valid UTF-8 -> chardetng guess.
        let gbk = [0xc4, 0xe3, 0xba, 0xc3]; // "你好" in GBK
        assert_ne!(detect(&gbk).0, TextEncoding::Utf8);
    }

    #[test]
    fn lf_verification() {
        let le = TextEncoding::Utf16Le;
        assert!(le.is_lf_at(&[b'a', 0, b'b', 0, 0x0A, 0], 4));
        assert!(!le.is_lf_at(&[b'a', 0, 0x0A, 0x0A], 2)); // 0x0A inside 0x0A0A? invalid pair
        let be = TextEncoding::Utf16Be;
        assert!(be.is_lf_at(&[0, b'a', 0, b'b', 0, 0x0A], 5));
    }

    #[test]
    fn decode_lossy() {
        let mut s = String::new();
        TextEncoding::Utf16Le.decode_to_string(&[b'a', 0, 0x0A, 0], &mut s);
        assert_eq!(s, "a\n");
        let mut s = String::new();
        TextEncoding::Utf8.decode_to_string(&[0xFF], &mut s);
        assert!(s.contains('\u{FFFD}'));
    }
}
