//! Output helpers: human + NDJSON rendering of lines and matches.

use std::io::Write;

use anyhow::Result;
use loggi_engine::TextEncoding;
use loggi_engine::reader::LazyReader;
use loggi_engine::search::{SearchEngine, SearchOptions};

const MATCH_RED: &str = "\x1b[0;31m";
const RESET: &str = "\x1b[0m";

pub fn encoding_name(e: TextEncoding) -> &'static str {
    match e {
        TextEncoding::Utf8 => "UTF-8",
        TextEncoding::Utf16Le => "UTF-16LE",
        TextEncoding::Utf16Be => "UTF-16BE",
        TextEncoding::Utf32Le => "UTF-32LE",
        TextEncoding::Utf32Be => "UTF-32BE",
        TextEncoding::Other(enc) => enc.name(),
    }
}

pub fn info_json(info: &loggi_engine::FileInfo) -> Result<String> {
    Ok(serde_json::json!({
        "path": info.path.to_string_lossy(),
        "size": info.size,
        "line_count": info.line_count,
        "max_line_len": info.max_line_len,
        "encoding": encoding_name(info.encoding),
        "index_bytes": info.index_bytes,
        "index_time_s": info.index_time.as_secs_f64(),
        "index_throughput_mibs": info.index_throughput_mibs,
    })
    .to_string())
}

fn read_line_text<'b>(
    reader: &LazyReader,
    buf: &'b mut Vec<u8>,
    line: u64,
) -> Option<(&'b [u8], String)> {
    let read = reader.read_lines(line, 1, buf).ok()?;
    if read.end_line <= line {
        return None;
    }
    let raw = reader.line_view(&read, buf, line);
    let mut text = String::new();
    reader.decode_line(raw, 0, &mut text);
    text.push('\n');
    Some((raw, text))
}

/// Common rendering options for match output.
pub struct PrintOptions<'a> {
    pub engine: &'a SearchEngine,
    pub reader: &'a LazyReader,
    pub opts: &'a SearchOptions,
    pub no_line_numbers: bool,
    pub color: bool,
}

/// Print match lines with optional line numbers and red match highlighting.
pub fn print_lines(po: &PrintOptions<'_>, matches: &[u64]) -> Result<u64> {
    let mut buf = Vec::new();
    let mut count = 0u64;
    let stdout = std::io::stdout();
    let mut w = stdout.lock();
    for &line in matches {
        let Some((raw, text)) = read_line_text(po.reader, &mut buf, line) else {
            continue;
        };
        if po.no_line_numbers {
            w.write_all(text.as_bytes())?;
        } else {
            w.write_all(format!("{}:", line).as_bytes())?;
            if po.color && po.engine.index().encoding().is_utf8() {
                w.write_all(
                    highlighted(
                        text.trim_end_matches('\n').as_bytes(),
                        raw,
                        po.engine,
                        po.opts,
                    )
                    .as_bytes(),
                )?;
                w.write_all(b"\n")?;
            } else {
                w.write_all(text.as_bytes())?;
            }
        }
        count += 1;
    }
    w.flush()?;
    Ok(count)
}

/// Wrap match spans in ANSI red (positions are relative to the decoded text;
/// only used on the UTF-8 fast path where raw == decoded).
fn highlighted(text: &[u8], raw: &[u8], engine: &SearchEngine, opts: &SearchOptions) -> String {
    let mut out = String::new();
    let mut pos = 0usize;
    if let Ok(spans) = engine.match_positions(opts, raw) {
        for (a, b) in spans {
            let (a, b) = (a as usize, b as usize);
            out.push_str(unsafe {
                std::str::from_utf8_unchecked(&text[pos.min(text.len())..a.min(text.len())])
            });
            out.push_str(MATCH_RED);
            out.push_str(unsafe {
                std::str::from_utf8_unchecked(&text[a.min(text.len())..b.min(text.len())])
            });
            out.push_str(RESET);
            pos = b;
        }
    }
    out.push_str(unsafe { std::str::from_utf8_unchecked(&text[pos.min(text.len())..]) });
    out
}

/// Print matches with context windows, rg-style: `N:text` for matches and
/// `N-text` for context, `--` between groups.
pub fn print_context(
    po: &PrintOptions<'_>,
    matches: &[u64],
    before: u64,
    after: u64,
) -> Result<()> {
    let mut selected: Vec<u64> = Vec::new();
    let max_line = po.reader.index().line_count().saturating_sub(1);
    for &m in matches {
        let a = m.saturating_sub(before);
        let b = (m + after).min(max_line);
        selected.extend(a..=b);
    }
    selected.sort_unstable();
    selected.dedup();
    let matches_set: std::collections::BTreeSet<u64> = matches.iter().copied().collect();
    let mut buf = Vec::new();
    let stdout = std::io::stdout();
    let mut w = stdout.lock();
    let mut prev: Option<u64> = None;
    for line in selected {
        if let Some(p) = prev
            && line > p + 1
        {
            w.write_all(b"--\n")?;
        }
        let Some((raw, text)) = read_line_text(po.reader, &mut buf, line) else {
            continue;
        };
        let is_match = matches_set.contains(&line);
        let sep = if is_match { ":" } else { "-" };
        if po.no_line_numbers {
            w.write_all(text.as_bytes())?;
        } else {
            w.write_all(format!("{line}{sep}").as_bytes())?;
            if is_match && po.color && po.engine.index().encoding().is_utf8() {
                w.write_all(
                    highlighted(
                        text.trim_end_matches('\n').as_bytes(),
                        raw,
                        po.engine,
                        po.opts,
                    )
                    .as_bytes(),
                )?;
                w.write_all(b"\n")?;
            } else {
                w.write_all(text.as_bytes())?;
            }
        }
        prev = Some(line);
    }
    w.flush()?;
    Ok(())
}

/// NDJSON stream: one object per match line (rg-compatible shape).
pub fn print_json_matches(
    engine: &SearchEngine,
    reader: &LazyReader,
    opts: &SearchOptions,
    matches: &[u64],
    path: &str,
) -> Result<()> {
    let stdout = std::io::stdout();
    let mut w = stdout.lock();
    let mut buf = Vec::new();
    for &line in matches {
        let Some((raw, text)) = read_line_text(reader, &mut buf, line) else {
            continue;
        };
        let positions: Vec<serde_json::Value> = engine
            .match_positions(opts, raw)
            .unwrap_or_default()
            .into_iter()
            .map(|(a, b)| serde_json::json!({ "start": a, "end": b }))
            .collect();
        let obj = serde_json::json!({
            "type": "match",
            "path": path,
            "line_number": line + 1,
            "lines": { "text": text },
            "matches": positions,
        });
        w.write_all(obj.to_string().as_bytes())?;
        w.write_all(b"\n")?;
    }
    w.flush()?;
    Ok(())
}
