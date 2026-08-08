# AGENTS.md — Development Guide for loggi

**loggi** is a desktop log viewer for very large log files: a Rust engine for line indexing,
lazy reads, fast search and file watch; a CLI and an MCP server reusing that engine; and a
Kotlin Multiplatform (Compose Multiplatform) desktop UI, packaged as native installers for
Windows / macOS / Linux.

**Read first**: `docs/PLAN.md` (milestones M0–M10). Follow the milestone plan; do not
implement stretch backlog items without confirmation.

## Project layout

```
crates/engine          loggi-engine: line indexing, lazy reads, parallel search, file watch, encodings
crates/engine-jni      JNI cdylib bridge (thin marshalling only, no logic)
crates/cli             `loggi` CLI (info / search / tail)
crates/mcp             MCP server (stdio JSON-RPC over the engine)
crates/bench           criterion benchmarks + soak harness (+ gen-log data generator)
shared/                KMP module (JVM target): all UI, ViewModels, settings/i18n/theme, JNI host
desktopApp/            app entry (main.kt) + native installer packaging (icons in packaging/)
packaging/             icon-512.png / icon.icns / icon.ico
docs/                  PLAN.md, benchmarks.md, perf.md, release.md, audit-*

## Commands

Rust (from repo root):

- Build: `cargo build` / `cargo build --release`
- Test: `cargo test` (always run before finishing a task)
- Lint: `cargo clippy --workspace --all-targets -- -D warnings` (must pass)
- Format: `cargo fmt` (must produce no diff)
- Benchmarks: `cargo bench -p bench` (or the soak script under `crates/bench`)
- Generate test data: `cargo run -p bench --bin gen-log -- <size> <pattern> <path>` (see `crates/bench/src/bin/gen-log.rs`)

Desktop (from repo root; two Gradle modules — `shared` (KMP, JVM target: all UI + JNI host)
and `desktopApp` (entry point + packaging); project-level Gradle config lives in the repo root):

- Build/run: `./gradlew :desktopApp:run` — the JNI cdylib is built automatically by the
  `cargoBuildJni` task (debug profile; `-Ploggi.jni.profile=release` for packaging) and staged
  into jar resources under `natives/<os>-<arch>/` (extracted + `System.load`ed at runtime; in
  dev the loader also probes `target/<profile>/` at the repo root). Do not commit binaries.
- Compile only (no cargo needed): `./gradlew compileKotlinJvm compileTestKotlinJvm compileKotlin`
- JVM tests (bridge smoke + model + Compose UI tests; builds the cdylib via cargo):
  `./gradlew :shared:jvmTest`
- Installers (M10 hardening): `./gradlew :desktopApp:packageDmg|Msi|Deb
  -Ploggi.jni.profile=release`; app icons come from `packaging/`.

CI runs: `cargo fmt --check`, `cargo clippy -D warnings`, `cargo test`, Gradle build, on
windows-latest / macos-latest / ubuntu-latest.

## Conventions

- Rust: 2018+ edition style, `rustfmt` defaults, no unsafe unless required (JNI bridge,
  zero-copy paths) — every `unsafe` block needs a comment justifying soundness.
- Engine API is UI-agnostic: no GUI types, no println. CLI/MCP/UI are separate consumers.
- Kotlin: Compose Multiplatform, kotlinx-coroutines; ViewModels own state; no business logic in
  composables.
- UI density: chrome/menus/settings use the compact controls in `ui/Compact.kt` (stock M3
  padding only inside modal dialogs); fonts resolve via `ui/Fonts.kt` (generic aliases +
  Skia-enumerated system families). Dropdown/popup menus use `CompactDropdownMenu` (exact
  anchor-below positioning) — not M3 `DropdownMenu`, whose 48 dp `MenuVerticalMargin`
  pushes popups away from anchors near the window top (toolbar, tab bar, first log rows).
- Fonts: `fontFamily` = log-view font (default Monospace), `uiFontFamily` = UI font
  (default `System` = OS default UI font, applied via `LoggiTheme` typography).
- JNI bridge rules:
  - thin only — no engine logic in `engine-jni`;
  - long operations never run on the UI thread (use `Dispatchers.Default`);
  - transfer line data as chunked direct ByteBuffers, never per-line Java strings;
  - every `GlobalRef`/`ByteBuffer` is released by a single owning drop;
  - searches are cancellable via search-id + engine flag; cancellation must join worker threads.
- Errors: engine returns typed results (`Result`); the bridge maps them to checked exceptions;
  the UI surfaces them without panic paths.
- Settings: portable file (`loggi.conf` beside the binary) or app-config dir; follow
  `docs/PLAN.md` M8.

## Documentation

- **`README.md` (English) and `README-cn.md` (zh-Hans) must be updated
  together** whenever the project's reality changes — new commands, new
  install formats, new CI workflow, new milestone, dropped feature,
  packaging change, etc. Treat the two READMEs as a single bilingual
  artifact: never ship a `README.md` edit that is not mirrored in
  `README-cn.md`, and vice-versa. The link from `README.md` to the Chinese
  version must stay valid.
- Internal docs (`docs/PLAN.md`, `docs/benchmarks.md`, `docs/perf.md`,
  `docs/release.md`, `docs/audit-*`) are the source of truth for design
  decisions; the READMEs point to them, do not duplicate them.
- Before claiming a milestone done, re-read both READMEs and confirm every
  command, file path, and version reference still matches what the code
  actually does.

## Testing expectations

- Unit + property tests on synthetic files (trailing-LF-less files, UTF-16, CRLF, huge single
  lines, empty files) in `crates/engine`.
- CLI golden tests against generated corpora (`crates/cli/tests`).
- Soak test (M5+): open → search → close cycles with RSS sampling; must show flat RSS. CI runs a
  fast subset; nightly runs the full matrix.
- Perf gates: benchmark thresholds in CI (see `docs/benchmarks.md` after M4).
- Before claiming a milestone done: acceptance criteria in `docs/PLAN.md` must be verifiable.

## Code review checklist (memory & perf)

1. No per-line allocations in search/display hot paths (reuse buffers).
2. No unbounded caches; every cache has a cap + eviction.
3. Bitsets/indices reset on file truncation; watchers deregistered on close.
4. All async tasks cancellable and joined; no orphaned threads after close.
5. JNI references released deterministically.
6. `cargo test` + clippy + fmt clean.
7. Benchmarks attached for any perf-sensitive change.

## Status

Engine milestones M0–M4 are pure Rust (verifiable via CLI + bench). M5–M7 UI
work is done, on the `shared` (KMP/JVM) + `desktopApp` module structure: JNI
bridge (open/info/readLines/search/match/refresh/close over polled native
threads), virtualized main view with line-number gutter, drag selection +
right-click context menu (copy selected text / lines / whole line, highlight
with preset or custom color — matched in the engine per line and cached,
pins), search bar + streaming results with history, three layouts
(side/top/detached search window) with persisted splitter, tabs (close /
close-others / left / right / all, middle-click close, drag reorder,
horizontal/vertical placement, rename, copy path, open folder), display
settings (font size, line spacing, wrap, tab stop; log font family via generic
aliases + Skia-enumerated system families; separate UI font family for the
whole interface, defaulting to the OS font, applied through the theme
typography), themes (follow system / force light / force dark, live
OS listener via skiko polling), i18n EN + zh-Hans via `i18n/Strings.kt` (a
reflection test asserts the zh-Hans override is complete; switch is live),
`loggi.conf` persistence + session restore, minimap overview strip. Pins are
kept UI-side (`ResultsModel` maintains the sorted union pins ∪ matches), not
in the engine. JVM smoke test (`shared/src/jvmTest`) exercises the full
bridge contract.

M8.5 (2026-08-08) closed the M8 gaps: compact control set (`ui/Compact.kt`)
replacing stock M3 padding in the search bar / menus / settings rows, a
toolbar (open/follow/go-to-line/wrap/layout dropdown/unpin-all/settings),
tab bar always visible (auto-hide removed), settings in their own window,
reopen-on-startup toggle, system font picker via Skia `FontMgr`, whole-line
+ current-line highlight, horizontal scroll (log view when wrap is off +
results pane), click-to-clear selection, platform-correct icons (824/1024
macOS; ico stays full-bleed), process-level multi-instance (atomic
`loggi.conf` writes; last-writer-wins documented). Highlighters are managed
in their own window (`ui/HighlightersWindow.kt`: pattern/color/ignore-case/
regex/whole-line, live-applied); the context menu falls back to a whole-line
highlight when there is no text selection. Pins/results live-refresh turned
out to be a real bug — the results LazyColumn never invalidated because
`ResultsModel.size` was a plain getter (snapshot state now) — fixed and
covered by Compose UI tests (`SearchPaneUiTest`); a manual run pass on the
3 OSes gates M9. The context menu (`ui/LineContextMenu.kt`) is shared by
both panes (copy / copy lines / `name:line` reference / highlight — its
hover submenu also holds remove-highlight — / remove-all / pin) and
anchored via a zero-size padding-placed box — CMP's DropdownMenu `DpOffset`
is mis-scaled on HiDPI. All dropdowns/context menus render through
`CompactDropdownMenu` (`ui/Compact.kt`): M3's `DropdownMenu` refuses to
place a popup within 48 dp of the window top (`MenuVerticalMargin`), which
detached the toolbar layout menu from its button and pushed the highlight
submenu off its row; the replacement anchors popup top/left exactly to the
anchor (submenu: row top-end corner), covered by
`LogViewUiTest.contextMenuOpensAtCursor` / `highlightSubmenuAlignsWithParentRow`
and `CompactControlsUiTest.dropdownOpensFlushBelowAnchorAtWindowTop`. Main-view
blanking
from results-pane chunk churn is fixed (LRU touch + re-ensure on version).
Search history is global and persisted in `loggi.conf`
(`ui/SearchHistoryWindow.kt` deletes entries / clears all); tap-to-clear is
primary-button-only, so right-click keeps the drag selection for highlight.

Remaining milestones: M9 (perf & memory hardening), M10 (packaging/release:
Dmg/Msi/Deb/**Rpm** installers, signing, release CI uploading artifacts to
GitHub Releases), M11 (OS integration: `loggi <file>` CLI entry, "Open with
Loggi"/"使用 Loggi 打开" right-click verb for any file extension with i18n —
see `docs/PLAN.md`).

M9 (2026-08-08) closed the memory + perf hardening pass: JNI matcher cache
now a 64-entry LRU (no unbounded highlighter retention); index bytes/line
unit-tested at ≤ 8 bytes/line (1.41 measured on the Android bugreport);
search cache eviction covered by `cycles_dont_grow_cache` and
`dense_results_not_cached`; `soak` now has an explicit 32 MiB post-warmup
RSS growth budget. See `docs/perf.md` for the full memory model + gates.

M10 (2026-08-08) closed the packaging + release pipeline: `desktopApp` ships
Dmg/Pkg/Msi/Exe/Deb/**Rpm** (Rpm is new in M10); macOS signing +
notarization are best-effort and gated on `MACOS_SIGNING_*` /
`MACOS_NOTARIZATION_*` env vars; Windows Authenticode signing is applied by
a post-build signtool step in `release.yml`. The release workflow
(`.github/workflows/release.yml`) fires on `v*` tag push, on
`release: published`, or on workflow dispatch (see `docs/release.md` §
Trigger); it matrix-builds every
target, runs the perf-gate smoke against a 1 GiB corpus, and uploads
installers + `SHA256SUMS` to GitHub Releases. See `docs/release.md`.

M11 (2026-08-08) closed the OS file-association pass: `main.kt` accepts
file paths as positional args (one per tab). The "Open with Loggi" verb
shows up under right-click for any file extension on every OS:
- macOS: jpackage's `fileAssociation("*", "public.data", …)` lands in
  `Info.plist` (`CFBundleDocumentTypes`, `LSItemContentTypes`) — no runtime
  work.
- Windows: runtime registration to `HKCU\Software\Classes\*\shell\Loggi`
  via `os.WindowsFileAssociation` (`reg.exe`); jpackage's MSI verb is
  HKLM-only (admin required), so per-user HKCU is re-registered on first
  run and on every locale change so the verb display name follows the UI
  language.
- Linux: jpackage writes the system-wide `.desktop` with `MimeType=*`;
  `os.LinuxFileAssociation` also writes a per-user copy in
  `XDG_DATA_HOME/applications/loggi-user.desktop` (atomic write, locale-
  aware) so the verb shows up without root or a relogin.
Verb display name is localized (`Open with Loggi` / `使用 Loggi 打开`).
See `shared/.../os/FileAssociation.kt` + JVM tests in
`shared/src/jvmTest/.../os/FileAssociationTest.kt`.
