//! Parallel search over an indexed file.
//!
//! Pipeline: the line range is split into stripes (threads × 4). Each stripe is
//! read in bounded chunks (one contiguous `pread` per chunk, ≤ 4 MiB) and
//! matched in parallel; an ordered merge on the calling thread ORs per-stripe
//! Roaring bitsets into the result and hands sorted batches of matching line
//! numbers to the caller with throttled progress. Cancellation is cooperative
//! via an `AtomicFlag` checked per chunk.
//!
//! Fast paths: case-sensitive single-literal uses `memchr` memmem over whole
//! chunks (no per-line allocations); multi-literal uses aho-corasick; regex and
//! non-ASCII ignore-case use `regex` with its literal prefilter. For non-UTF-8
//! files the chunk is decoded to UTF-8 during the scan (never whole-file
//! conversion).

use std::collections::{BTreeMap, HashMap};
use std::io;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use aho_corasick::AhoCorasickBuilder;
use memchr::memchr_iter;
use rayon::ThreadPool;
use roaring::RoaringTreemap;
use thiserror::Error;

use crate::index::FileIndex;
use crate::reader::LazyReader;
use crate::util::AtomicFlag;

/// Cap on cached search-result cardinality.
pub const DEFAULT_CACHE_CAP_LINES: u64 = 1_000_000;

/// Default chunk byte budget for search reads.
const SEARCH_CHUNK_BUDGET: usize = 4 << 20;
/// Max lines per search chunk.
const SEARCH_CHUNK_LINES: u64 = 200_000;

#[derive(Error, Debug)]
pub enum SearchError {
    #[error("io error: {0}")]
    Io(#[from] io::Error),
    #[error("invalid pattern: {0}")]
    InvalidPattern(String),
    #[error("operation cancelled")]
    Cancelled,
}

/// Pattern options for a search.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct SearchOptions {
    /// One or more patterns; OR semantics across patterns.
    pub patterns: Vec<String>,
    /// Case-insensitive matching.
    pub ignore_case: bool,
    /// Treat patterns as regular expressions; when false, patterns are literal.
    pub use_regex: bool,
    /// Restrict search to lines `[start_line, end_line)`.
    pub start_line: Option<u64>,
    pub end_line: Option<u64>,
    /// Stop after this many matches (results are partial).
    pub max_results: Option<u64>,
}

impl SearchOptions {
    pub fn new(pattern: impl Into<String>) -> Self {
        SearchOptions {
            patterns: vec![pattern.into()],
            ignore_case: false,
            use_regex: true,
            start_line: None,
            end_line: None,
            max_results: None,
        }
    }
}

/// Progress/status of a search, passed alongside each match batch.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SearchStatus {
    /// Matching lines found so far.
    pub matches_found: u64,
    /// Lines processed so far.
    pub processed_lines: u64,
    /// Total lines in the search range.
    pub total_lines: u64,
    /// True on the final batch (search finished or was cancelled).
    pub done: bool,
}

/// Result of a (possibly partial) search.
#[derive(Debug, Clone)]
pub struct SearchResults {
    /// Matching line numbers (sorted, 0-based, global to the file).
    pub matches: RoaringTreemap,
    pub processed_lines: u64,
    pub total_lines: u64,
    pub cancelled: bool,
    pub elapsed: Duration,
}

impl SearchResults {
    /// Remove a line from the match set (used for the one-line overlap when
    /// resuming an incremental search).
    pub fn discard_line(&mut self, line: u64) {
        self.matches.remove(line);
    }
}

/// A matcher over whole chunks, shared across stripes.
enum Matcher {
    /// Case-sensitive single literal: memchr memmem over the whole chunk.
    Literal(Vec<u8>),
    /// Case-sensitive multi-literal or ASCII ignore-case literals: aho-corasick.
    LiteralAc(aho_corasick::AhoCorasick),
    /// Regex (regex mode, or non-ASCII ignore-case literals via escaping).
    Regex(regex::bytes::Regex),
}

