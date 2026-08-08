//! Synthetic log file generator.
//!
//! Usage: `gen-log <size> <pattern> <path>` where size accepts suffixes
//! (e.g. `1g`, `500m`, `256MiB`) and pattern is one of:
//! - `repeat`  fixed-length repeating lines (dense, compressible)
//! - `json`    random JSON-ish log lines
//! - `long`    long lines (~8 KiB each)
//! - `single`  one huge line without any line feed
//! - `utf16`   UTF-16LE with BOM
//! - `nolf`    no trailing line feed at EOF

use std::io::Write;
use std::path::PathBuf;

/// xorshift64 PRNG: dependency-free, deterministic.
struct Xorshift(u64);

impl Xorshift {
    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }
}

fn parse_size(s: &str) -> u64 {
    let s = s.trim().to_ascii_lowercase();
    let (num, mult) = if let Some(n) = s.strip_suffix("kib") {
        (n, 1024)
    } else if let Some(n) = s.strip_suffix("mib") {
        (n, 1024 * 1024)
    } else if let Some(n) = s.strip_suffix("gib") {
        (n, 1024 * 1024 * 1024)
    } else if let Some(n) = s.strip_suffix('k') {
        (n, 1000)
    } else if let Some(n) = s.strip_suffix('m') {
        (n, 1000 * 1000)
    } else if let Some(n) = s.strip_suffix('g') {
        (n, 1000 * 1000 * 1000)
    } else {
        (s.as_str(), 1)
    };
    let v: f64 = num.parse().expect("bad size");
    (v * mult as f64) as u64
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 4 {
        eprintln!("usage: gen-log <size> <pattern> <path>");
        eprintln!("patterns: repeat | json | long | single | utf16 | nolf");
        std::process::exit(2);
    }
    let size = parse_size(&args[1]);
    let pattern = args[2].as_str();
    let path = PathBuf::from(&args[3]);
    let mut rng = Xorshift(0x9E3779B97F4A7C15);

    let mut f = std::io::BufWriter::new(std::fs::File::create(&path).expect("create file"));
    let mut written = 0u64;
    if pattern == "utf16" {
        f.write_all(&[0xFF, 0xFE]).unwrap(); // UTF-16LE BOM once, at file start
        written += 2;
    }
    let mut i = 0u64;
    if pattern == "single" {
        f.write_all(&vec![b'x'; size as usize]).unwrap();
        written = size;
    }
    while written < size {
        let line: Vec<u8> = match pattern {
            "single" => break,
            "repeat" => format!(
                "2026-08-07 12:00:00.123456 {i:010} INFO  request handled in 42 ms error none\n"
            )
            .into_bytes(),
            "json" => {
                let lvl = ["INFO", "WARN", "ERROR"][(rng.next() % 3) as usize];
                let id = rng.next() % 1_000_000;
                format!(
                    "{{\"ts\":\"2026-08-07T12:00:00.{:06}Z\",\"level\":\"{lvl}\",\"id\":{id},\"msg\":\"request {i} processed\",\"dur_ms\":{}}}\n",
                    rng.next() % 500,
                    rng.next() % 500
                )
                .into_bytes()
            }
            "long" => {
                let mut s = String::with_capacity(8192 + 32);
                s.push_str(&format!("LONG {i:010} "));
                for _ in 0..512 {
                    s.push_str("payload-chunk-abcdef-123456 ");
                }
                s.push('\n');
                s.into_bytes()
            }
            "utf16" => {
                format!("2026-08-07 12:00:00.123456 {i:010} INFO  UTF16 line error payload\n")
                    .encode_utf16()
                    .flat_map(|u| u.to_le_bytes())
                    .collect::<Vec<u8>>()
            }
            _ => format!("2026-08-07 12:00:00.123456 {i:010} INFO  plain line payload error\n")
                .into_bytes(),
        };
        if written + line.len() as u64 > size {
            break; // stop without a trailing LF (`nolf` behavior at EOF)
        }
        f.write_all(&line).unwrap();
        written += line.len() as u64;
        i += 1;
    }
    f.flush().unwrap();
    eprintln!(
        "generated {} bytes, {} lines -> {}",
        written,
        i,
        path.display()
    );
}
