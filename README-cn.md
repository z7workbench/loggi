<p align="center">
  <img src="packaging/icon-512.png" alt="loggi" width="128" />
</p>

<h1 align="center">loggi</h1>

<p align="center">
  面向超大日志文件的桌面日志查看器。
  (<a href="README.md">English</a>)
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

面向超大日志文件的日志查看器：Rust 引擎负责行索引、惰性读取、并行
ripgrep 级搜索与文件监听；CLI 与 MCP 服务器复用同一引擎；Kotlin
Multiplatform（Compose Multiplatform）桌面 UI 打包成 Windows、macOS、
Linux 原生安装程序。

- **Rust 引擎**（`crates/engine`）：行偏移索引（压缩后约 1.4 字节/行）、
  基于 `pread` 的惰性读取、并行 ripgrep 级搜索（Roaring 位图、
  memchr/aho-corasick/regex 快速路径）、XXH64 变更检测的尾部跟随、
  UTF-8/UTF-16/UTF-32 + chardetng 编码。
- **CLI**（`crates/cli`）：`loggi info|search|tail`，带 rg 风格参数与
  `--json` 输出。
- **MCP 服务器**（`crates/mcp`）：stdio JSON-RPC 服务器（`file_info`、
  `read_lines`、`search` 流式批次 + 进度、`cancel`）。
- **基准套件**（`crates/bench`）：criterion 基准、soak 压力测试（平坦
  RSS 门禁）、合成日志生成器（`gen-log`）、CI 性能门禁。
- **桌面应用**（`shared` + `desktopApp` Gradle 模块）：Kotlin
  Multiplatform（Compose Multiplatform，JVM 目标）——基于引擎分块的
  虚拟化主视图、三种布局的流式搜索（左右 / 上下 / 独立窗口）、标签页
  （水平或垂直）、拖拽选择复制、文本高亮、固定行、明暗主题 + 九种
  配色方案（紫罗兰 / 蓝 / 青 / 绿 / 橙 / 琥珀 / 玫红 / 石墨 / 靛蓝）、
  六语言界面（英 / 简中 / 繁中 / 法 / 德 / 俄）、
  `loggi.conf` 会话持久化、迷你概览条、从系统文件管理器拖放文件
  （把任意文件拖到窗口即可打开）。Rust 互操作走 JNI
  （`crates/engine-jni` cdylib；曾评估 UniFFI，因热缓冲区控制需求而
  放弃——见 `docs/PLAN.md` §2）。

参见 `docs/PLAN.md`（里程碑 M0–M11）、`docs/perf.md`（M9 内存模型
+ 性能门禁）、`docs/release.md`（M10 发布流程）以及
`docs/benchmarks.md`（实测基线）。

## 截图

> 即将补上——把 PNG 放进 `docs/screenshots/`，然后在此处引用。建议：
> 主视图 + 侧边搜索、独立搜索窗口、迷你概览条、高亮规则、中文界面。

## 安装

每个支持平台都有预编译安装包，发布在
[Releases](https://github.com/z7workbench/loggi/releases/latest) 页面。
安装包捆绑了 JRE 与 JNI cdylib；图标取自 `packaging/`。应用图标由
[icon.kitchen](https://icon.kitchen) 网站生成，安装包、关于窗口以及
`shared/src/commonMain/composeResources/` 里的 `loggi_icon` 资源均使用
该图标。

| 操作系统 | 安装包格式 |
|---|---|
| macOS | `.dmg`（拖入 Applications）、`.pkg` |
| Windows | `.exe`（NSIS） |
| Linux | `.deb`（Ubuntu/Debian）、`.rpm`（RHEL/Fedora/SUSE） |

macOS 与 Windows 的安装包会注册任意文件扩展名的**「使用 Loggi 打开」**
——各平台的具体实现见 `docs/PLAN.md` §3 M11。

## 环境要求

- **Rust**（stable 工具链，含 `rustfmt` + `clippy` 组件）——引擎、CLI、
  MCP、JNI 桥。
- **JDK 21**——Gradle 构建/运行桌面应用（缺少 `JAVA_HOME` 时工具链
  解析器会自动提供）。
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

安装程序捆绑 JRE 与 JNI cdylib；图标来自 `packaging/`。使用 release
Rust 配置以获得优化的内置引擎：

```sh
# macOS → desktopApp/build/compose/binaries/main-release/dmg/
./gradlew :desktopApp:packageDmg -Ploggi.jni.profile=release
./gradlew :desktopApp:packagePkg -Ploggi.jni.profile=release

# Windows（在 Windows 主机上）/ Debian & RPM（在 Linux 主机上）
./gradlew :desktopApp:packageExe -Ploggi.jni.profile=release
./gradlew :desktopApp:packageDeb -Ploggi.jni.profile=release
./gradlew :desktopApp:packageRpm -Ploggi.jni.profile=release
```

M11：每个安装程序都会为任意文件扩展名注册「使用 Loggi 打开」。
macOS 走 `jpackage` 的 `Info.plist`，Linux 走 Deb/Rpm 安装的 `.desktop`
MimeType，Windows 走运行时的当前 UI 语言下注册（每用户注册表项 +
Linux 的 `~/.local/share/applications/loggi-user.desktop`）。
也可以把文件从 Finder / 资源管理器 / Nautilus 直接拖放到应用窗口，
每个拖入的普通文件都会在独立标签页中打开。
矩阵构建与签名流程见 `docs/release.md`。

设置保存在 `loggi.conf` 中：工作目录下存在时使用便携模式，否则使用
各平台的应用配置目录（`~/.config/loggi`、`%APPDATA%\loggi`、
`~/Library/Application Support/loggi`）。可用 `-Dloggi.config=<path>`
覆盖。

## 项目结构

```
crates/engine          loggi-engine：行索引、惰性读取、并行搜索、文件监听、编码
crates/engine-jni      JNI cdylib 桥（仅做薄薄的编组，无业务逻辑）
crates/cli             `loggi` CLI（info / search / tail）
crates/mcp             MCP 服务器（基于引擎的 stdio JSON-RPC）
crates/bench           criterion 基准 + soak 压力测试（含 gen-log 数据生成器）
shared/                KMP 模块（JVM 目标）：全部 UI、ViewModel、设置/i18n/主题、JNI 宿主
desktopApp/            应用入口（main.kt）+ 原生安装包打包（图标取自 packaging/）
packaging/             icon-512.png / icon.icns / icon.ico
docs/                  PLAN.md、benchmarks.md、perf.md、release.md、audit-*
.github/workflows/     ci.yml（push/PR）+ release.yml（push / tag → GitHub Releases）
```

## 许可

[MIT](LICENSE)——完整文本见该文件。
