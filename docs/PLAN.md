# loggi — Milestone Plan

**loggi** is a desktop log viewer for very large log files: a Rust engine for indexing, lazy
reading and fast search, wrapped by a Kotlin Multiplatform (Compose Multiplatform) desktop UI,
distributed as native installers for **Windows, macOS and Linux**. The engine is designed for
reuse in a CLI tool and an MCP server.

This plan uses proven ideas from established desktop log viewers (line-offset indexing,
Roaring bitsets, parallel search, lazy disk reads) and deliberately adds what they lack
(left/right layout, detached search window, vertical tabs, line spacing, clipboard copy from
the log view, EN/zh-Hans i18n, theme modes, Rust CLI + MCP).

---

## 1. Product requirements (from the user)

1. **Rust engine first**: open very large log files; instantly report file info (size, line
   count, encoding, max line length); search file contents quickly.
2. **Reusability**: the same engine powers CLI and MCP.
3. **Search quality**: ripgrep-class search speed (SIMD literals, parallel regex, streaming).
4. **Benchmark suite** to prove engine capability.
5. **KMP UI** (per requirements):
   - open large files;
   - two panes: main log view + search results; case-sensitive toggle; regex toggle;
     layouts: left-right, top-bottom, search in an independent window;
   - select lines or text → right-click context menu → **copy** (selected text / whole lines /
     multi-line content to the system clipboard) or **highlight** with configurable color;
     identical text is highlighted everywhere;
   - pin lines into the search window; pinned lines survive search-term changes; results stay
     sorted by line number;
   - multiple files via tabs; horizontal **or vertical** tab placement;
   - font family, font size, line spacing adjustments;
   - user-configurable highlight colors;
   - theme: light/dark modes — **follow the system, force light, or force dark**;
   - i18n: UI strings in English and Simplified Chinese; locale follows the system or a user
     setting.