impl Matcher {
    fn build(opts: &SearchOptions) -> Result<Matcher, SearchError> {
        let pats: Vec<&str> = opts.patterns.iter().map(String::as_str).collect();
        if !opts.use_regex {
            if pats.len() == 1 && !opts.ignore_case {
                return Ok(Matcher::Literal(pats[0].as_bytes().to_vec()));
            }
            let ascii_only = pats.iter().all(|p| p.is_ascii());
            if ascii_only {
                let ac = AhoCorasickBuilder::new()
                    .ascii_case_insensitive(opts.ignore_case)
                    .build(&pats)
                    .map_err(|e| SearchError::InvalidPattern(e.to_string()))?;
                return Ok(Matcher::LiteralAc(ac));
            }
            // Non-ASCII literal with ignore-case: escaped regex alternation.
            let joined = pats
                .iter()
                .map(|p| regex::escape(p))
                .collect::<Vec<_>>()
                .join("|");
            return regex_builder(&joined, true).map(Matcher::Regex);
        }
        // Regex mode: OR of patterns.
        let joined = pats
            .iter()
            .map(|p| format!("(?:{p})"))
            .collect::<Vec<_>>()
            .join("|");
        regex_builder(&joined, opts.ignore_case).map(Matcher::Regex)
    }

    /// Append global line numbers of matching lines in `bytes` to `out`,
    /// sorted ascending and deduplicated. `base_line` is the global line of
    /// `line_starts[0]`. Returns the number of lines appended.
    fn scan_chunk(&self, bytes: &[u8], line_starts: &[u32], base_line: u64, out: &mut Vec<u64>) {
        let mut last = u64::MAX;
        let it: Box<dyn Iterator<Item = usize> + '_> = match self {
            Matcher::Literal(needle) => {
                Box::new(memchr::memmem::find_iter(bytes, needle.as_slice()))
            }
            Matcher::LiteralAc(ac) => Box::new(ac.find_iter(bytes).map(|m| m.start())),
            Matcher::Regex(rx) => Box::new(rx.find_iter(bytes).map(|m| m.start())),
        };
        for pos in it {
            let line = line_of(pos, line_starts) as u64 + base_line;
            if line != last {
                out.push(line);
                last = line;
            }
        }
    }

    /// Match byte positions within a single line (used by `--json` and M7
    /// highlighters).
    fn positions_in_line(&self, line: &[u8]) -> Vec<(u32, u32)> {
        let mut out = Vec::new();
        match self {
            Matcher::Literal(needle) => {
                let w = needle.len();
                for pos in memchr::memmem::find_iter(line, needle.as_slice()) {
                    out.push((pos as u32, (pos + w) as u32));
                }
            }
            Matcher::LiteralAc(ac) => {
                for m in ac.find_iter(line) {
                    out.push((m.start() as u32, m.end() as u32));
                }
            }
            Matcher::Regex(rx) => {
                for m in rx.find_iter(line) {
                    out.push((m.start() as u32, m.end() as u32));
                }
            }
        }
        out
    }
}

fn regex_builder(pattern: &str, ignore_case: bool) -> Result<regex::bytes::Regex, SearchError> {
    regex::bytes::RegexBuilder::new(pattern)
        .case_insensitive(ignore_case)
        .unicode(true)
        .build()
        .map_err(|e| SearchError::InvalidPattern(e.to_string()))
}

/// Global line number of a byte position within a prepared chunk.
#[inline]
fn line_of(pos: usize, line_starts: &[u32]) -> usize {
    line_starts.partition_point(|&s| s as usize <= pos) - 1
}

/// A chunk of lines ready for matching: UTF-8 bytes (zero-copy for UTF-8
/// files) plus per-line start offsets into those bytes.
struct PreparedChunk<'a> {
    bytes: ChunkBytes<'a>,
    line_starts: Vec<u32>,
}

enum ChunkBytes<'a> {
    Borrowed(&'a [u8]),
    Owned(String),
}

