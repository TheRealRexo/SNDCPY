# -*- mode: python ; coding: utf-8 -*-
# SNDCPY Linux GUI Client - PyInstaller spec (onedir)

block_cipher = None

a = Analysis(
    ['sndcpy_gui_client.py'],
    pathex=[],
    binaries=[],
    datas=[('sndcpy.png', '.'), ('requirements.txt', '.')],
    hiddenimports=['cryptography', 'cryptography.hazmat.primitives.asymmetric.ec',
                   'cryptography.hazmat.primitives.kdf.hkdf',
                   'cryptography.hazmat.primitives.ciphers.aead',
                   'cryptography.hazmat.primitives.serialization'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['matplotlib', 'numpy', 'pandas', 'PIL', 'PyQt6.QtWebEngine',
              'PyQt6.QtWebEngineCore', 'PyQt6.QtWebEngineWidgets',
              'PyQt6.Qt3D', 'PyQt6.QtBluetooth', 'PyQt6.QtCharts',
              'PyQt6.QtDataVisualization', 'PyQt6.QtDesigner',
              'PyQt6.QtHelp', 'PyQt6.QtLocation', 'PyQt6.QtLocationWidgets',
              'PyQt6.QtMultimedia', 'PyQt6.QtMultimediaWidgets',
              'PyQt6.QtNetwork', 'PyQt6.QtNfc', 'PyQt6.QtOpenGL',
              'PyQt6.QtOpenGLWidgets', 'PyQt6.QtPdf',
              'PyQt6.QtPdfWidgets', 'PyQt6.QtPositioning',
              'PyQt6.QtQuick', 'PyQt6.QtQuickWidgets',
              'PyQt6.QtRemoteObjects', 'PyQt6.QtSensors',
              'PyQt6.QtSerialBus', 'PyQt6.QtSerialPort',
              'PyQt6.QtSql', 'PyQt6.QtSvg',
              'PyQt6.QtSvgWidgets', 'PyQt6.QtTest',
              'PyQt6.QtTextToSpeech', 'PyQt6.QtUiTools',
              'PyQt6.QtWebChannel', 'PyQt6.QtWebSockets',
              'PyQt6.QtX11Extras',
              'PyQt6.QtXml', 'PyQt6.QtXmlPatterns'],
    noarchive=False,
)

pyz = PYZ(a.pure, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='SNDCPY',
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
    icon='sndcpy.png',
)