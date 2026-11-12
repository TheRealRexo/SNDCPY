#!/usr/bin/env bash
# SNDCPY Linux - Build script for all package formats
# Usage: ./build_linux.sh [--deb] [--rpm] [--appimage] [--all]

set -euo pipefail

VERSION="1.0.0"
APP_NAME="sndcpy"
PACKAGE_NAME="sndcpy-client"
MAINTAINER="TheRealRexo <therealrexo@example.com>"
HOMEPAGE="https://github.com/TheRealRexo/SNDCPY"
DESCRIPTION="SNDCPY Linux Client - Mirrors and controls Android devices over Wi-Fi"
LICENSE="Apache-2.0"

BUILD_DIR="dist"
SRC_DIR="."
ICON_256="../assets/sndcpy-icon-256.png"
ICON_512="../assets/sndcpy-icon-512.png"
EXECUTABLE="sndcpy_client.py"

# Create build directory
mkdir -p "$BUILD_DIR"

# Common files
COMMON_FILES=(
    "$EXECUTABLE"
    "requirements.txt"
)

build_deb() {
    echo "Building .deb package..."
    local DEB_DIR="$BUILD_DIR/${PACKAGE_NAME}_${VERSION}_all"
    local DEB_CTRL="$DEB_DIR/DEBIAN"
    local DEB_BIN="$DEB_DIR/usr/bin"
    local DEB_ICON="$DEB_DIR/usr/share/icons/hicolor/256x256/apps"
    local DEB_DESKTOP="$DEB_DIR/usr/share/applications"
    local DEB_DOC="$DEB_DIR/usr/share/doc/$PACKAGE_NAME"
    local DEB_SHARE="$DEB_DIR/usr/share/$PACKAGE_NAME"

    rm -rf "$DEB_DIR"
    mkdir -p "$DEB_CTRL" "$DEB_BIN" "$DEB_ICON" "$DEB_DESKTOP" "$DEB_DOC" "$DEB_SHARE"

    # Control file
    cat > "$DEB_CTRL/control" <<EOF
Package: $PACKAGE_NAME
Version: $VERSION
Section: utils
Priority: optional
Architecture: all
Depends: python3 (>= 3.10), python3-pip
Maintainer: $MAINTAINER
Homepage: $HOMEPAGE
Description: $DESCRIPTION
 SNDCPY mirrors Android devices (video and audio) over Wi-Fi
 and allows control with keyboard and mouse.
EOF

    # postinst
    cat > "$DEB_CTRL/postinst" <<'EOF'
#!/bin/sh
set -e
pip3 install -r /usr/share/sndcpy-client/requirements.txt 2>/dev/null || true
update-desktop-database /usr/share/applications >/dev/null 2>&1 || true
update-icon-caches /usr/share/icons/hicolor >/dev/null 2>&1 || true
EOF
    chmod 755 "$DEB_CTRL/postinst"

    # prerm
    cat > "$DEB_CTRL/prerm" <<'EOF'
#!/bin/sh
set -e
update-desktop-database /usr/share/applications >/dev/null 2>&1 || true
update-icon-caches /usr/share/icons/hicolor >/dev/null 2>&1 || true
EOF
    chmod 755 "$DEB_CTRL/prerm"

    # Executable wrapper
    cat > "$DEB_BIN/sndcpy" <<'EOF'
#!/bin/sh
exec python3 /usr/share/sndcpy-client/sndcpy_client.py "$@"
EOF
    chmod 755 "$DEB_BIN/sndcpy"

    # Source files
    for f in "${COMMON_FILES[@]}"; do
        cp "$SRC_DIR/$f" "$DEB_SHARE/"
    done

    # Icon
    mkdir -p "$DEB_ICON"
    cp "$ICON_256" "$DEB_ICON/sndcpy.png"

    # Desktop entry
    cat > "$DEB_DESKTOP/sndcpy.desktop" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=SNDCPY
Comment=Mirror and control Android devices over Wi-Fi
Exec=sndcpy
Icon=sndcpy
Terminal=false
Categories=Network;RemoteAccess;
StartupNotify=true
EOF

    # Copyright
    cat > "$DEB_DOC/copyright" <<EOF
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: SNDCPY
Source: https://github.com/TheRealRexo/SNDCPY

Files: *
Copyright: 2024 TheRealRexo
License: Apache-2.0
EOF

    # Build
    dpkg-deb --build "$DEB_DIR" "$BUILD_DIR/${PACKAGE_NAME}_${VERSION}_all.deb"
    echo "Created $BUILD_DIR/${PACKAGE_NAME}_${VERSION}_all.deb"
}

