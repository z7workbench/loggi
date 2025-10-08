#!/bin/bash
# Script to generate macOS application icon from SVG

# This script requires:
# - Inkscape (for SVG to PNG conversion)
# - iconutil (built into macOS for PNG to ICNS conversion)

SVG_FILE="Assets/icon.svg"

if ! command -v inkscape &> /dev/null; then
    echo "Inkscape is required for SVG to PNG conversion"
    echo "Install with: brew install inkscape"
    exit 1
fi

if [ ! -f "$SVG_FILE" ]; then
    echo "SVG file $SVG_FILE not found"
    exit 1
fi

echo "Creating AppIcon.appiconset directory..."
mkdir -p AppIcon.appiconset

echo "Generating PNG files of different sizes..."

# Generate different sizes required for macOS
inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_16x16.png" --export-width=16 --export-height=16
inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_32x32.png" --export-width=32 --export-height=32
inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_64x64.png" --export-width=64 --export-height=64
inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_128x128.png" --export-width=128 --export-height=128
inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_256x256.png" --export-width=256 --export-height=256
inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_512x512.png" --export-width=512 --export-height=512
inkscape "$SVG_FILE" --export-type="png" --export-filename="AppIcon.appiconset/icon_1024x1024.png" --export-width=1024 --export-height=1024

echo "Creating ICNS file..."
iconutil -c icns AppIcon.appiconset -o Assets/icon.icns

echo "Icon generation complete!"
echo "The file Assets/icon.icns has been created and can be used as the macOS app icon."