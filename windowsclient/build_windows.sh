#!/usr/bin/env bash
# SNDCPY Windows - Build script for portable zip + MSI installer
# Usage: ./build_windows.sh [--portable] [--msi] [--all]

set -euo pipefail

VERSION="1.0.0"
APP_NAME="SNDCPY"
BUILD_DIR="dist"
SRC_DIR="."

mkdir -p "$BUILD_DIR"

build_portable() {
    echo "Building Windows portable zip..."
    local PORTABLE_DIR="$BUILD_DIR/${APP_NAME}-${VERSION}-portable"
    rm -rf "$PORTABLE_DIR"
    mkdir -p "$PORTABLE_DIR"

    # Copy source files
    cp sndcpy_client.py "$PORTABLE_DIR/"
    cp requirements.txt "$PORTABLE_DIR/"
    cp sndcpy.ico "$PORTABLE_DIR/"
    cp sndcpy.png "$PORTABLE_DIR/"

    # Create launcher batch file
    cat > "$PORTABLE_DIR/SNDCPY.bat" <<'EOF'
@echo off
python sndcpy_client.py %*
EOF

    # Create README
    cat > "$PORTABLE_DIR/README.txt" <<EOF
SNDCPY Windows Client v${VERSION}
=================================

Portable version - no installation required.

Requirements:
- Python 3.10+
- Run: pip install -r requirements.txt

Usage:
    SNDCPY.bat --host 192.168.1.42

Or directly with Python:
    python sndcpy_client.py --host 192.168.1.42

First connection will require pairing code shown on Android device.

GitHub: https://github.com/TheRealRexo/SNDCPY
EOF

    # Create zip
    cd "$BUILD_DIR"
    zip -r "${APP_NAME}-${VERSION}-portable.zip" "${APP_NAME}-${VERSION}-portable"
    cd -
    echo "Created $BUILD_DIR/${APP_NAME}-${VERSION}-portable.zip"
}

build_exe() {
    echo "Building standalone executable with PyInstaller..."
    if ! command -v pyinstaller >/dev/null 2>&1; then
        echo "PyInstaller not found. Install: pip install pyinstaller"
        return 1
    fi
    pyinstaller --clean SNDCPY.spec
    cp dist/SNDCPY.exe "$BUILD_DIR/SNDCPY-${VERSION}.exe"
    echo "Created $BUILD_DIR/SNDCPY-${VERSION}.exe"
}

build_msi() {
    echo "Building MSI installer with Inno Setup..."
    if ! command -v iscc >/dev/null 2>&1; then
        echo "Inno Setup (iscc) not found."
        echo "Install: https://jrsoftware.org/isdl.php"
        return 1
    fi
    iscc sndcpy.iss
    cp dist/SNDCPY-Setup-1.0.0.exe "$BUILD_DIR/SNDCPY-Setup-${VERSION}.exe"
    echo "Created $BUILD_DIR/SNDCPY-Setup-${VERSION}.exe"
}

# Main
case "${1:-}" in
    --portable) build_portable ;;
    --exe) build_exe ;;
    --msi) build_msi ;;
    --all|"")
        build_portable
        build_exe
        build_msi
        ;;
    *) echo "Usage: $0 [--portable|--exe|--msi|--all]"; exit 1 ;;
esac