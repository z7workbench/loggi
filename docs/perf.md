# Performance & memory hardening (M9)

This document captures the per-platform performance and memory model for
loggi, the budgets every code path is allowed to assume, and the gates CI
enforces to catch regressions. It is the canonical reference for the M9
acceptance criteria in `docs/PLAN.md` §3.

## TL;DR

- **Index memory**: ≤ 8 bytes/line on real-world corpora; **1.41 bytes/line**
  measured on the Android bugreport corpus (243 MiB, 2.2M lines).
- **Search results cache**: bounded by `DEFAULT_CACHE_CAP_LINES = 1_000_000`
  lines (M4) — large enough to cover real logs, small enough that a runaway
  pattern cannot balloon RSS.
- **Display chunk cache**: `ChunkCache` LRU of 96 chunks × 512 lines = ~49K
  lines of decoded text, capped.
- **JNI matcher cache**: 64-entry LRU (M9); each entry is a compiled regex /
  aho-corasick / memchr literal matcher for one (pattern, ignore_case,
  use_regex) key.
- **Process-wide search pool**: one rayon pool shared by every engine
  (`global_pool()`), sized to the machine's available parallelism; keeps RSS
  flat across open → search → close cycles.
- **Soak gate**: 200 open/search/close cycles, RSS growth ≤ 32 MiB after a
  10-cycle warmup window. CI runs the fast subset (1 GiB generated corpus).

## Memory model

### What is held in memory

A `SharedIndex` opens with:

| Component | Size | Bound |
|---|---|---|
| Compressed line-offset index | ~1.5–2 bytes/line (varint deltas, 128-line blocks) | `BLOCK_SIZE = 128`; per-block `Vec<u64>` base + per-line varint delta |
| `FileWatcher` hash windows (head + tail) | `2 × 5 MiB` = 10 MiB when configured with the default `hash_size` | `WatchConfig::hash_size` (default 5 MiB) |
| Search results cache | ≤ 1 bit/line (Roaring) × up to 1M lines (≈ 128 KiB at 1 bit/line, with Roaring run/array containers the real number is much lower) | `DEFAULT_CACHE_CAP_LINES` |
| Per-thread chunk buffers | `4 MiB` search / `8 MiB` index / `8 MiB` display | hard caps in the engine |
| `ChunkCache` (UI) | 96 chunks × 512 lines × ~120 bytes/line ≈ 5.5 MiB worst case | `maxChunks = 96` LRU |

The file content itself is never loaded. Display and search read directly
from disk via `pread`, so a 100 GB log uses roughly the same RSS as a 1 GB
log (modulo bitsets, which grow with matches, not file size).

### Index bytes/line budget

`FileIndex::index_bytes` reports the live memory used by the line-offset
index. For the Android bugreport corpus (243 MiB, 2,221,698 lines) we
measure **1.41 bytes/line** (3.0 MiB total). The plan allows up to 8
bytes/line; the M9 budget holds with a 5× margin.

A unit test (`bytes_per_line_within_budget` in `crates/engine/src/index.rs`)
asserts the budget holds on a synthetic 100k-line corpus so a future
regression in the varint packing is caught before it lands.

### Search results cache

`SearchCache` is bounded by `DEFAULT_CACHE_CAP_LINES = 1_000_000` total
cardinality across entries, with the largest entry evicted when the budget
is exceeded. Dense results that would exceed the budget on their own are
rejected at insert time. The cache is invalidated on every index refresh
(generation bump in `SharedIndex`, engine swap in the JNI bridge).

`crates/engine/src/search.rs` has two M9 unit tests:

- `cycles_dont_grow_cache` — 100 identical sparse searches leave the cache
  with one entry, not 100.
- `dense_results_not_cached` — 20 dense searches leave the cache empty
  (entries that would exceed the cap are refused at insert time).

### Display chunk cache

`shared/src/jvmMain/.../model/ChunkCache.kt`:

```kotlin
private val maxChunks: Int = 96  // 49K lines ≈ 5.5 MiB worst case
private val chunks = object : LinkedHashMap<Long, LineChunk>(16, 0.75f, true) {
    override fun removeEldestEntry(...) = size > maxChunks
}
```

LRU; `ensure` re-touches present chunks (read-touches count) so the
results-pane churn cannot evict the log view's visible chunks (M8.5
white-screen fix; regression test in `SearchPaneUiTest`).

### JNI matcher cache (M9 new)

`crates/engine-jni/src/lib.rs` adds a bounded LRU:

```rust
const MATCHER_CACHE_CAP: usize = 64;
matchers: Mutex<LruMap<(String, bool, bool), Arc<HighlightMatcher>>>,
```

Each `HighlightMatcher` compiles a `regex` / `aho-corasick` / `memchr`
literal matcher once and reuses it for every visible line. Without the cap
a long session that touches many distinct highlighter patterns would
retain all of them; the LRU evicts cold patterns on the 65th distinct key.

### Process-wide search pool

`crates/engine/src/search.rs`:

