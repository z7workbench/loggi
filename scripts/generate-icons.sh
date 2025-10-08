#!/bin/bash
# Script to generate application icons for different platforms from SVG

# This script requires:
# - Inkscape (for SVG to PNG conversion)
# - iconutil (built into macOS for PNG to ICNS conversion)

SVG_FILE="src/avalonia/Assets/icon.svg"

if [ ! -f "$SVG_FILE" ]; then
    echo "SVG file $SVG_FILE not found"
    exit 1
fi

# Check if inkscape is available
if ! command -v inkscape &> /dev/null; then
    echo "Inkscape is required for SVG to PNG conversion"
    echo "Install with: brew install inkscape"
    echo "Skipping icon generation."
    exit 0
fi

echo "Creating Assets directory if it doesn't exist..."
mkdir -p src/avalonia/Assets

# Generate PNG files of different sizes for ICO
echo "Generating PNG files for ICO..."

# Create temporary directory for ICO generation
mkdir -p temp_ico
inkscape "$SVG_FILE" --export-type="png" --export-filename="temp_ico/icon_16.png" --export-width=16 --export-height=16
inkscape "$SVG_FILE" --export-type="png" --export-filename="temp_ico/icon_32.png" --export-width=32 --export-height=32
inkscape "$SVG_FILE" --export-type="png" --export-filename="temp_ico/icon_48.png" --export-width=48 --export-height=48
inkscape "$SVG_FILE" --export-type="png" --export-filename="temp_ico/icon_256.png" --export-width=256 --export-height=256

# Check if ImageMagick's convert is available to create ICO
if command -v convert &> /dev/null; then
    echo "Creating icon.ico using ImageMagick..."
    convert temp_ico/icon_16.png temp_ico/icon_32.png temp_ico/icon_48.png temp_ico/icon_256.png src/avalonia/Assets/icon.ico
else
    echo "ImageMagick not found, creating placeholder ICO file"
    # Create a placeholder SVG file that will be replaced during proper build
    echo "This is a placeholder. Use ImageMagick to convert to proper ICO format." > src/avalonia/Assets/icon.ico
fi

# For macOS ICNS
if [ "$(uname)" = "Darwin" ]; then
    if command -v iconutil &> /dev/null; then
        echo "Creating AppIcon.appiconset directory..."
        mkdir -p AppIcon.appiconset
        
        # Generate different sizes required for macOS
        inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_16x16.png" --export-width=16 --export-height=16
        inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_32x32.png" --export-width=32 --export-height=32
        inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_64x64.png" --export-width=64 --export-height=64
        inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_128x128.png" --export-width=128 --export-height=128
        inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_256x256.png" --export-width=256 --export-height=256
        inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_512x512.png" --export-width=512 --export-height=512
        inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_1024x1024.png" --export-width=1024 --export-height=1024
        
        echo "Creating icon.icns..."
        iconutil -c icns AppIcon.appiconset -o src/avalonia/Assets/icon.icns
        
        # Clean up iconset directory
        rm -rf AppIcon.appiconset
    else
        echo "iconutil not found on macOS, creating placeholder ICNS file"
        echo "This is a placeholder. Use iconutil to convert to proper ICNS format." > src/avalonia/Assets/icon.icns
    fi
fi

# Clean up temporary directory
rm -rf temp_ico

echo "Icon generation complete!"