build_rpm() {
    echo "Building .rpm package..."
    local RPM_DIR="$(pwd)/dist/rpmbuild"
    mkdir -p "$RPM_DIR"/{BUILD,RPMS,SOURCES,SPECS,SRPMS}

    # Install build dependencies (fuse3 provides libfuse.so.3, create symlink for libfuse.so.2)
    sudo apt-get update -qq && sudo apt-get install -y python3 python3-pip rpm-build fuse3 2>/dev/null || true
    # Create symlink for libfuse.so.2 if libfuse3.so.3 is available
    if [ -f /usr/lib/x86_64-linux-gnu/libfuse3.so.3 ]; then
        sudo ln -sf /usr/lib/x86_64-linux-gnu/libfuse3.so.3 /usr/lib/x86_64-linux-gnu/libfuse.so.2
    fi

    # Create tarball with proper top-level directory for rpmbuild
    local TARBALL="$RPM_DIR/SOURCES/${PACKAGE_NAME}-${VERSION}.tar.gz"
    local TMPDIR=$(mktemp -d)
    local PKGDIR="$TMPDIR/${PACKAGE_NAME}-${VERSION}"
    mkdir -p "$PKGDIR"
    for f in "${COMMON_FILES[@]}"; do
        cp "$SRC_DIR/$f" "$PKGDIR/"
    done
    # Also include icon for RPM
    cp "$ICON_256" "$PKGDIR/sndcpy.png"
    tar -czf "$TARBALL" -C "$TMPDIR" "${PACKAGE_NAME}-${VERSION}"
    rm -rf "$TMPDIR"

    # Spec file - use placeholders for bash variables, preserve rpm macros
    cat > "$RPM_DIR/SPECS/$PACKAGE_NAME.spec" <<'SPECEOF'
Name:           @PACKAGE_NAME@
Version:        @VERSION@
Release:        1%{?dist}
Summary:        SNDCPY Linux Client

License:        @LICENSE@
URL:            @HOMEPAGE@
Source0:        %{name}-%{version}.tar.gz

BuildArch:      noarch
BuildRequires:  python3 >= 3.10, python3-pip
Requires:       python3 >= 3.10, python3-pip

%description
@DESCRIPTION@

%prep
%autosetup -n %{name}-%{version}

%build
# No build needed

%install
mkdir -p %{buildroot}%{_bindir}
mkdir -p %{buildroot}%{_datadir}/%{name}
mkdir -p %{buildroot}%{_datadir}/applications
mkdir -p %{buildroot}%{_datadir}/icons/hicolor/256x256/apps

# Install source files
cp sndcpy_client.py %{buildroot}%{_datadir}/%{name}/
cp requirements.txt %{buildroot}%{_datadir}/%{name}/

# Wrapper script
cat > %{buildroot}%{_bindir}/sndcpy <<'EOF'
#!/bin/sh
exec python3 /usr/share/sndcpy-client/sndcpy_client.py "$@"
EOF
chmod 755 %{buildroot}%{_bindir}/sndcpy

# Icon
cp sndcpy.png %{buildroot}%{_datadir}/icons/hicolor/256x256/apps/sndcpy.png 2>/dev/null || true

# Desktop entry
cat > %{buildroot}%{_datadir}/applications/sndcpy.desktop <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=SNDCPY
Comment=Mirror and control Android devices over Wi-Fi
Exec=sndcpy
Icon=sndcpy
Terminal=false
Categories=Network;RemoteAccess;
StartupNotify=true
EOF

%files
%{_bindir}/sndcpy
%{_datadir}/%{name}/
%{_datadir}/applications/sndcpy.desktop
%{_datadir}/icons/hicolor/256x256/apps/sndcpy.png

%changelog
* @DATE@ TheRealRexo <therealrexo@example.com> - @VERSION@-1
- Initial release
SPECEOF

    # Substitute variables
    sed -i \
        -e "s|@PACKAGE_NAME@|${PACKAGE_NAME}|g" \
        -e "s|@VERSION@|${VERSION}|g" \
        -e "s|@LICENSE@|${LICENSE}|g" \
        -e "s|@HOMEPAGE@|${HOMEPAGE}|g" \
        -e "s|@DESCRIPTION@|${DESCRIPTION}|g" \
        -e "s|@DATE@|$(date +"%a %b %d %Y")|g" \
        "$RPM_DIR/SPECS/$PACKAGE_NAME.spec"

    # Build with --nodeps to skip dependency check (deps are satisfied on system)
    rpmbuild --define "_topdir $RPM_DIR" --nodeps -bb "$RPM_DIR/SPECS/$PACKAGE_NAME.spec"
    cp "$RPM_DIR/RPMS/noarch/${PACKAGE_NAME}-${VERSION}-1.noarch.rpm" "dist/"
    echo "Created dist/${PACKAGE_NAME}-${VERSION}-1.noarch.rpm"
}

