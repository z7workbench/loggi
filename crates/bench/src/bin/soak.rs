//! Soak harness: open → info → search → read → close cycles with RSS
//! sampling. Flat RSS is the pass criterion (no leaks / unbounded caches).
//!
//! Usage: `soak <file> [cycles]`

use std::time::{Duration, Instant};

use loggi_engine::index::{IndexOptions, SharedIndex};
use loggi_engine::reader::LazyReader;
use loggi_engine::search::{SearchEngine, SearchOptions};

fn rss_bytes() -> u64 {
    memory_stats::memory_stats()
        .map(|m| m.physical_mem as u64)
        .unwrap_or(0)
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let path = args.get(1).expect("usage: soak <file> [cycles]");
    let cycles: usize = args.get(2).map(|s| s.parse().unwrap()).unwrap_or(50);
    let t0 = Instant::now();
    let mut last_rss = rss_bytes();
    eprintln!("soak: {} cycles on {}", cycles, path);

    for c in 0..cycles {
        let shared = SharedIndex::open(path, &IndexOptions::default()).unwrap();
        let engine = SearchEngine::new(shared.snapshot());
        let reader = LazyReader::new(engine.index().clone());

        let info = engine.index().to_info(Duration::ZERO);
        let mut opts = SearchOptions::new("ERROR");
        opts.use_regex = false;
        let mut found = 0u64;
        let mut batches = 0u64;
        let results = engine
            .search_with(&opts, |_s, lines| {
                found += lines.len() as u64;
                batches += 1;
                true
            })
            .unwrap();
        assert_eq!(found, results.matches.len());

        // read a few random windows
        let n = info.line_count;
        let mut buf = Vec::new();
        for _ in 0..20 {
            let start = (c as u64 * 97 + 13) % n.max(1);
            let _ = reader.read_lines(start, 500, &mut buf).unwrap();
        }

        drop(results);
        drop(engine);
        drop(shared);
        if c % 10 == 0 || c == cycles - 1 {
            let rss = rss_bytes();
            eprintln!(
                "cycle {c:>3}: rss = {:.1} MiB (delta {:+8.1} MiB)",
                rss as f64 / (1 << 20) as f64,
                (rss as f64 - last_rss as f64) / (1 << 20) as f64
            );
            last_rss = rss;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    let final_rss = rss_bytes();
    eprintln!(
        "soak done in {:.1}s: rss {:.1} MiB -> {:.1} MiB (growth {:.1} MiB)",
        t0.elapsed().as_secs_f64(),
        rss_bytes() as f64 / (1 << 20) as f64,
        final_rss as f64 / (1 << 20) as f64,
        (final_rss as f64 - last_rss as f64) / (1 << 20) as f64
    );
}
