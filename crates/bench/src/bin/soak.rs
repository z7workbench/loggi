//! Soak harness: open → info → search → read → close cycles with RSS
//! sampling. Flat RSS is the pass criterion (no leaks / unbounded caches).
//!
//! Usage: `soak <file> [cycles] [max-growth-MiB]`
//!
//! The default growth budget is 32 MiB over 200 cycles. Set the third arg to
//! tighten or relax; the soak exits non-zero when the post-warmup RSS growth
//! exceeds the budget. The first 10 cycles are the warmup window and are
//! excluded from the budget so a per-thread malloc-zone ramp does not fail
//! the gate.

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
    let path = args
        .get(1)
        .expect("usage: soak <file> [cycles] [max-growth-MiB]");
    let cycles: usize = args.get(2).map(|s| s.parse().unwrap()).unwrap_or(200);
    let max_growth_mib: f64 = args.get(3).map(|s| s.parse().unwrap()).unwrap_or(32.0);
    let t0 = Instant::now();
    let mut rss_samples: Vec<u64> = Vec::new();
    eprintln!(
        "soak: {} cycles on {} (max growth {:.0} MiB after warmup)",
        cycles, path, max_growth_mib
    );

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
        let rss = rss_bytes();
        rss_samples.push(rss);
        if c % 10 == 0 || c == cycles - 1 {
            eprintln!(
                "cycle {c:>3}: rss = {:6.1} MiB",
                rss as f64 / (1 << 20) as f64
            );
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    let final_rss = rss_bytes();
    let warmup = (cycles / 20).max(5);
    let post_warm = rss_samples.get(warmup).copied().unwrap_or(0);
    let growth_mib = (final_rss as f64 - post_warm as f64) / (1 << 20) as f64;
    eprintln!(
        "soak done in {:.1}s: warmup rss {:6.1} MiB -> final {:6.1} MiB (growth {:+6.1} MiB over last {} cycles)",
        t0.elapsed().as_secs_f64(),
        post_warm as f64 / (1 << 20) as f64,
        final_rss as f64 / (1 << 20) as f64,
        growth_mib,
        cycles - warmup,
    );
    if growth_mib > max_growth_mib {
        eprintln!(
            "FAIL: RSS growth {:.1} MiB exceeds budget {:.1} MiB",
            growth_mib, max_growth_mib
        );
        std::process::exit(1);
    }
    eprintln!("soak: PASS");
}
