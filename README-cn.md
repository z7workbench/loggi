# loggi

面向超大日志文件的桌面日志查看器。

- **Rust 引擎**（`crates/engine`）：行偏移索引（压缩后约 1.4 字节/行）、基于 `pread`
  的惰性读取、并行 ripgrep 级搜索（Roaring 位图、memchr/aho-corasick/regex 快速路径）、
  XXH64 变更检测的尾部跟随、UTF-8/UTF-16/UTF-32 + chardetng 编码。
- **CLI**（`crates/cli`）：`loggi info|search|tail`，带 rg 风格参数与 `--json` 输出。
- **MCP 服务器**（`crates/mcp`）：stdio JSON-RPC 服务器（`file_info`、`read_lines`、
  `search` 流式批次 + 进度、`cancel`）。
- **基准套件**（`crates/bench`）：criterion 基准、soak 压力测试（平坦 RSS 门禁）、
  合成日志生成器（`gen-log`）、CI 性能门禁。
- **桌面应用**（`shared` + `desktopApp` Gradle 模块）：Kotlin Multiplatform
  （Compose Multiplatform，JVM 目标）——基于引擎分块的虚拟化主视图、三种布局的流式
  搜索（左右 / 上下 / 独立窗口）、标签页（水平或垂直）、拖拽选择复制、文本高亮、
  固定行、明暗主题、中英双语界面、`loggi.conf` 会话持久化、迷你概览条。Rust 互操作
  走 JNI（`crates/engine-jni` cdylib；曾评估 UniFFI，因热缓冲区控制需求而放弃——
  见 `docs/PLAN.md` §2）。

参见 `docs/PLAN.md`（里程碑 M0–M10）与 `docs/benchmarks.md`（实测基线）。

## 环境要求

- **Rust**（stable 工具链，含 `rustfmt` + `clippy` 组件）——引擎、CLI、MCP、JNI 桥。
- **JDK 21**——Gradle 构建/运行桌面应用（缺少 `JAVA_HOME` 时工具链解析器会自动提供）。
- `cargo` 在 `PATH` 中——Gradle 的 `cargoBuildJni` 任务会调用它。

## 构建

```sh
# Rust 工作区（引擎、CLI、MCP、bench、JNI cdylib）——debug
cargo build

# …或 release
cargo build --release

# 桌面应用（Kotlin/Compose）——同时通过 cargo 构建 JNI cdylib
./gradlew :desktopApp:build
```

## 运行

```sh
# CLI：info / search / tail
./target/release/loggi info <file>
./target/release/loggi search -F -i "ERROR" <file>
./target/release/loggi search -C 2 --json <file> <pattern>
./target/release/loggi tail --lines 50 --follow <file>

# MCP 服务器（stdio）
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | ./target/release/loggi-mcp

# 桌面应用——首次运行自动构建 JNI cdylib（debug 配置）
./gradlew :desktopApp:run

# 基准 / 测试数据 / soak
cargo run --release -p loggi-bench --bin perf-gate
cargo run --release -p loggi-bench --bin gen-log -- 1g repeat /tmp/big.log
cargo run --release -p loggi-bench --bin soak -- /tmp/big.log 100
```

## 测试

```sh
cargo test --workspace          # Rust：单元 + 属性 + CLI 黄金 + MCP 测试
cargo clippy --workspace --all-targets -- -D warnings
cargo fmt --check

./gradlew :shared:jvmTest       # JVM：JNI 桥冒烟测试 + 模型/设置/i18n 测试
```

## 打包安装程序

安装程序捆绑 JRE 与 JNI cdylib；图标来自 `packaging/`。使用 release Rust 配置以
获得优化的内置引擎：

```sh
# macOS → desktopApp/build/compose/binaries/main-release/dmg/
./gradlew :desktopApp:packageDmg -Ploggi.jni.profile=release

# Windows（在 Windows 主机上）/ Debian（在 Linux 主机上）
./gradlew :desktopApp:packageMsi -Ploggi.jni.profile=release
./gradlew :desktopApp:packageDeb -Ploggi.jni.profile=release
```

设置保存在 `loggi.conf` 中：工作目录下存在时使用便携模式，否则使用各平台的
应用配置目录（`~/.config/loggi`、`%APPDATA%\loggi`、`~/Library/Application Support/loggi`）。
可用 `-Dloggi.config=<path>` 覆盖。
