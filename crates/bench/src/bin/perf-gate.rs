//! Perf gate for CI: literal search throughput on a generated corpus must stay
//! above a conservative threshold (regression gate, M4).
//!
//! Usage: `perf-gate [file]` — generates a 512 MiB corpus (or reuses the given
//! file) and times indexing + literal search.

use std::io::Write;
use std::time::Instant;

use loggi_engine::index::{IndexOptions, SharedIndex};
use loggi_engine::search::{SearchEngine, SearchOptions};

const MIN_INDEX_MIBS: f64 = 200.0; // conservative: modern hardware does 1-3 GiB/s
const MIN_SEARCH_MIBS: f64 = 300.0;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let file = match args.get(1) {
        Some(f) => std::path::PathBuf::from(f),
        None => {
            let dir = Box::leak(Box::new(tempfile::tempdir().unwrap()));
            let p = dir.path().join("perf-gate.log");
            eprintln!("generating 512 MiB corpus...");
            gen_corpus(&p, 512 << 20);
            p
        }
    };
    if !file.is_file() {
        eprintln!("perf-gate: no such file: {}", file.display());
        std::process::exit(2);
    }

    let t = Instant::now();
    let shared = SharedIndex::open(&file, &IndexOptions::default()).unwrap();
    let idx = shared.snapshot();
    let index_mibs = idx.size() as f64 / t.elapsed().as_secs_f64() / (1 << 20) as f64;
    eprintln!(
        "index: {:.0} MiB/s ({} lines)",
        index_mibs,
        idx.line_count()
    );

    let engine = SearchEngine::new(idx);
    let mut opts = SearchOptions::new("error");
    opts.use_regex = false;
    let t = Instant::now();
    let count = engine.search_count(&opts).unwrap().matches;
    let search_mibs = engine.index().size() as f64 / t.elapsed().as_secs_f64() / (1 << 20) as f64;
    eprintln!(
        "literal search: {:.0} MiB/s ({} matches)",
        search_mibs, count
    );

    let mut ci = SearchOptions::new("ERROR");
    ci.ignore_case = true;
    ci.use_regex = false;
    let t = Instant::now();
    let _ = engine.search_count(&ci).unwrap().matches;
    let ci_mibs = engine.index().size() as f64 / t.elapsed().as_secs_f64() / (1 << 20) as f64;
    eprintln!("ignore-case search: {:.0} MiB/s", ci_mibs);

    let rx = SearchOptions::new("ERR[OA]R|WARN");
    let t = Instant::now();
    let _ = engine.search_count(&rx).unwrap().matches;
    let rx_mibs = engine.index().size() as f64 / t.elapsed().as_secs_f64() / (1 << 20) as f64;
    eprintln!("regex search: {:.0} MiB/s", rx_mibs);

    let mut ok = true;
    if index_mibs < MIN_INDEX_MIBS {
        eprintln!("FAIL: index below {MIN_INDEX_MIBS} MiB/s");
        ok = false;
    }
    if search_mibs < MIN_SEARCH_MIBS {
        eprintln!("FAIL: literal search below {MIN_SEARCH_MIBS} MiB/s");
        ok = false;
    }
    eprintln!("perf gate: {}", if ok { "PASS" } else { "FAIL" });
    std::process::exit(if ok { 0 } else { 1 });
}

fn gen_corpus(path: &std::path::Path, size: u64) {
    let mut f = std::io::BufWriter::new(std::fs::File::create(path).unwrap());
    let mut written = 0u64;
    let mut i = 0u64;
    while written < size {
        let l = format!("2026-08-07 12:00:00.123456 {i:010} INFO  request handled error none\n");
        if written + l.len() as u64 > size {
            break;
        }
        f.write_all(l.as_bytes()).unwrap();
        written += l.len() as u64;
        i += 1;
    }
    f.flush().unwrap();
}
