# loggi

A desktop log viewer for very large log files.

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
  drag-selection copy, text highlighting, pinned lines, light/dark themes, EN + zh-Hans i18n,
  `loggi.conf` session persistence, minimap overview strip. Rust interop is JNI
  (`crates/engine-jni` cdylib; UniFFI was considered and rejected for hot-buffer control —
  see `docs/PLAN.md` §2).

See `docs/PLAN.md` (milestones M0–M10) and `docs/benchmarks.md` (measured
baselines).

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

# Windows (on a Windows host) / Debian (on a Linux host)
./gradlew :desktopApp:packageMsi -Ploggi.jni.profile=release
./gradlew :desktopApp:packageDeb -Ploggi.jni.profile=release
```

Settings live in `loggi.conf` next to the working directory when present (portable mode),
otherwise in the per-OS app-config dir (`~/.config/loggi`, `%APPDATA%\loggi`,
`~/Library/Application Support/loggi`). Override with `-Dloggi.config=<path>`.

