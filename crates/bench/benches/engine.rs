//! Criterion benchmarks for the loggi engine (fast subset; see docs/benchmarks.md).

use std::sync::Arc;
use std::time::Duration;

use criterion::{Criterion, Throughput, criterion_group, criterion_main};
use loggi_engine::index::{IndexOptions, index_file};
use loggi_engine::reader::LazyReader;
use loggi_engine::search::{SearchEngine, SearchOptions};

/// Generate `size` bytes of realistic-ish log lines into `path`.
fn gen_log(path: &std::path::Path, size: usize, line: &[u8]) {
    use std::io::Write;
    let mut f = std::fs::File::create(path).unwrap();
    let mut written = 0usize;
    let mut i = 0u64;
    while written < size {
        let l = format!(
            "{i:010} 2026-08-07 12:00:00 ERROR payload chunk data error {:?}\n",
            i
        );
        let b = l.as_bytes();
        if written + b.len() > size {
            let _ = line;
            break;
        }
        f.write_all(b).unwrap();
        written += b.len();
        i += 1;
    }
}

fn bench_index(c: &mut Criterion) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("index.log");
    gen_log(&path, 128 << 20, b"x"); // 128 MiB
    let opts = IndexOptions::default();
    let mut group = c.benchmark_group("index/128MiB");
    group.throughput(Throughput::Bytes(128 << 20));
    group.bench_function("index", |b| {
        b.iter(|| {
            let _ = index_file(&path, &opts).unwrap();
        })
    });
    group.finish();
}

fn bench_search(c: &mut Criterion) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("search.log");
    gen_log(&path, 256 << 20, b"x"); // 256 MiB
    let index = Arc::new(index_file(&path, &IndexOptions::default()).unwrap());
    let engine = SearchEngine::new(index.clone());
    let mut group = c.benchmark_group("search/256MiB");
    group.throughput(Throughput::Bytes(256 << 20));

    let mut lit = SearchOptions::new("ERROR");
    lit.use_regex = false;
    group.bench_function("literal", |b| {
        b.iter(|| {
            let _ = engine.search_count(&lit).unwrap();
        })
    });

    let mut ci = SearchOptions::new("error");
    ci.ignore_case = true;
    ci.use_regex = false;
    group.bench_function("literal-ignore-case", |b| {
        b.iter(|| {
            let _ = engine.search_count(&ci).unwrap();
        })
    });

    let rx = SearchOptions::new("ERR[OA]R");
    group.bench_function("regex", |b| {
        b.iter(|| {
            let _ = engine.search_count(&rx).unwrap();
        })
    });
    group.finish();
}

fn bench_read(c: &mut Criterion) {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("read.log");
    gen_log(&path, 128 << 20, b"x");
    let index = Arc::new(index_file(&path, &IndexOptions::default()).unwrap());
    let reader = LazyReader::new(index);
    let mut group = c.benchmark_group("read/128MiB");
    group.throughput(Throughput::Bytes(128 << 20));
    group.bench_function("sequential-chunks", |b| {
        b.iter(|| {
            let n = reader.index().line_count();
            let mut buf = Vec::new();
            let mut pos = 0u64;
            while pos < n {
                let r = reader.read_lines(pos, 10_000, &mut buf).unwrap();
                if r.end_line <= pos {
                    break;
                }
                pos = r.end_line;
            }
        })
    });
    group.bench_function("random-chunks", |b| {
        b.iter(|| {
            let n = reader.index().line_count();
            let mut buf = Vec::new();
            let _pos = 0u64;
            let mut x = 0x9E3779B97F4A7C15u64;
            for _ in 0..100 {
                x = x
                    .wrapping_mul(6364136223846793005)
                    .wrapping_add(1442695040888963407);
                let p = x % n;
                let _ = reader.read_lines(p, 500, &mut buf).unwrap();
            }
        })
    });
    group.finish();
}

criterion_group! {
    name = index;
    config = Criterion::default().sample_size(10).warm_up_time(Duration::from_secs(2)).measurement_time(Duration::from_secs(5));
    targets = bench_index
}

criterion_group! {
    name = search;
    config = Criterion::default().sample_size(10).warm_up_time(Duration::from_secs(2)).measurement_time(Duration::from_secs(5));
    targets = bench_search
}

criterion_group! {
    name = read;
    config = Criterion::default().sample_size(10).warm_up_time(Duration::from_secs(2)).measurement_time(Duration::from_secs(5));
    targets = bench_read
}

criterion_main!(index, search, read);