impl<'a> ChunkBytes<'a> {
    #[inline]
    fn as_slice(&self) -> &[u8] {
        match self {
            ChunkBytes::Borrowed(b) => b,
            ChunkBytes::Owned(s) => s.as_bytes(),
        }
    }
}

impl<'a> PreparedChunk<'a> {
    fn prepare(raw: &'a [u8], encoding: crate::TextEncoding) -> PreparedChunk<'a> {
        if encoding.is_utf8() {
            let line_starts = lf_starts(raw);
            PreparedChunk {
                bytes: ChunkBytes::Borrowed(raw),
                line_starts,
            }
        } else {
            let mut s = String::with_capacity(raw.len() + raw.len() / 2);
            encoding.decode_to_string(raw, &mut s);
            let line_starts = lf_starts(s.as_bytes());
            PreparedChunk {
                bytes: ChunkBytes::Owned(s),
                line_starts,
            }
        }
    }
}

/// Offsets of each line start within `bytes` (first entry 0, last entry
/// `bytes.len()`); every line except possibly the last ends with `\n`.
fn lf_starts(bytes: &[u8]) -> Vec<u32> {
    let mut starts = Vec::with_capacity(1024);
    starts.push(0);
    for lf in memchr_iter(b'\n', bytes) {
        starts.push((lf + 1) as u32);
    }
    if *starts.last().unwrap() != bytes.len() as u32 {
        starts.push(bytes.len() as u32);
    }
    starts
}

#[derive(Hash, PartialEq, Eq, Clone, Debug)]
struct CacheKey {
    patterns: Vec<String>,
    ignore_case: bool,
    use_regex: bool,
    start: Option<u64>,
    end: Option<u64>,
}

/// Cached search results, keyed by (patterns, options, range).
#[derive(Clone)]
struct CachedSearch {
    matches: RoaringTreemap,
    processed_lines: u64,
    total_lines: u64,
}

/// Process-wide search pool shared by all engines created with the default
/// configuration. Keeping the same threads alive avoids per-open pool churn
/// (malloc zones, thread stacks) so RSS stays flat across open/search/close
/// cycles (soak gate).
static GLOBAL_POOL: std::sync::OnceLock<Arc<ThreadPool>> = std::sync::OnceLock::new();

fn global_pool() -> &'static Arc<ThreadPool> {
    GLOBAL_POOL.get_or_init(|| {
        let n = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4);
        Arc::new(
            rayon::ThreadPoolBuilder::new()
                .num_threads(n)
                .thread_name(|i| format!("loggi-search-{i}"))
                .build()
                .expect("failed to build global search pool"),
        )
    })
}

/// Results cache with a cap on total cached cardinality; evicts the largest
/// entry when over budget.
struct SearchCache {
    map: HashMap<CacheKey, CachedSearch>,
    cap_lines: u64,
    total: u64,
}

impl SearchCache {
    fn new(cap_lines: u64) -> Self {
        SearchCache {
            map: HashMap::new(),
            cap_lines,
            total: 0,
        }
    }

    fn get(&self, key: &CacheKey) -> Option<CachedSearch> {
        self.map.get(key).cloned()
    }

    fn insert(&mut self, key: CacheKey, cached: CachedSearch) {
        let card = cached.matches.len();
        if card > self.cap_lines {
            return;
        }
        if let Some(prev) = self.map.get(&key) {
            self.total -= prev.matches.len();
        }
        self.total += card;
        self.map.insert(key, cached);
        while self.total > self.cap_lines && !self.map.is_empty() {
            let (victim_key, victim) = self
                .map
                .iter()
                .max_by_key(|(_, c)| c.matches.len())
                .map(|(k, v)| (k.clone(), v.matches.len()))
                .unwrap();
            self.map.remove(&victim_key);
            self.total -= victim;
        }
    }
}

/// A parallel search engine over one indexed file snapshot.
pub struct SearchEngine {
    index: Arc<FileIndex>,
    reader: LazyReader,
    pool: Arc<ThreadPool>,
    cache: std::sync::Mutex<SearchCache>,
}

