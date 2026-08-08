# Benchmarks (M4 baseline)

All numbers measured on the development machine (macOS, Apple Silicon, warm
page cache) in release mode (`--release`), M4 baseline, 2026-08-07. CI perf gate
uses conservative thresholds (see `crates/bench/src/bin/perf-gate.rs`).

## Real-world corpus: Android bugreport (243.2 MiB, 2,221,698 lines)

`testcase/bugreport-dijun-BP2A.250605.031.A3-2026-08-07-00-45-23.txt`

| Operation | Time | Throughput |
|---|---|---|
| Index (full) | 0.073 s | 3,314 MiB/s |
| Search literal `-F "WifiStateMachine"` | 0.123 s total (incl. index) | — |
| Search literal `-F "ERROR"` | 0.121 s | 2,000 MiB/s |
| Search literal `-F -i "android"` (259,953 matches) | 0.129 s | 1,900 MiB/s |
| Search regex `CRITICAL\|FATAL` | 0.122 s | 2,000 MiB/s |
| Search literal, range `--line-offset 1000000 "service"` | 0.100 s | — |
| Search `--limit 5 "PackageManager"` | 0.090 s | — |

Index memory: **1.41 bytes/line** (3.0 MiB total for the whole file).
Longest line: 54,675 bytes (handled by segmented reads).

Reference comparison (`rg -c`, same corpus, raw grep without indexing):

| Pattern | rg | loggi (incl. index) |
|---|---|---|
| `ERROR` | 0.048 s | 0.121 s |
| `-i android` | 0.070 s | 0.129 s |
| `CRITICAL\|FATAL` | 0.051 s | 0.122 s |

loggi's combined index+search is within ~2.5× of `rg`'s raw search; the index
is reused across searches, so subsequent searches on the same file (or the UI's
scroll/display path) amortize the indexing cost entirely. Informational only —
the M1 acceptance criterion (≥ 1-2 GB/s effective search throughput on warm
cache) is met: 2.0-3.1 GB/s measured.

## Criterion micro-benchmarks (synthetic corpora)

| Benchmark | Time | Throughput |
|---|---|---|
| `index/128MiB` | 30.4 ms | 4,214 MiB/s |
| `search/256MiB/literal` | 58.5 ms | 4,378 MiB/s |
| `search/256MiB/literal-ignore-case` | 92.0 ms | 2,783 MiB/s |
| `search/256MiB/regex` | 81.2 ms | 3,154 MiB/s |
| `read/128MiB/sequential-chunks` | 10.8 ms | 11,852 MiB/s |
| `read/128MiB/random-chunks` (100 × 500 lines) | 1.16 ms | — |

## Perf gate (`cargo run --release -p loggi-bench --bin perf-gate`, 512 MiB)

| Metric | Measured | CI threshold |
|---|---|---|
| Index throughput | 3,501 MiB/s | ≥ 200 MiB/s |
| Literal search throughput | 3,091 MiB/s | ≥ 300 MiB/s |
| Ignore-case search | 2,197 MiB/s | — |
| Regex search | 3,955 MiB/s | — |

## Soak (open → search → read → close cycles, RSS sampling)

100 cycles on the 243 MiB bugreport: RSS plateaus at ~75 MiB after ~15 cycles
(+0.0-0.2 MiB per cycle thereafter). `leaks --atExit` reports **0 leaks** over
30 cycles. Residual RSS variance across runs is macOS malloc per-thread zone
retention of freed chunk buffers (bounded by threads × 4 MiB search chunk
budget), not process retention — the process-wide search pool keeps it bounded.

## Key tuning decisions (first pass)

1. **Shared process-wide rayon pool** (threads = available parallelism): per-
   open pool churn caused RSS growth across open/search/close cycles
   (malloc zones + thread stacks). One pool, kept alive, flattens RSS.
2. **Chunk budget 4 MiB** for search reads, 8 MiB for indexing, 8 MiB for
   display reads: balances stripe parallelism vs buffer retention.
3. **RoaringTreemap** with inclusive `rank`/`select`: dense-result rank/select
   throughput verified at 200k dense matches (select loop < 5 s bound, ~µs per
   select).
4. **Compressed index blocks (128 lines, varint deltas)**: 1.41 bytes/line on
   the bugreport corpus vs 8 bytes/line raw.
5. **memchr literal fast path**: case-sensitive single-literal searches scan
   whole chunks (no per-line allocations); aho-corasick for multi-literal;
   `regex` for regex/ignore-case modes.

## Regression gate

CI runs `perf-gate` on ubuntu-latest (Linux) with the thresholds above. Nightly
runs the full criterion matrix plus the soak on a 1 GiB generated corpus.