6. **Cross-platform** Windows / macOS / Linux, delivered as native installers.
7. **Desktop-grade UX** (hard boundary conditions, added 2026-08-08):
   - **Compact density**: stock Material 3 padding (text fields, buttons, chips, dropdown /
     context menus, dialogs) is too spacious for desktop — all controls use the app's compact
     spacing system, never M3 defaults.
   - **Tabs always available** (tab bar visible even with a single file), **multi-instance /
     multi-window supported**, and a **toolbar** with one-click access to common tools.
   - **Log-view behaviors** match desktop log-viewer expectations: horizontal scrolling for
     long lines when wrap is off, left-click clears the selection, current-line highlight on
     jump, whole-line highlight mode.
   - **Platform conventions**: app icons follow per-OS artwork guides (macOS: artwork
     ≈ 824/1024 of the canvas — edge-to-edge artwork renders oversized in the Dock);
     settings live in their own window; installed system font families are selectable.

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  desktopApp — Compose Multiplatform app entry (Kotlin/JVM): │
│  main window, native installer packaging (icons, jpackage)  │
│           │ depends on                                       │
│  shared — KMP module (JVM target): all UI, ViewModels,      │
│  settings/i18n/theme, JNI host                              │
│           │ JNI (thin bridge, no logic)                     │
│           ▼                                                  │
│  crates/engine-jni — cdylib, marshals to/from Kotlin        │
│           ▼                                                  │
├───────────┼─────────────────────────────────────────────────┤
│  crates/engine (loggi-engine) — Rust core, UI-agnostic      │
│  • FileIndex (line offsets, optional delta compression)     │
│  • lazy line reader (seek+read, UTF-8 fast path, encodings) │
│  • SearchEngine (parallel, regex/literal, Roaring bitsets)  │
│  • FileWatcher (notify) + incremental index/search          │
│  • progress + cancellation (AtomicFlag)                     │
├───────────┼─────────────────────────────────────────────────┤
│  crates/cli — loggi <info|search> …  (rg-like flags)        │
│  crates/mcp — MCP server (stdio, JSON-RPC) over the engine  │
│  crates/bench — criterion benchmarks + soak tests + data    │
│                generator                                    │
└─────────────────────────────────────────────────────────────┘
```

**Decisions**

- **UI framework**: Compose Multiplatform (the KMP UI for desktop, JVM target) — one codebase
  for all three OSes. Kotlin/Native is not used; Rust interop is via **JNI** (`jni` crate +
  `crates/engine-jni` cdylib), which gives zero-copy control (`DirectByteBuffer` for line chunks)
  and simple callbacks for progress. (Alternative considered: UniFFI — less boilerplate, weaker
  control of hot buffers; revisit only if JNI marshalling becomes a bottleneck. Re-evaluated
  after M5–M8, decision stands: the two hottest calls — `readLines` filling a caller-owned
  direct `ByteBuffer` and `searchPoll` draining into a caller-owned `LongArray` — cannot be
  expressed in UniFFI's safe marshalling model without an extra copy + allocation per chunk,
  and the poll-not-callback threading model avoids JVM attachment on native threads entirely.
  The bridge stays ~750 lines of thin, fully smoke-tested marshalling.)
- **Engine design**:
  - line index = `Vec<u64>` of end-of-line byte offsets; optional delta/varint block compression
    (128-line blocks, constant-time random access);
  - search results / marks / pins = `roaring::Roaring64Map` bitsets; rank/select for
    index↔line mapping; pin ∪ match merge is a bitset OR;
  - search = streaming chunks + rayon-parallel matching + serial merge, throttled progress;
  - display = one contiguous `pread` per visible chunk via index-positioned reads;
  - follow = `notify` watcher + XXH64 header/tail hashes + partial reindex + incremental search;
  - cancellation via `AtomicFlag`, everywhere.
- **Search engine**: `regex` crate (regex-automata) with literal prefilter (memchr,
  aho-corasick multi-pattern) for ripgrep-class throughput; case-insensitive literal fast path.
- **JNI rules**: no engine logic in the bridge; no per-line Java strings — return chunked raw
  bytes; all long operations run on `Dispatchers.Default` (never the UI thread); search
  cancellation via search-id + flag.
- **Packaging**: JDK `jpackage` (bundles JRE): `.dmg`/`.pkg` (macOS), `.exe` (Windows, NSIS),
  `.deb`/`.rpm` (Linux) + AppImage; GitHub Actions matrix builds the Rust cdylib + JVM jar per
  OS and assembles installers on tag. Signing/notarization in M10.

---

## 3. Milestones

### M0 — Scaffold + engine core (file info, index, lazy read)
- Cargo workspace: `crates/engine`, `crates/cli`, `crates/bench`; MIT license; `AGENTS.md`;
  GitHub Actions basic CI (build + test + clippy on 3 OSes).
- `FileIndex`: sequential scan (streaming 4–8 MiB blocks, memchr-based LF scan), builds
  end-of-line offset index; progress reporting; cooperative cancel; files without trailing LF.
- `FileInfo` snapshot: size, line count, max line length, encoding (BOM + UTF-8 validation in
  M0; full auto-detection via chardetng lands in M2), index memory usage.
- Lazy line reader: index-positioned `pread`, one contiguous read per chunk, UTF-8 fast path
  (zero-copy `&str` views), CR stripping, tab expansion for display mode; per-read size capped
  with segmented reads so multi-GB single lines never blow the chunk buffer.
- Acceptance: warm cache: a 1 GB file is indexed and info reported in < 3 s (~2 GB/min
  throughput; cold-cache numbers measured separately and recorded); open 50 GB without memory
  blowup (RSS tracked); unit + property tests on generated files.

### M1 — Search engine (ripgrep-class)
- `SearchEngine`: chunked streaming search; parallelism via rayon; `regex` crate with literal
  prefilter; search options: pattern, ignore-case, regex-vs-literal; results as
  `Roaring64Map`; rank/select APIs for the UI/CLI; `count` API. Validate rank/select
  throughput on dense results (e.g. a very common substring) — fall back to `roaring64` or a
  custom two-level bitset if the 64-bit map degrades.
- Streaming results: caller receives batches (matching line numbers) with throttled progress;
  interrupt + discard results cleanly.
- Search range support (start/end line) and results cache keyed `(pattern, options, range)`.
- Multi-pattern OR support (feeds boolean combining later).
- Acceptance: literal search on a 10 GB file ≥ ~1–2 GB/s effective throughput on warm cache
  (bench vs `rg` in M4); memory bounded by chunks, not file size; regex search completes with
  progress and cancel works mid-search.

### M2 — Follow-tail, encoding, robustness
- `FileWatcher` via `notify` (+ polling fallback for network drives), change detection with
  XXH64 header/tail hashes → `Unchanged / DataAdded / Truncated`.
- Partial reindex from last indexed offset; incremental search resume from `lastProcessed − 1`
  with overlap dedup.
- Concurrent access: display/search reads and partial reindex run concurrently under a shared
  lock / versioned index; readers never observe a half-updated index.
- Encoding: UTF-8/UTF-16 (LE/BE) with multi-byte LF verification; BOM handling; full
  auto-detection via chardetng; non-UTF-8 codepaths via `encoding_rs` — decode for display,
  and for search convert **per chunk during the scan** (never whole-file conversion); ANSI
  escape prefilter option at read time.
- Acceptance: append-heavy test (log growing at ~50 MB/s) stays tailed with bounded index/search
  cost O(new data); truncated file handled; UTF-16LE file opens and searches correctly.

### M3 — CLI tool + MCP server
- `loggi info <file>` (size, lines, encoding, max length, index stats, index time/memory).
- `loggi search <pattern> <file>` — rg-inspired flags: `-i`, `-F` (fixed), `-e` (regex default),
  `-n`/`-N`, `-c`, `-C/-A/-B` context, `--line-offset` (0-based), `--limit`, `-q` quiet,
  `--follow`, colored output, `--json` (NDJSON stream for tooling).
- `loggi tail --follow <file>` style mode via engine watcher.
- `crates/mcp`: stdio JSON-RPC MCP server over the engine — `file_info`, `search`
  (pattern / ignore-case / regex / limit, streamed result batches + progress), `cancel`,
  `read_lines`; request-scoped cancellation, safe under concurrent requests.
- Acceptance: `loggi search` matches rg output semantics on the same corpus; `--json` streams
  without buffering the file; engine API used by CLI contains zero UI coupling; MCP `search`
  on a 10 GB file streams batched results with progress and cancels cleanly mid-search.

### M4 — Benchmark suite + tuning
- `crates/bench` + `tools/gen-log`: synthetic generators (repeat patterns, random JSON lines,
  long lines, huge single-line files, UTF-16 files, no-trailing-LF; 1 GB / 10 GB / 100 GB).
  CI runs the 1–10 GB subset; the 100 GB corpus runs on demand / nightly on large runners
  (runner disk limits).
- Metrics: index time & peak RSS, index bytes per line, search throughput (cold/warm page cache,
  literal/case-insensitive/regex), follow-tail incremental latency, chunk-decode throughput,
  memory over a long session (soak: open→search→close cycles with RSS sampling).
- Criterion micro-benchmarks + scripted soak harness; CI runs a fast subset, nightly the full
  matrix. Compare search throughput vs `ripgrep` where semantics match (informational, not
  a requirement).
- M4 is also the first tuning pass (buffer sizes, block size, thread counts) driven by results.
- Acceptance: baseline numbers recorded in `docs/benchmarks.md`; perf regression gate in CI
  (e.g., literal search throughput ≥ threshold on the 10 GB corpus).

### M5 — KMP app shell + JNI bridge + virtualized main view ✅ implemented
- Compose Multiplatform project with two Gradle modules (project-level Gradle config lives in
  the repo root: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
  `gradle/wrapper`): `shared` (KMP, JVM target — all UI, ViewModels, settings/i18n/theme and
  the JNI host under `src/jvmMain`) and `desktopApp` (plain JVM module: `main.kt`, window,
  native installer packaging with the app icon). Gradle, kotlinx-coroutines, minimal ViewModel
  architecture; `crates/engine-jni` cdylib built per-OS (`cargoBuildJni` Gradle task) and
  packaged into the jar under `natives/<os>-<arch>/` (extract + `System.load` at runtime;
  dev mode also probes `target/<profile>/` at the repo root).
- JNI contract: `openFile(path) → FileHandle(id)`; `fileInfo(id)`; `readLines(id, start, count)`
  → direct ByteBuffer chunk; `searchStart/searchPoll/searchCancel`; progress via 100 ms
  polling only — no JNI callbacks from native threads (avoids GlobalRef lifecycle issues);
  handle lifecycle (release, drop, errors as checked exceptions).
- Main log view: virtualized `LazyColumn` over engine chunks, line numbers gutter, fast scroll
  to line, follow-at-bottom placeholder; open file dialog with progress dialog for indexing.
- Acceptance: open a 10 GB file, scroll top→bottom with no visible lag, RSS stable while
  scrolling; memory: only visible chunks decoded; JNI does not allocate per-line strings.

### M6 — Search window + pane layouts ✅ implemented
- Search bar (pattern input, ignore-case checkbox, regex toggle, search/stop, history) and
  results pane rendering match lines via bitset rank/select; incremental results while
  searching; results jump-to-line on click.
- Layouts: split top/bottom, left/right, and **detached search window** (results in a separate
  OS window, same model); splitter proportions persisted.
- Acceptance: all three layouts switchable live; results stream in while searching a 10 GB file;
  case/regex toggles re-run correctly (splitter-proportion persistence lands in M8 session
  restore).

### M7 — Highlighting + clipboard + pinned lines ✅ implemented
- Highlight engine in Rust core? No — highlighters are UI-state but matching is hot: implement
  highlighter matching in the engine (per-line, cached per chunk) with plain/regex patterns,
  ignore-case, fore/back colors, ordered priority (later wins).
- Right-click context menu on a selection (single line, inline text range, or multiple lines):
  - **Copy**: selected text, whole line content, or multi-line content → system clipboard
    (lines joined with `\n`; no line-number/prefix artifacts; CRLF/UTF-16 decoded to plain
    text). Paste works everywhere standard text fields work (e.g. search bar, settings).
  - **Highlight**: color picker (preset palette + custom color dialog); **same text
    highlighted everywhere** (the selected text becomes a plain-text highlighter with the
    chosen color, applied to both panes).
- **Pinned lines**: pin from context menu (or pin button on selected lines) → persistent marks
  bitset in the engine; pinned lines stay visible in the search window when the search term
  changes; display = `pins ∪ matches`, ordered by line number via bitset iteration/rank.
- Acceptance: highlight identical text everywhere across panes; pin lines, change search term
  several times, pinned lines persist and ordering is by line number; custom colors persisted;
  copy of a text range / whole lines / multi-line selection lands on the clipboard normalized
  to LF plain text; `--json`/CLI output is unaffected (UI-only feature).

### M8 — Tabs + display settings + themes + i18n + persistence + overview ⚠️ partially implemented
- File tabs: close/close-others/close-left/close-right/all, middle-click close, drag reorder,
  tab bar auto-hide when single file, **horizontal or vertical tab placement** (setting), tab
  context menu (copy path, open folder, rename).
- Display settings: font family (fixed-pitch list), font size (live Ctrl+/−), **line spacing /
  line height**, text wrap toggle (long lines wrap or truncate with ellipsis), theme colors,
  search-result highlight color; persisted via portable settings file (`loggi.conf` next to
  the binary, or app-config dir).
- **Themes (light/dark modes)**: setting = `follow system` / `force light` / `force dark`;
  in follow mode a system-theme listener switches live when the OS theme changes; per-mode
  color palettes (background, text, gutter, selection, accent, highlight defaults); persisted.
- **i18n (EN + zh-Hans)**: all UI strings in resource files; locale = system default by
  default, overridable in settings; switch applies live; persisted.
- Session restore: open files + tab order + view context (splitter sizes, search options,
  marks, per-tab top line).
- Nice-to-have: overview/minimap strip (match density + click-to-jump).
- Acceptance: settings apply live; all persisted across restarts; vertical tabs work on all 3
  OSes; session restore opens the same file set; theme mode (follow system / force light /
  force dark) switches live and is persisted; UI strings render in EN and zh-Hans per the
  locale setting.
- **Status (2026-08-08 audit + user report)**: the features above exist in code, but M8 is
  **not fully done** — gaps and UX defects are collected in M8.5, which gates M9.

### M8.5 — UX gap closure + desktop polish ✅ implemented (manual pass gates M9)

Found by the 2026-08-08 audit of `shared/` + user report on the running app. Implemented
the same day; a manual pass in the running app (3 OSes) is still required before M9.

- **Compact density theme** ✅: compact control set (`ui/Compact.kt`: `CompactSearchField`,
  `CompactButton`, `CompactMenuItem`, `CompactMenuCustom`) replaces stock M3 in the search
  bar, toolbar, tab context menu, log-view context menu and settings rows (search-bar row
  ≈ 32 dp, menu items ≈ 26 dp). Modal `AlertDialog`s (go-to-line, rename, about, color
  picker) stay stock M3 — modal and infrequent.
- **Toolbar** ✅: `Toolbar` in `App.kt` (open, follow, go-to-line, wrap, layout dropdown,
  unpin-all, settings); enabled/checked state tracks the active tab.
- **Tab bar always visible** ✅: the auto-hide-when-single-file behavior is removed
  outright (existing `loggi.conf` values are ignored) — the tab bar hides only with zero
  files open.
- **Multi-instance** ✅ (process-level decision): several OS processes run side by side;
  `SettingsStore.save` writes atomically (tmp + `ATOMIC_MOVE`, plain-replace fallback), so
  concurrent instances cannot corrupt `loggi.conf`; settings are last-writer-wins
  (documented policy). An in-app second `Window` with its own tab set is deliberately not
  implemented (session/scope coupling) — it stays in the stretch backlog.
- **Pins ∪ matches live refresh** ✅ fixed + covered by Compose UI tests: the root cause
  was real — a lazy list re-runs its content lambda only when *snapshot state* read inside
  it changes, and `ResultsModel.size` was a plain getter, so matches/pins never invalidated
  the results list (the matches-count label updated while the rows stayed empty). `size` is
  snapshot state now; `SearchPaneUiTest` (Compose UI test over the real JNI engine) covers
  search → rows appearing, IME-search, and pin-without-search.
- **Highlighting** ✅: `HighlighterRule.wholeLine` tints the full line text; the
  `searchMatchWholeLine` setting does the same for search matches; jumps and clicks set
  `FileViewModel.currentLine`, rendered as a full-row tint
  (`LoggiColors.currentLineBackground`). Context menu: with a text selection the selected
  text becomes a substring highlighter; with none, the right-clicked line becomes a
  whole-line highlighter (the item was previously disabled without a selection). Rules are
  managed in their own `HighlightersWindow` (pattern, color, ignore-case, regex, whole-line
  — all editable and live-applied; entry via the Search menu and the settings window).
  Tap-to-clear is filtered to the primary button, so a right-click release no longer wipes
  the drag selection before the highlight action reads it.
- **System font picker** ✅: `ui/Fonts.kt` enumerates installed families via Skia
  `FontMgr` (`matchFamilyStyle` → `platform.Typeface` → `FontFamily`), generic aliases kept
  as fallback; dropdown picker in the settings window.
- **Settings window + reopen toggle** ✅: `SettingsWindow` (own resizable `Window`);
  `reopenOnStartup` (default on) guards `AppViewModel.restoreSession`.
- **Global search history** ✅: persisted in `loggi.conf` (was in-memory only) and shared
  across files; `SearchHistoryWindow` (Search menu + the history dropdown's manage entry)
  deletes individual entries or clears all.
- **Log-view interactions** ✅: horizontal scroll + scrollbar — in the log view when wrap
  is off, and always in the results pane (content width from `maxLineLen`, capped below the
  Compose `Constraints` measurement limit of 262_142 px — wider content ellipsizes at the
  cap; pointer coordinates are scroll-local, so selection math needed no offset); plain
  left-click clears the selection and marks the current line (`onLineClick`). The context
  menu (`ui/LineContextMenu.kt`) is shared by both panes — copy / copy lines / copy
  `name:line` reference / highlight (submenu opens on hover) / pin — and opens exactly at
  the cursor: CMP's DropdownMenu `DpOffset` is applied unscaled on HiDPI, so the menu is
  anchored via a zero-size padding-placed box (2x-density regression test). White-screen
  fix: `ChunkCache.ensure` read-touches present chunks (LRU) and the log view re-ensures
  visible chunks on every cache-version bump, so results-pane churn can't blank the main
  view.
- **Icons** ✅: regenerated at the Apple-grid 824/1024 ratio (centered, bbox-verified);
  `icon.ico` stays full-bleed per Windows convention.
- **Feature-parity re-check** ✅: whole-line highlighter flag landed; QuickFind and
  pull-to-follow elastic scrolling stay in the stretch backlog; "Keep Results" stays
  replaced by pins ∪ matches.

Acceptance: `./gradlew :shared:jvmTest` green (39 tests incl. the Compose UI tests
`SearchPaneUiTest` / `LogViewUiTest` / `CompactControlsUiTest`); manual pass in the
running app on macOS (density, toolbar, h-scroll, click-to-clear, current-line, fonts,
settings + highlighters windows, Dock icon) plus a Windows + Linux spot-check before M9.

### M9 — Performance & memory hardening (explicit pass)
- Profiling pass on all platforms (perf/samply on Linux, Instruments on macOS, VS/ETW on
  Windows); allocation profiling (jemalloc stats or mimalloc); identify hot paths in decode,
  index, search, JNI marshalling.
- Memory-leak & unbounded-growth audit: chunk pool sizes, bitset growth, results cache eviction,
  JNI GlobalRef lifecycle, thread-pool teardown on file close, watcher deregistration; soak
  tests (thousands of open/search/close cycles; 24 h follow of a growing file) asserting RSS
  plateaus.
- Performance gates: benchmark suite (M4) run on CI with thresholds; memory budget model
  documented (index bytes/line, bitset bytes, chunk cache cap, search cache cap).
- Optional: mmap backend (memmap2 + MADV_SEQUENTIAL) behind a feature flag; keep-file-closed
  mode for Windows; index compression option.
- Acceptance: soak runs clean (no monotonic RSS growth); perf gates green; documented
  `docs/perf.md` with numbers and decisions.

### M10 — Packaging & release (installers + signing + release CI)
- Installer targets per OS (JDK `jpackage`, `-Ploggi.jni.profile=release`, icons from
  `packaging/`):
  - macOS: `.dmg` (+ `.pkg` best-effort);
  - Windows: `.exe` (NSIS);
  - Linux: `.deb` (Ubuntu/Debian), `.rpm` (RHEL/Fedora/SUSE — **new**, jpackage supports
    `TargetFormat.Rpm`), AppImage best-effort.
  - The Rust cdylib is already bundled inside the jar resources and extracted at runtime —
    verify it lands inside the app image on all targets.
- Menu entries + app registration: macOS `CFBundleDisplayName`, Windows Start-menu shortcut,
  Linux `.desktop` file (icons already per-OS). (File *association* is M11.)
- Code signing: macOS notarization (Developer ID + stapler), Windows Authenticode
  (documented, best-effort in CI with secrets), Linux signing best-effort.
- **Release CI** (`release.yml`, new): triggers on tag push (`v*`); jobs:
  - matrix build (ubuntu/macos/windows) → `packageDmg|Exe|Deb|Rpm` with release JNI profile;
  - per-OS smoke tests on the packaged app (open 10 GB file, search, follow);
  - assemble release: installers + `SHA256SUMS` + release notes → **upload to GitHub Releases**
    as artifacts.
- Versioning: semver derived from the tag; changelog (`docs/release.md`).
- Acceptance: one tag produces a release with installer artifact sets for all OS families
  (Windows, macOS, Ubuntu, RHEL); each installer opens a 10 GB file and passes the smoke list.

### M11 — OS integration: "Open with Loggi" (file association, i18n)
- **Command-line entry point**: `loggi <file>` — `main.kt` reads `args` and opens the first
  file argument (multiple args → one tab each) via `AppViewModel.openFile`. This is the
  foundation for every "open with" path below.
- **Any extension**: already true in-app (AWT `FileDialog` has no extension filter; engine has
  no extension restriction) — M11 only adds the OS-level entry points; no new file-type
  filtering is introduced anywhere.
- **Windows** — register a shell verb on `HKCU\Software\Classes\*\shell\Loggi` (any file
  type, no admin needed): command → `"<install>\Loggi.exe" "%1"`. Registration happens on
  first run (and re-registered on locale change), since jpackage's per-machine registry
  writes need admin. **i18n**: the verb display name is written from the
  current UI locale (EN "Open with Loggi" / zh-Hans "使用 Loggi 打开"); re-run on locale
  switch updates it (string pairs already exist in `i18n/Strings.kt`).
- **macOS** — `CFBundleDocumentTypes` in `Info.plist` with `LSItemContentTypes =
  [public.data]` (covers every extension; UTIs remain dynamic so arbitrary extensions work);
  LaunchServices then offers Loggi under Finder's "Open With" automatically (the menu title
  is OS-localized, App display name = localized `CFBundleDisplayName`). Verify open-at-launch
  via `NSApplicationDelegate` (jpackage-generated `main` passes the file path as argv —
  covered by the M11 command-line entry point).
- **Linux** — `.desktop` file with `MimeType=text/plain;text/x-log;application/x-log;
  text/*;application/octet-stream;` + `xdg-mime default` registration in the package
  postinst; file managers show Loggi under "Open With Other Application" for any file.
  **i18n**: `.desktop` `Name` / `Name[zh_CN]` keys.
- Acceptance: on each OS, right-click any file → Loggi appears (Windows verb "使用 Loggi
  打开"/"Open with Loggi"; macOS Open With; Linux Open With); launching opens the file in a
  tab; locale switch re-registers the Windows verb in the other language.

---

## 4. Cross-cutting: performance & memory strategy (applies from M1)

- **Memory model**: only line-offset index + bitsets + bounded chunk buffers; never the file
  content. Budgets: index ≤ ~4–8 bytes/line compressed, matches ≤ 1 bit/line (Roaring), chunk
  cache cap (configurable MB), search-results cache cap (configurable lines).
- **Zero-copy fast paths**: UTF-8 raw views for search; direct-byte-buffer chunks to JVM.
- **Bounded concurrency**: rayon pools sized by config; IO and match phases pipelined with
  backpressure (bounded prefetch limiter); cooperative cancel on every operation.
- **Measurement first**: every performance claim backed by `crates/bench` results
  (`docs/benchmarks.md`); soak tests part of CI (fast subset) and nightly (full).
- **Leak prevention rules** (review checklist in code review / AGENTS.md):
  1. no per-line allocations in search/display hot paths;
  2. every JNI `GlobalRef`/`ByteBuffer` released on a single owner's drop;
  3. every async task cancellable and joined on close (no orphaned threads);
  4. caches have caps and eviction; bitsets reset on file truncation;
  5. watcher/handle deregistration on file close;
  6. soak test must show flat RSS.

## 5. Stretch backlog (post-M10, unordered)

- Archives: open gzip/bz2/xz/zip/7z (stream-decompress to a temp file).
- QuickFind: incremental in-file find bar with prev/next + long-search notification.
- Boolean combining of multiple search terms; inverse match; search in all open tabs.
- Predefined filters + saved searches + favorites; search history management.
- Follow-mode "pull to elastic" auto-scroll behavior; Auto-refresh toggle per tab.
- Scratchpad window (transform tools: CRC32/hex/base64/JSON format).
- System tray; drag & drop file opening. (Multi-window/multi-instance is a hard requirement —
  moved to M8.5.)
- HiDPI verification (per-monitor density testing).
- Auto-update channel (nice-to-have for the 3 OSes).
- MCP tools beyond search: `file_info`, `tail_stream`, `highlight_feed` for agent UIs.

## 6. Risks & mitigations

| Risk | Mitigation |
|---|---|
| JNI marshalling overhead dominates UI | chunked direct-buffer transfers; batch counters; poll not callback; measure in M9 |
| Regex throughput below rg | literal prefilter (memchr/aho-corasick) from day one; benchmark-driven in M4; consider `pcre2` fallback |
| 100 GB class files stress index | compressed index blocks (M4), optional mmap, on-disk index cache (stretch) |
| Platform divergence (Windows file locking, notarization) | keep-file-closed mode; CI matrix from M0; signing early in M10 schedule |
| Memory leaks in long sessions | soak gates from M5; JNI lifecycle audit in M9 |
| Compose performance on huge lists | virtualization from day one (M5); chunk pooling; no per-line objects |
| Scope creep in UI | requirements frozen per milestone with acceptance criteria; stretch items tracked in backlog |
| Desktop UX gaps found late (M8 audit: M3 density, icons, interactions) | M8.5 gates M9; acceptance verified in the running app, not just in code |

## 7. Repository layout (target)

```
AGENTS.md
docs/
  PLAN.md
  benchmarks.md        (M4)
  perf.md              (M9)
  release.md           (M10)
Cargo.toml             (workspace)
settings.gradle.kts    (includes :shared + :desktopApp)
build.gradle.kts       (plugin aliases only)
packaging/             (app icons: icon-512.png, icon.icns, icon.ico)
shared/                (KMP module, JVM target)
  src/commonMain/composeResources/   (app icon drawable)
  src/jvmMain/           (all UI + ViewModels + JNI host)
  src/jvmTest/           (bridge smoke test + model/settings/i18n tests)
desktopApp/            (app entry: main.kt, nativeDistributions packaging)
crates/
  engine/              loggi-engine: indexing, search, watch, encoding
  engine-jni/          cdylib JNI bridge
  cli/                 loggi binary
  mcp/                 MCP server binary
  bench/               criterion + soak harness
.github/workflows/     CI (3 OS) + release
```

## 8. Milestone dependency graph

```
M0 ─▶ M1 ─▶ M2 ─▶ M3 ─▶ M4 ─┐
                              ├─▶ M5 ─▶ M6 ─▶ M7 ─▶ M8 ─▶ M8.5 ─▶ M9 ─▶ M10 ─▶ M11
(M0..M4 pure Rust, verifiable   │
 via CLI + bench; M5+ UI)       │
UI work can start at M5 with   ▼
a stub engine only if needed   (M5 depends on engine API, not on M2/M3/M4 features)
```
