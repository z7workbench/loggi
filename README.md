<p align="center">
  <img src="packaging/icon-512.png" alt="loggi" width="128" />
</p>

<h1 align="center">loggi</h1>

<p align="center">
  A desktop log viewer for very large log files.
  (<a href="README-cn.md">中文</a>)
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/z7workbench/loggi" alt="License: MIT" /></a>
  <a href="https://github.com/z7workbench/loggi/releases/latest"><img src="https://img.shields.io/github/v/release/z7workbench/loggi?include_prereleases&sort=semver" alt="Latest release" /></a>
  <a href=".github/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/z7workbench/loggi/ci.yml?label=CI" alt="CI status" /></a>
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-blue" alt="Platforms" />
  <img src="https://img.shields.io/badge/rust-stable-orange?logo=rust" alt="Rust" />
  <img src="https://img.shields.io/badge/Compose%20Multiplatform-1.11.1-4285F4?logo=jetpackcompose" alt="Compose Multiplatform" />
  <img src="https://img.shields.io/badge/MCP-1.0-7E3FB2" alt="MCP" />
</p>

---

A log viewer for very large log files: a Rust engine for line indexing,
lazy reads, parallel ripgrep-class search and file watch; a CLI and an
MCP server reusing that engine; and a Kotlin Multiplatform (Compose
Multiplatform) desktop UI, packaged as native installers for Windows,
macOS, and Linux.

- **Rust engine** (`crates/engine`): line-offset indexing (compressed, ~1.4
  bytes/line), lazy `pread`-based reading, parallel ripgrep-class search
  (Roaring bitsets, memchr/aho-corasick/regex fast paths), follow-tail with
  XXH64 change detection, UTF-8/UTF-16/UTF-32 + chardetng encodings.
- **CLI** (`crates/cli`): `loggi info|search|tail` with rg-inspired flags and
  `--json` output.
- **MCP server** (`crates/mcp`): stdio JSON-RPC server (`file_info`,
  `read_lines`, `search` with streamed batches + progress, `cancel`).
- **Bench suite** (`crates/bench`): criterion benchmarks, soak harness (flat
  RSS gate), synthetic log generator (`gen-log`), CI perf gate.
- **Desktop app** (`shared` + `desktopApp` Gradle modules): Kotlin Multiplatform
  (Compose Multiplatform, JVM target) — virtualized main view over engine chunks, streaming
  search with three layouts (side / bottom / detached window), tabs (horizontal or vertical),
  drag-selection copy, text highlighting, pinned lines, light/dark themes with
  nine color schemes (violet/blue/teal/green/orange/amber/rose/slate/indigo),
  6-locale i18n (EN, zh-Hans, zh-Hant, FR, DE, RU),
  `loggi.conf` session persistence, minimap overview strip, file drag & drop
  from the OS file manager (drop any file onto the window to open it). Rust
  interop is JNI
  (`crates/engine-jni` cdylib; UniFFI was considered and rejected for hot-buffer control —
  see `docs/PLAN.md` §2).

See `docs/PLAN.md` (milestones M0–M11), `docs/perf.md` (M9 memory model +
perf gates), `docs/release.md` (M10 release process), and
`docs/benchmarks.md` (measured baselines).

## Screenshots

> Coming soon — drop a PNG into `docs/screenshots/` and reference it here.
> Suggested shots: log view + side search, detached search window, minimap
> overview, highlighter rules, zh-Hans locale.

## Install

Pre-built installers for every supported OS family are published on the
[Releases](https://github.com/z7workbench/loggi/releases/latest) page.
Each installer bundles the JRE and the JNI cdylib; icons come from
`packaging/`.

| OS | Formats |
|---|---|
| macOS | `.dmg` (drag-to-Applications), `.pkg` |
| Windows | `.msi` (per-user, dirChooser) |
| Linux | `.deb` (Ubuntu/Debian), `.rpm` (RHEL/Fedora/SUSE) |

macOS and Windows installers register **"Open with Loggi"** for any
file extension — see `docs/PLAN.md` §3 M11 for the per-OS plumbing.

## Prerequisites

- **Rust** (stable toolchain, `rustfmt` + `clippy` components) — engine, CLI, MCP, JNI bridge.
- **JDK 21** — the Gradle build compiles/runs the desktop app (the toolchain resolver
  auto-provisions one when `JAVA_HOME` is missing).
- `cargo` on `PATH` — the Gradle `cargoBuildJni` task shells out to it.

## Build

```sh
# Rust workspace (engine, CLI, MCP, bench, JNI cdylib) — debug
cargo build

# …or release
cargo build --release

# Desktop app (Kotlin/Compose) — also builds the JNI cdylib via cargo
./gradlew :desktopApp:build
```

## Run

```sh
# CLI: info / search / tail
./target/release/loggi info <file>
./target/release/loggi search -F -i "ERROR" <file>
./target/release/loggi search -C 2 --json <file> <pattern>
./target/release/loggi tail --lines 50 --follow <file>

# MCP server (stdio)
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | ./target/release/loggi-mcp

# Desktop app — auto-builds the JNI cdylib (debug profile) on first run
./gradlew :desktopApp:run

# benchmarks / test data / soak
cargo run --release -p loggi-bench --bin perf-gate
cargo run --release -p loggi-bench --bin gen-log -- 1g repeat /tmp/big.log
cargo run --release -p loggi-bench --bin soak -- /tmp/big.log 100
```

## Test

```sh
cargo test --workspace          # Rust: unit + property + CLI golden + MCP tests
cargo clippy --workspace --all-targets -- -D warnings
cargo fmt --check

./gradlew :shared:jvmTest       # JVM: JNI bridge smoke test + model/settings/i18n tests
```

## Package installers

Installers bundle the JRE and the JNI cdylib; icons come from `packaging/`.
Use the release Rust profile so the bundled engine is optimized:

```sh
# macOS → desktopApp/build/compose/binaries/main-release/dmg/
./gradlew :desktopApp:packageDmg -Ploggi.jni.profile=release
./gradlew :desktopApp:packagePkg -Ploggi.jni.profile=release

# Windows (on a Windows host) / Debian & RPM (on a Linux host)
./gradlew :desktopApp:packageMsi -Ploggi.jni.profile=release
./gradlew :desktopApp:packageDeb -Ploggi.jni.profile=release
./gradlew :desktopApp:packageRpm -Ploggi.jni.profile=release
```

M11: every installer registers "Open with Loggi" for any file extension.
The verb is wired through `jpackage` (`Info.plist` on macOS, the
MSI-installed `.desktop` MimeType on Linux) and re-registered at runtime
in the active UI locale (Windows per-user registry entry + Linux
`~/.local/share/applications/loggi-user.desktop`). Files can also be
dropped onto the app window from Finder / Explorer / Nautilus — each
dropped regular file opens in its own tab. See
`docs/release.md` for the matrix build + signing flow.

Settings live in `loggi.conf` next to the working directory when present (portable mode),
otherwise in the per-OS app-config dir (`~/.config/loggi`, `%APPDATA%\loggi`,
`~/Library/Application Support/loggi`). Override with `-Dloggi.config=<path>`.

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
.github/workflows/     ci.yml (push/PR) + release.yml (tag / release published / dispatch)
```

## License

[MIT](LICENSE) — see the file for the full text.