impl SearchEngine {
    /// Create an engine with a default rayon pool sized to the machine.
    pub fn new(index: Arc<FileIndex>) -> Self {
        Self::with_config(index, 0, DEFAULT_CACHE_CAP_LINES)
    }

    /// `threads == 0` means "use available parallelism" via a process-wide
    /// shared pool (keeps RSS flat across open/search/close cycles); an
    /// explicit thread count builds a private pool (tests, tuning).
    pub fn with_config(index: Arc<FileIndex>, threads: usize, cache_cap_lines: u64) -> Self {
        let pool: Arc<ThreadPool> = if threads == 0 {
            global_pool().clone()
        } else {
            Arc::new(
                rayon::ThreadPoolBuilder::new()
                    .num_threads(threads)
                    .thread_name(|i| format!("loggi-search-{i}"))
                    .build()
                    .expect("failed to build search pool"),
            )
        };
        SearchEngine {
            index: index.clone(),
            reader: LazyReader::with_budget(index, SEARCH_CHUNK_BUDGET),
            pool,
            cache: std::sync::Mutex::new(SearchCache::new(cache_cap_lines)),
        }
    }

    pub fn index(&self) -> &Arc<FileIndex> {
        &self.index
    }

    pub fn threads(&self) -> usize {
        self.pool.current_num_threads()
    }

    /// Drop all cached results (call after the index was replaced).
    pub fn invalidate_cache(&self) {
        let mut c = self.cache.lock().unwrap();
        c.map.clear();
        c.total = 0;
    }

    fn range(&self, opts: &SearchOptions) -> (u64, u64) {
        let n = self.index.line_count();
        let start = opts.start_line.unwrap_or(0).min(n);
        let end = opts.end_line.unwrap_or(n).min(n);
        (start, end.max(start))
    }

    fn cache_key(opts: &SearchOptions) -> CacheKey {
        CacheKey {
            patterns: opts.patterns.clone(),
            ignore_case: opts.ignore_case,
            use_regex: opts.use_regex,
            start: opts.start_line,
            end: opts.end_line,
        }
    }

