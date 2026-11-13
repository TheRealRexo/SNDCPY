# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['sndcpy_gui_client.py'],
    pathex=[],
    binaries=[],
    datas=[('sndcpy.icns', '.'), ('sndcpy.png', '.')],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='SNDCPY-macos-arm64',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=['sndcpy.icns'],
)
app = BUNDLE(
    exe,
    name='SNDCPY-macos-arm64.app',
    icon='sndcpy.icns',
    bundle_identifier=None,
)
