# Build script for loggi Rust core library (Windows)

Write-Output "Building loggi Rust core library..."

# Navigate to the Rust directory
Push-Location (Join-Path $PSScriptRoot "..\src\rust")

# Build the Rust library
cargo build --release

# The output library on Windows will be a DLL
$libName = "loggi_core.dll"

# Copy the built library to the Avalonia directory
$sourcePath = "target\release\$libName"
$destPath = "..\avalonia\$libName"

if (Test-Path $sourcePath) {
    Copy-Item -Path $sourcePath -Destination $destPath
    Write-Output "Rust library built successfully: $libName"
} else {
    Write-Error "Rust library was not built successfully. Check if the file exists: $sourcePath"
}

# Return to the original directory
Pop-Location