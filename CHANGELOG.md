# v1.1 - Ancient Agave

## Changes

- **Architecture**: macOS and Windows installers are now built for both
  **x64 and arm64** (Linux stays x64), and macOS additionally ships a
  **universal** `.dmg` / `.pkg` merged from both architectures with `lipo`
  (launcher, bundled JVM runtime and both archs' JNI dylibs in one jar).
- **Release pipeline**: all CI actions run on Node 24 runtimes; the GitHub
  Release body is taken from this changelog (the current version's section)
  instead of auto-generated PR lists.
- **About window**: shows the release codename.

# v1.0 - Ancient Agave

**loggi** is a desktop log viewer for very large log files: a Rust engine for line indexing, lazy reads, parallel search and file watch, plus a CLI, an MCP server, and a Kotlin Multiplatform (Compose Multiplatform) desktop UI for Windows / macOS / Linux. This is the first stable release.

## Highlights

- **Rust engine** — compressed line-offset indexing (~1.4 bytes/line), lazy `pread`-based reads, parallel ripgrep-class search (memchr / aho-corasick / regex fast paths with Roaring bitsets), follow-tail with XXH64 change detection, UTF-8/16/32 + chardetng encodings.
- **Desktop app**:
  - Virtualized main view with line-number gutter, horizontal scroll, minimap overview strip, drag-selection + context-menu copy, whole-line highlight
  - Streaming search with side / bottom / detached-window layouts, persisted history, results pane with pins (live-refresh fixed in 1.0)
  - Tabs: close / close-others, drag reorder, horizontal/vertical placement, rename, copy path, open folder, browser-style "+" new-tab button
  - Highlighters (pattern / color / ignore-case / regex / whole-line) in their own window, live-applied
  - 9 color schemes, follow-system / light / dark themes, compact toolbar + controls, About window
  - 6 locales: EN, zh-Hans, zh-Hant, FR, DE, RU (live switch)
  - `loggi.conf` persistence + session restore (atomic, portable mode), reopen-on-startup
  - File drag & drop from Finder / Explorer / Nautilus — each file opens in its own tab
  - **"Open with Loggi"** right-click verb for any file extension (macOS Info.plist, Windows per-user registry, Linux `.desktop`), localized
- **CLI** — `loggi info | search | tail` with rg-inspired flags and `--json` output; **MCP server** — stdio JSON-RPC (`file_info`, `read_lines`, streamed `search`, `cancel`).
- **Performance & memory hardening (M9)** — 64-entry LRU JNI matcher cache, bounded search cache with eviction, index ≤ 8 bytes/line (1.41 measured), soak harness with a 32 MiB RSS growth gate.
- **Release pipeline (M10)** — CI matrix on 3 OSes builds native installers bundling JRE + JNI cdylib, best-effort macOS signing/notarization and Windows Authenticode signing, perf-gate smoke against a 1 GiB corpus, `SHA256SUMS` per release.
