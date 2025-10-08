# loggi - High-Performance Log Viewer

[![.NET](https://img.shields.io/badge/.NET-9.0-blue.svg)](https://dotnet.microsoft.com/)
[![Rust](https://img.shields.io/badge/Rust-1.70+-orange.svg)](https://www.rust-lang.org/)
[![Avalonia](https://img.shields.io/badge/Avalonia-11.0+-5596C8?logo=avalonia)](https://avaloniaui.net/)
[![Powered by Qwen-Code](https://img.shields.io/badge/Powered%20by-Qwen--Code-8A2BE2?logo=github&logoColor=white)](https://github.com/QwenLM/Qwen-Code)

loggi is a cross-platform, high-performance log file viewer built with a Rust core and Avalonia UI. It efficiently handles large log files using memory mapping technology and provides fast search capabilities.

## ✨ Features

- **High Performance**: Memory-mapped file access enables fast loading of large log files
- **Virtual Scrolling**: Smooth navigation through millions of log lines
- **Text Search**: Fast full-text search with case-sensitive option
- **Regex Search**: Advanced pattern matching for complex searches
- **Cross-Platform**: Runs on Windows, Linux, and macOS
- **Modern UI**: Built with Avalonia UI for native look and feel

## 🚀 Getting Started

### Prerequisites

- [.NET 9.0+](https://dotnet.microsoft.com/download)
- [Rust 1.70+](https://www.rust-lang.org/tools/install)
- Avalonia 11.0+

### Building the Project

#### Windows

```powershell
# Clone the repository
git clone https://github.com/z7workbench/loggi.git
cd loggi

# Build the Rust core library
.\scripts\build-rust.ps1

# Build and run the application
cd src\avalonia
dotnet run
```

#### Linux/macOS

```bash
# Clone the repository
git clone https://github.com/z7workbench/loggi.git
cd loggi

# Build the Rust core library
./scripts/build-rust.sh

# Build and run the application
cd src/avalonia
dotnet run
```

## 🏗️ Architecture

### Project Structure

```
src/
├── rust/                 # Rust core library (loggi_core)
│   ├── Cargo.toml        # Rust dependencies and build config
│   └── src/
│       └── lib.rs        # Memory mapping, search algorithms, and FFI
├── avalonia/             # Avalonia UI application
│   ├── Loggi.csproj      # .NET project file
│   ├── Program.cs        # Application entry point
│   ├── App.axaml         # Application definition
│   ├── Views/            # UI views and controls
│   ├── ViewModels/       # MVVM view models
│   ├── Services/         # Service layer (including Rust interop)
│   └── Converters/       # Value converters for UI
└── scripts/              # Build and utility scripts
    ├── build-rust.sh     # Unix build script
    └── build-rust.ps1    # Windows PowerShell build script
```

### Tech Stack

- **Backend (Rust)**:
  - `memmap2`: Efficient memory mapping for large file access
  - `serde`: JSON serialization for data exchange
  - `regex`: Advanced pattern matching for regex searches
  - FFI: C ABI functions for interoperability with C#

- **Frontend (C# Avalonia)**:
  - Avalonia 11+: Cross-platform XAML-based UI framework
  - CommunityToolkit.Mvvm: MVVM pattern implementation
  - ItemsRepeater: Virtualized list controls for performance

## 🛠️ Usage

1. **Open a Log File**: Click the open file button to select a log file
2. **Navigate**: Use the virtualized scroll bar to quickly navigate through the log
3. **Search**:
   - Text search: Enter your search term in the search box
   - Case-sensitive option: Toggle for case-sensitive matching
   - Regex search: Use regex patterns for advanced searches
4. **Jump to Results**: Double-click a search result to highlight and navigate to that line

## 📁 File Structure

The project is organized as a hybrid application with separate Rust and C# components:

- **Rust (`src/rust`)**: Handles file I/O, memory mapping, and search operations
- **Avalonia UI (`src/avalonia`)**: Provides the user interface and application logic
- **Build Scripts (`scripts/`)**: Automate the build process across platforms

## 💡 Performance Highlights

- **Memory Mapping**: Large files are accessed via memory mapping instead of loading entirely into RAM
- **Efficient Searching**: Rust backend provides fast full-text and regex search capabilities
- **Virtualization**: UI only renders visible log lines for smooth scrolling
- **Caching**: Recently accessed file mapping is cached for improved performance

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