```rust
static GLOBAL_POOL: OnceLock<Arc<ThreadPool>> = OnceLock::new();
```

A single rayon pool sized to `available_parallelism()` is shared by every
`SearchEngine::new()` (the `threads == 0` default). Per-engine pools
caused thread-stack + malloc-zone churn across open/search/close cycles
(M4 tuning). One pool, kept alive, flattens RSS.

`SearchEngine::with_config(index, n, cap)` still accepts an explicit thread
count for tests and benchmarking; `SearchEngine::new(index)` is the
production default.

### Watcher deregistration

`FileWatcher::drop` sets the stop flag and `join`s the polling thread; the
OS-level `notify::RecommendedWatcher` is dropped (its `Drop` deregisters
the kernel watch). `engine-jni/src/lib.rs` `closeFile` joins every
in-flight search and removes the `Arc<HandleInner>` from the registry map
so the engine and the index-thread's clone are both released.

## Performance gates

### `perf-gate` (CI, every push / PR)

`cargo run --release -p loggi-bench --bin perf-gate` generates a 512 MiB
synthetic corpus and measures:

| Metric | Threshold (conservative) |
|---|---|
| Index throughput | ≥ 200 MiB/s |
| Literal search throughput | ≥ 300 MiB/s |
| Ignore-case search throughput | (informational) |
| Regex search throughput | (informational) |

These thresholds have ~5× headroom on the M4 baseline (3500/3000 MiB/s
respectively on warm cache). The run is bounded to a single core
allocation so CI sees the worst case on a busy runner.

### `soak` (nightly, full matrix)

`cargo run --release -p loggi-bench --bin soak <file> <cycles> <max-growth-MiB>`

Default budget: **200 cycles × 512 MiB corpus × 32 MiB post-warmup growth
budget**. The first 10 cycles are excluded from the budget so the rayon
pool ramp and per-thread malloc-zone retention do not fail the gate.

The harness prints warmup RSS, final RSS, and the growth delta; it exits
non-zero when the growth exceeds the budget. The nightly job also runs
the full criterion matrix and a 1 GiB soak.

### `cargo bench -p loggi-bench` (nightly, full matrix)

Criterion micro-benchmarks covering index/search/read throughput; the
results are written to `target/criterion/` and compared across runs.

## Tuning decisions (M4 first pass, M9 reaffirmed)

1. **One process-wide rayon pool**: per-engine pools caused RSS growth
   across open/search/close cycles (malloc zones + thread stacks). Shared
   pool keeps RSS flat.
2. **Chunk budget 4 MiB** for search reads, 8 MiB for indexing, 8 MiB for
   display reads. Balances stripe parallelism vs buffer retention.
3. **RoaringTreemap with `rank`/`select`**: dense-result rank/select
   throughput verified at 200k dense matches (`dense_results_rank_select_throughput`).
4. **Compressed index blocks (128 lines, varint deltas)**: 1.41 bytes/line
   on the Android bugreport vs 8 bytes/line raw.
5. **memchr literal fast path**: case-sensitive single-literal searches
   scan whole chunks (no per-line allocations); aho-corasick for
   multi-literal; `regex` for regex/ignore-case modes.
6. **JNI matcher cache cap (M9)**: 64-entry LRU prevents unbounded
   highlighter matcher retention in long sessions.
7. **Display chunk cap (M8.5)**: 96 chunks × 512 lines caps the log-view
   decoded-line memory at ~5.5 MiB worst case.

## Profiling

| Platform | Tools |
|---|---|
| Linux | `perf`, `heaptrack`, `valgrind --tool=massif` |
| macOS | Instruments (Time Profiler, Allocations, Leaks) |
| Windows | VS/ETW, WPA |

The M9 acceptance run uses `perf` on Linux (the CI runner) and Instruments
on macOS (the dev box). The bench harness prints RSS per cycle, so a
visual inspection of the soak output is enough to spot monotonic growth.

## Open knobs (M9 stretch backlog)

- **mmap backend** (`memmap2` + `MADV_SEQUENTIAL`) behind a feature flag.
  Skipped for M9 because `pread` over the page cache is already at the
  SSD-read ceiling on the M4 baseline; mmap would only help for files
  larger than the page cache, which is a different milestone.
- **Index compression on disk** (serialize the line-offset index for
  instant reopen). Skipped for M9; the open path is fast enough that
  the engineering cost is not justified yet.
- **per-tab chunk pool** instead of `global_pool`. Skipped for M9; the
  shared pool keeps RSS flat in the soak gate.

## Files of interest

- `crates/engine/src/index.rs` — index memory accounting.
- `crates/engine/src/search.rs` — search cache + global pool.
- `crates/engine/src/reader.rs` — chunk budget.
- `crates/engine-jni/src/lib.rs` — JNI matcher LRU, handle lifecycle.
- `crates/bench/src/bin/perf-gate.rs` — CI throughput gate.
- `crates/bench/src/bin/soak.rs` — RSS plateau gate.
- `shared/src/jvmMain/.../model/ChunkCache.kt` — UI chunk LRU.
