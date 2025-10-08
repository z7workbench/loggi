# loggi - 高性能日志查看器

[![.NET](https://img.shields.io/badge/.NET-9.0-blue.svg)](https://dotnet.microsoft.com/)
[![Rust](https://img.shields.io/badge/Rust-1.70+-orange.svg)](https://www.rust-lang.org/)
[![Avalonia](https://img.shields.io/badge/Avalonia-11.0+-5596C8?logo=avalonia)](https://avaloniaui.net/)

loggi 是一款跨平台、高性能的日志文件查看器，采用 Rust 核心和 Avalonia UI 构建。它使用内存映射技术高效处理大型日志文件，并提供快速搜索功能。

## ✨ 特性

- **高性能**: 内存映射文件访问，能够快速加载大型日志文件
- **虚拟滚动**: 流畅导航数百万行日志
- **文本搜索**: 快速全文搜索，支持大小写敏感选项
- **正则搜索**: 高级模式匹配，适用于复杂搜索
- **跨平台**: 支持 Windows、Linux 和 macOS
- **现代 UI**: 使用 Avalonia UI 构建，提供原生外观和体验

## 🚀 快速开始

### 先决条件

- [.NET 9.0+](https://dotnet.microsoft.com/download)
- [Rust 1.70+](https://www.rust-lang.org/tools/install)
- Avalonia 11.0+

### 构建项目

#### Windows

```powershell
# 克隆仓库
git clone https://github.com/z7workbench/loggi.git
cd loggi

# 构建 Rust 核心库
.\scripts\build-rust.ps1

# 构建并运行应用程序
cd src\avalonia
dotnet run
```

#### Linux/macOS

```bash
# 克隆仓库
git clone https://github.com/z7workbench/loggi.git
cd loggi

# 构建 Rust 核心库
./scripts/build-rust.sh

# 构建并运行应用程序
cd src/avalonia
dotnet run
```

## 🏗️ 架构

### 项目结构

```
src/
├── rust/                 # Rust 核心库 (loggi_core)
│   ├── Cargo.toml        # Rust 依赖和构建配置
│   └── src/
│       └── lib.rs        # 内存映射、搜索算法和 FFI
├── avalonia/             # Avalonia UI 应用程序
│   ├── Loggi.csproj      # .NET 项目文件
│   ├── Program.cs        # 应用程序入口点
│   ├── App.axaml         # 应用程序定义
│   ├── Views/            # UI 视图和控件
│   ├── ViewModels/       # MVVM 视图模型
│   ├── Services/         # 服务层 (包括 Rust 互操作)
│   └── Converters/       # UI 值转换器
└── scripts/              # 构建和实用脚本
    ├── build-rust.sh     # Unix 构建脚本
    └── build-rust.ps1    # Windows PowerShell 构建脚本
```

### 技术栈

- **后端 (Rust)**:
  - `memmap2`: 用于大型文件访问的高效内存映射
  - `serde`: 用于数据交换的 JSON 序列化
  - `regex`: 用于高级模式匹配的正则搜索
  - FFI: 与 C# 交互的 C ABI 函数

- **前端 (C# Avalonia)**:
  - Avalonia 11+: 跨平台基于 XAML 的 UI 框架
  - CommunityToolkit.Mvvm: MVVM 模式实现
  - ItemsRepeater: 用于性能的虚拟化列表控件

## 🛠️ 使用方法

1. **打开日志文件**: 点击打开文件按钮选择日志文件
2. **导航**: 使用虚拟化滚动条快速浏览日志
3. **搜索**:
   - 文本搜索: 在搜索框中输入搜索词
   - 区分大小写选项: 切换区分大小写的匹配
   - 正则搜索: 使用正则表达式模式进行高级搜索
4. **跳转到结果**: 双击搜索结果以高亮并导航到该行

## 📁 文件结构

该项目组织为具有独立 Rust 和 C# 组件的混合应用程序:

- **Rust (`src/rust`)**: 处理文件 I/O、内存映射和搜索操作
- **Avalonia UI (`src/avalonia`)**: 提供用户界面和应用程序逻辑
- **构建脚本 (`scripts/`)**: 跨平台自动化构建过程

## 💡 性能亮点

- **内存映射**: 大型文件通过内存映射访问，而不是完全加载到 RAM 中
- **高效搜索**: Rust 后端提供快速全文和正则搜索功能
- **虚拟化**: UI 只渲染可见的日志行以实现流畅滚动
- **缓存**: 最近访问的文件映射被缓存以提高性能

## 📄 许可证

本项目根据 MIT 许可证授权 - 详情请参阅 [LICENSE](LICENSE) 文件。