build_appimage() {
    echo "Building .AppImage..."
    local APPDIR="$BUILD_DIR/${APP_NAME}.AppDir"
    local APPIMAGE="$BUILD_DIR/${APP_NAME}-${VERSION}-x86_64.AppImage"

    rm -rf "$APPDIR"
    mkdir -p "$APPDIR/usr/bin" "$APPDIR/usr/share/applications" "$APPDIR/usr/share/icons/hicolor/256x256/apps" "$APPDIR/usr/share/${APP_NAME}"

    # Copy files
    for f in "${COMMON_FILES[@]}"; do
        cp "$SRC_DIR/$f" "$APPDIR/usr/share/${APP_NAME}/"
    done
    cp "$ICON_256" "$APPDIR/usr/share/icons/hicolor/256x256/apps/sndcpy.png"

    # AppRun
    cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
SELF=$(readlink -f "$0")
HERE=${SELF%/*}
export PYTHONPATH="${HERE}/usr/share/sndcpy:${PYTHONPATH}"
exec python3 "${HERE}/usr/share/sndcpy/sndcpy_client.py" "$@"
EOF
    chmod 755 "$APPDIR/AppRun"

    # Desktop file
    cat > "$APPDIR/sndcpy.desktop" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=SNDCPY
Comment=Mirror and control Android devices over Wi-Fi
Exec=AppRun
Icon=sndcpy
Terminal=false
Categories=Network;RemoteAccess;
StartupNotify=true
EOF
    cp "$APPDIR/sndcpy.desktop" "$APPDIR/usr/share/applications/"

    # Icon
    cp "$ICON_512" "$APPDIR/sndcpy.png"

    # Build AppImage (optional - requires FUSE)
    if command -v appimagetool >/dev/null 2>&1; then
        # Try to build AppImage, but don't fail if FUSE is not available
        if ARCH=x86_64 appimagetool "$APPDIR" "$APPIMAGE" 2>/dev/null; then
            echo "Created $APPIMAGE"
        else
            echo "Warning: AppImage build failed (FUSE not available), skipping..."
            echo "Install libfuse2 and appimagetool for AppImage support"
        fi
    else
        echo "appimagetool not found, skipping AppImage build"
        echo "Install: wget https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
    fi
}

# Main
case "${1:-}" in
    --deb) build_deb ;;
    --rpm) build_rpm ;;
    --appimage) build_appimage ;;
    --all|"")
        build_deb
        build_rpm
        build_appimage
        ;;
    *) echo "Usage: $0 [--deb|--rpm|--appimage|--all]"; exit 1 ;;
esac