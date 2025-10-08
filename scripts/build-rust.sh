#!/bin/bash
# Build script for loggi Rust core library

set -e

echo "Building loggi Rust core library..."

# Determine the OS
OS=$(uname -s | tr '[:upper:]' '[:lower:]')

# Navigate to the Rust directory
cd "$(dirname "$0")/../src/rust"

# Build the Rust library
cargo build --release

# Determine the output library name based on the OS
if [[ "$OS" == *"linux"* ]]; then
    LIB_NAME="libloggi_core.so"
elif [[ "$OS" == *"darwin"* ]]; then
    LIB_NAME="libloggi_core.dylib"
else
    LIB_NAME="libloggi_core.so"  # Default fallback
fi

# Copy the built library to the parent directory for distribution
cp "target/release/$LIB_NAME" "../avalonia/"

echo "Rust library built successfully: $LIB_NAME"