    /// Run a search, emitting sorted batches of matching line numbers with
    /// throttled progress. Return `false` from the emitter to cancel.
    pub fn search_with(
        &self,
        opts: &SearchOptions,
        mut emit: impl FnMut(SearchStatus, &[u64]) -> bool + Send,
    ) -> Result<SearchResults, SearchError> {
        let key = Self::cache_key(opts);
        if opts.max_results.is_none()
            && let Some(hit) = self.cache.lock().unwrap().get(&key)
        {
            let mut lines: Vec<u64> = Vec::with_capacity(hit.matches.len() as usize);
            for l in hit.matches.iter() {
                lines.push(l);
            }
            emit(
                SearchStatus {
                    matches_found: hit.matches.len(),
                    processed_lines: hit.processed_lines,
                    total_lines: hit.total_lines,
                    done: true,
                },
                &lines,
            );
            return Ok(SearchResults {
                matches: hit.matches,
                processed_lines: hit.processed_lines,
                total_lines: hit.total_lines,
                cancelled: false,
                elapsed: Duration::ZERO,
            });
        }

        let t = Instant::now();
        let (start, end) = self.range(opts);
        let total = end - start;
        let mut merged = RoaringTreemap::new();
        if total == 0 {
            emit(
                SearchStatus {
                    matches_found: 0,
                    processed_lines: 0,
                    total_lines: 0,
                    done: true,
                },
                &[],
            );
            return Ok(SearchResults {
                matches: merged,
                processed_lines: 0,
                total_lines: 0,
                cancelled: false,
                elapsed: t.elapsed(),
            });
        }

        let matcher = Arc::new(Matcher::build(opts)?);
        let cancel = AtomicFlag::new();
        // Separate flag for the `max_results` early stop: tasks stop scanning,
        // but the merger still drains what was already computed (the result set
        // is then trimmed to the exact limit, since a chunk may overshoot).
        let stop = AtomicFlag::new();
        let stripes = self.stripe_count(total);
        let (tx, rx) = crossbeam_channel::bounded::<(u64, RoaringTreemap)>(stripes);
        let mut cancelled = false;
        let mut processed = 0u64;
        // Global match counter shared by stripes.
        let found = Arc::new(std::sync::atomic::AtomicU64::new(0));
        let max = opts.max_results;

        {
            let index = self.index.clone();
            let reader = self.reader.clone();
            let matcher = matcher.clone();
            let cancel_flag = cancel.clone();
            self.pool.scope(|scope| {
                for s in 0..stripes {
                    let tx = tx.clone();
                    let (s_start, s_end) = split_range(start, end, stripes, s);
                    let index = index.clone();
                    let reader = reader.clone();
                    let matcher = matcher.clone();
                    let cancel = cancel_flag.clone();
                    let stop = stop.clone();
                    let found = found.clone();
                    scope.spawn(move |_| {
                        let mut local = RoaringTreemap::new();
                        let mut buf = Vec::new();
                        let mut matches_in_stripe = Vec::new();
                        let mut pos = s_start;
                        while pos < s_end {
                            if cancel.is_set() || stop.is_set() {
                                break;
                            }
                            let want = (s_end - pos).min(SEARCH_CHUNK_LINES);
                            let read = match reader.read_lines(pos, want, &mut buf) {
                                Ok(r) => r,
                                Err(_) => break,
                            };
                            if read.end_line <= pos {
                                break;
                            }
                            let raw = &buf[..read.byte_len as usize];
                            if !raw.is_empty() {
                                let prepared = PreparedChunk::prepare(raw, index.encoding());
                                matches_in_stripe.clear();
                                matcher.scan_chunk(
                                    prepared.bytes.as_slice(),
                                    &prepared.line_starts,
                                    read.start_line,
                                    &mut matches_in_stripe,
                                );
                                for &l in &matches_in_stripe {
                                    local.insert(l);
                                }
                                if let Some(m) = max {
                                    let now = found.fetch_add(
                                        matches_in_stripe.len() as u64,
                                        Ordering::Relaxed,
                                    ) + matches_in_stripe.len() as u64;
                                    if now >= m {
                                        stop.set();
                                        break;
                                    }
                                }
                            }
                            pos = read.end_line;
                        }
                        let _ = tx.send((s as u64, local)); // ignore send errors on cancel
                    });
                }
                drop(tx);
                // Ordered merge on the calling thread: OR each stripe into the
                // result and hand its sorted matches to the caller (streaming,
                // naturally throttled to stripe granularity).
                let mut next = 0u64;
                let mut buffered: BTreeMap<u64, RoaringTreemap> = BTreeMap::new();
                while next < stripes as u64 {
                    if cancel.is_set() {
                        cancelled = true;
                        break;
                    }
                    if let Some(b) = buffered.remove(&next) {
                        let (_, s_end) = split_range(start, end, stripes, next as usize);
                        processed = s_end - start;
                        let card = b.len() as usize;
                        let mut lines = Vec::with_capacity(card);
                        for l in b.iter() {
                            lines.push(l);
                        }
                        merged |= b;
                        let keep = emit(
                            SearchStatus {
                                matches_found: merged.len(),
                                processed_lines: processed.min(total),
                                total_lines: total,
                                done: false,
                            },
                            &lines,
                        );
                        if !keep {
                            cancel.set();
                            cancelled = true;
                            break;
                        }
                        next += 1;
                    } else {
                        match rx.recv_timeout(Duration::from_millis(100)) {
                            Ok((s, b)) => {
                                buffered.insert(s, b);
                            }
                            Err(crossbeam_channel::RecvTimeoutError::Timeout) => {}
                            Err(crossbeam_channel::RecvTimeoutError::Disconnected) => {
                                // All tasks done; drain the buffer in order.
                                let drained: Vec<(u64, RoaringTreemap)> =
                                    buffered.into_iter().collect();
                                for (s, b) in drained {
                                    let (_, s_end) = split_range(start, end, stripes, s as usize);
                                    processed = s_end - start;
                                    let card = b.len() as usize;
                                    let mut lines = Vec::with_capacity(card);
                                    for l in b.iter() {
                                        lines.push(l);
                                    }
                                    merged |= b;
                                    let keep = emit(
                                        SearchStatus {
                                            matches_found: merged.len(),
                                            processed_lines: processed.min(total),
                                            total_lines: total,
                                            done: false,
                                        },
                                        &lines,
                                    );
                                    if !keep {
                                        cancel.set();
                                        cancelled = true;
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            });
        }

        if stop.is_set() {
            cancelled = true; // partial results (max_results hit)
        }
        if let Some(m) = opts.max_results {
            merged.remove_range(m..);
        }
        let done_status = SearchStatus {
            matches_found: merged.len(),
            processed_lines: processed.min(total),
            total_lines: total,
            done: true,
        };
        emit(done_status, &[]);

        if !cancelled && opts.max_results.is_none() {
            self.cache.lock().unwrap().insert(
                key,
                CachedSearch {
                    matches: merged.clone(),
                    processed_lines: total,
                    total_lines: total,
                },
            );
        }

        Ok(SearchResults {
            matches: merged,
            processed_lines: processed,
            total_lines: total,
            cancelled,
            elapsed: t.elapsed(),
        })
    }

    fn stripe_count(&self, total: u64) -> usize {
        let threads = self.threads().max(1);
        let by_threads = threads * 4;
        // Each stripe should have at least ~8k lines to amortize setup.
        let min_stripes = (total / 8192).max(1);
        (by_threads as u64).min(min_stripes).max(1) as usize
    }

    /// Number of matching lines in a range.
    pub fn search_count(&self, opts: &SearchOptions) -> Result<SearchCount, SearchError> {
        let results = self.search_with(opts, |_status, _lines| true)?;
        Ok(SearchCount {
            matches: results.matches.len(),
            processed_lines: results.processed_lines,
        })
    }

    /// Match byte positions within one line (for `--json` output and M7
    /// highlighters). Line bytes must be UTF-8 (callers decode non-UTF-8).
    pub fn match_positions(
        &self,
        opts: &SearchOptions,
        line_bytes: &[u8],
    ) -> Result<Vec<(u32, u32)>, SearchError> {
        let matcher = Matcher::build(opts)?;
        Ok(matcher.positions_in_line(line_bytes))
    }
}

#[derive(Debug, Clone, Copy)]
pub struct SearchCount {
    pub matches: u64,
    pub processed_lines: u64,
}

/// A reusable per-pattern matcher for UI highlighters (M7).
///
/// Compilation is expensive, so callers cache the matcher and reuse it for
/// every visible line. Matching happens on UTF-8 line bytes; callers decode
/// non-UTF-8 lines before matching.
pub struct HighlightMatcher {
    matcher: Matcher,
}

impl HighlightMatcher {
    /// Build a matcher for the given options (only `patterns`, `ignore_case`
    /// and `use_regex` are consulted).
    pub fn new(opts: &SearchOptions) -> Result<Self, SearchError> {
        Ok(HighlightMatcher {
            matcher: Matcher::build(opts)?,
        })
    }

    /// Byte ranges (start, end) of every match within one line.
    pub fn positions_in_line(&self, line_bytes: &[u8]) -> Vec<(u32, u32)> {
        self.matcher.positions_in_line(line_bytes)
    }

    /// Cheap boolean: does this line match at all?
    pub fn is_match(&self, line_bytes: &[u8]) -> bool {
        let mut it: Box<dyn Iterator<Item = usize> + '_> = match &self.matcher {
            Matcher::Literal(needle) => {
                Box::new(memchr::memmem::find_iter(line_bytes, needle.as_slice()))
            }
            Matcher::LiteralAc(ac) => Box::new(ac.find_iter(line_bytes).map(|m| m.start())),
            Matcher::Regex(rx) => Box::new(rx.find_iter(line_bytes).map(|m| m.start())),
        };
        it.next().is_some()
    }
}

/// Split [start, end) into `stripes` ranges; returns the s-th.
fn split_range(start: u64, end: u64, stripes: usize, s: usize) -> (u64, u64) {
    let len = end - start;
    let per = len / stripes as u64;
    let rem = len % stripes as u64;
    let a = start + s as u64 * per + (s as u64).min(rem);
    let b = if s as u64 + 1 < rem {
        a + per + 1
    } else {
        a + per
    };
    (a, b.min(end))
}

/// rank(line) = number of matches strictly before `line`; select(k) = k-th
/// match (0-based). Both are O(1)-ish via the roaring treemap.
pub fn rank(bitset: &RoaringTreemap, line: u64) -> u64 {
    // roaring's rank is inclusive (counts `line` itself when present).
    bitset.rank(line) - bitset.contains(line) as u64
}

pub fn select(bitset: &RoaringTreemap, k: u64) -> Option<u64> {
    bitset.select(k)
}

/// Merge two match sets (pins ∪ matches).
pub fn merge(a: &mut RoaringTreemap, b: &RoaringTreemap) {
    *a |= b;
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::index::{IndexOptions, index_file};
    use std::io::Write;

    fn engine_for(bytes: &[u8]) -> SearchEngine {
        let dir = Box::leak(Box::new(tempfile::tempdir().unwrap()));
        let p = dir.path().join("f.log");
        let mut f = std::fs::File::create(&p).unwrap();
        f.write_all(bytes).unwrap();
        SearchEngine::with_config(
            Arc::new(index_file(&p, &IndexOptions::default()).unwrap()),
            2,
            1000,
        )
    }

    fn matches_of(engine: &SearchEngine, opts: &SearchOptions) -> Vec<u64> {
        let mut out = Vec::new();
        let _ = engine.search_with(opts, |_s, lines| {
            out.extend_from_slice(lines);
            true
        });
        out
    }

    fn corpus() -> Vec<u8> {
        let mut v = Vec::new();
        for i in 0..10_000u64 {
            v.extend_from_slice(format!("line {i:05} payload error\n").as_bytes());
        }
        v
    }

    #[test]
    fn literal_search() {
        let e = engine_for(&corpus());
        let mut opts = SearchOptions::new("error");
        opts.use_regex = false;
        let m = matches_of(&e, &opts);
        assert_eq!(m.len(), 10_000);
        assert_eq!(m[0], 0);
        assert_eq!(*m.last().unwrap(), 9_999);

        let mut opts = SearchOptions::new("line 0004");
        opts.use_regex = false;
        let m = matches_of(&e, &opts);
        assert_eq!(m.len(), 10);
    }

    #[test]
    fn regex_and_ignore_case() {
        let e = engine_for(&corpus());
        let mut opts = SearchOptions::new("err|WARN");
        opts.use_regex = true;
        let m = matches_of(&e, &opts);
        assert_eq!(m.len(), 10_000);

        let mut opts = SearchOptions::new("PAYLOAD");
        opts.ignore_case = true;
        opts.use_regex = false;
        let m = matches_of(&e, &opts);
        assert_eq!(m.len(), 10_000);

        let mut opts = SearchOptions::new("zzz");
        opts.use_regex = false;
        let m = matches_of(&e, &opts);
        assert!(m.is_empty());
    }

    #[test]
    fn multi_pattern_or() {
        let e = engine_for(b"apple\nbanana\ncherry\napple\n");
        let mut opts = SearchOptions::new("apple");
        opts.patterns.push("cherry".to_string());
        opts.use_regex = false;
        let m = matches_of(&e, &opts);
        assert_eq!(m, vec![0, 2, 3]);
    }

    #[test]
    fn range_search() {
        let e = engine_for(&corpus());
        let mut opts = SearchOptions::new("error");
        opts.use_regex = false;
        opts.start_line = Some(5_000);
        opts.end_line = Some(6_000);
        let m = matches_of(&e, &opts);
        assert_eq!(m.len(), 1_000);
        assert_eq!(m[0], 5_000);
        assert_eq!(*m.last().unwrap(), 5_999);
    }

    #[test]
    fn count() {
        let e = engine_for(&corpus());
        let mut opts = SearchOptions::new("error");
        opts.use_regex = false;
        let c = e.search_count(&opts).unwrap();
        assert_eq!(c.matches, 10_000);
    }

    #[test]
    fn cache_hit() {
        let e = engine_for(&corpus());
        // Dense results above the cap are not cached.
        let mut dense = SearchOptions::new("error");
        dense.use_regex = false;
        let m1 = matches_of(&e, &dense);
        let m2 = matches_of(&e, &dense);
        assert_eq!(m1, m2);
        assert_eq!(e.cache.lock().unwrap().total, 0);
        // Sparse results are cached.
        let mut sparse = SearchOptions::new("line 0004");
        sparse.use_regex = false;
        let s1 = matches_of(&e, &sparse);
        let s2 = matches_of(&e, &sparse);
        assert_eq!(s1, s2);
        assert_eq!(s1.len(), 10);
        assert_eq!(e.cache.lock().unwrap().total, 10);
    }

    #[test]
    fn rank_select() {
        let e = engine_for(&corpus());
        let mut opts = SearchOptions::new("error");
        opts.use_regex = false;
        let results = e.search_with(&opts, |_s, _l| true).unwrap();
        assert_eq!(results.matches.len(), 10_000);
        assert_eq!(rank(&results.matches, 0), 0);
        assert_eq!(rank(&results.matches, 1000), 1000);
        assert_eq!(select(&results.matches, 0), Some(0));
        assert_eq!(select(&results.matches, 9999), Some(9_999));
        assert_eq!(select(&results.matches, 10_000), None);
    }

    #[test]
    fn cancel_mid_search() {
        let e = engine_for(&corpus());
        let mut opts = SearchOptions::new("error");
        opts.use_regex = false;
        let res = e
            .search_with(&opts, |status, _lines| {
                status.processed_lines == 0 && status.done
            })
            .unwrap();
        assert!(res.cancelled);
    }

    #[test]
    fn max_results() {
        let e = engine_for(&corpus());
        let mut opts = SearchOptions::new("error");
        opts.use_regex = false;
        opts.max_results = Some(50);
        let res = e.search_with(&opts, |_s, _l| true).unwrap();
        assert_eq!(res.matches.len(), 50);
    }

    #[test]
    fn dense_results_rank_select_throughput() {
        let mut bytes = Vec::new();
        for i in 0..200_000u64 {
            bytes.extend_from_slice(format!("{i} xxxxxxxxxxxxxxxxxxxxxxxx\n").as_bytes());
        }
        let e = engine_for(&bytes);
        let mut opts = SearchOptions::new("xxx");
        opts.use_regex = false;
        let results = e.search_with(&opts, |_s, _l| true).unwrap();
        assert_eq!(results.matches.len(), 200_000);
        let t = Instant::now();
        let mut acc = 0u64;
        for k in 0..200_000 {
            acc ^= select(&results.matches, k).unwrap();
        }
        assert!(
            t.elapsed() < Duration::from_secs(5),
            "select slow: {:?}",
            t.elapsed()
        );
        assert_eq!(rank(&results.matches, 199_999), 199_999);
        assert_eq!(acc, 0); // xor of 0..199999
    }

    #[test]
    fn utf16_search() {
        let utf16: Vec<u8> = b"alpha error\nbeta\ngamma error\n"
            .iter()
            .flat_map(|&b| [b, 0])
            .collect();
        let e = engine_for(&utf16);
        let mut opts = SearchOptions::new("error");
        opts.use_regex = false;
        let m = matches_of(&e, &opts);
        assert_eq!(m, vec![0, 2]);
    }

    #[test]
    fn match_positions() {
        let e = engine_for(b"ab ab\n");
        let mut opts = SearchOptions::new("ab");
        opts.use_regex = false;
        let pos = e.match_positions(&opts, b"ab ab").unwrap();
        assert_eq!(pos, vec![(0, 2), (3, 5)]);
    }
}
