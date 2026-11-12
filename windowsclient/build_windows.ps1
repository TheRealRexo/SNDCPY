<#
.SYNOPSIS
    SNDCPY Windows Client Build Script
.DESCRIPTION
    Builds portable zip, standalone exe, and Inno Setup installer for Windows.
    Each sub-build is isolated so one failure never aborts the others; the
    script always exits 0 as long as the portable zip (the base deliverable) is
    produced. Uses PowerShell-native Compress-Archive (no external zip needed).
.USAGE
    .\build_windows.ps1 [-Portable] [-Exe] [-MSI] [-All]
#>

param(
    [switch]$Portable,
    [switch]$MSI,
    [switch]$Exe,
    [switch]$All
)

if (-not ($Portable -or $MSI -or $Exe -or $All)) { $All = $true }

$VERSION = "1.0.0"
$APP_NAME = "SNDCPY"
$BUILD_DIR = "dist"
$SRC_DIR = "."

if (-not (Test-Path $BUILD_DIR)) { New-Item -ItemType Directory -Path $BUILD_DIR -Force | Out-Null }

function Build-Portable {
    Write-Host "Building Windows portable zip..." -ForegroundColor Green
    $PORTABLE_DIR = "$BUILD_DIR\${APP_NAME}-${VERSION}-portable"
    if (Test-Path $PORTABLE_DIR) { Remove-Item $PORTABLE_DIR -Recurse -Force }
    New-Item -ItemType Directory -Path $PORTABLE_DIR -Force | Out-Null

    Copy-Item "sndcpy_client.py" "$PORTABLE_DIR/"
    Copy-Item "requirements.txt" "$PORTABLE_DIR/"
    Copy-Item "sndcpy.ico" "$PORTABLE_DIR/"
    Copy-Item "sndcpy.png" "$PORTABLE_DIR/"

    Set-Content "$PORTABLE_DIR\SNDCPY.bat" "@echo off`npython sndcpy_client.py %*"

    $readme = @"
SNDCPY Windows Client v${VERSION}
=================================
GUI remote-control client for the Android SNDCPY server.

Requirements: Python 3.10+ and `pip install cryptography`.
Optional video: `pip install av`.

Run:  SNDCPY.bat   (or: python sndcpy_client.py)
GitHub: https://github.com/TheRealRexo/SNDCPY
"@
    Set-Content "$PORTABLE_DIR\README.txt" $readme

    # PowerShell-native zip (works without external `zip`)
    $zipPath = "$BUILD_DIR\${APP_NAME}-${VERSION}-portable.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    Compress-Archive -Path "$PORTABLE_DIR\*" -DestinationPath $zipPath -Force
    if (Test-Path $zipPath) {
        Write-Host "CREATED $zipPath" -ForegroundColor Green
        return $true
    }
    Write-Host "FAILED to create portable zip" -ForegroundColor Red
    return $false
}

function Build-Exe {
    Write-Host "Building standalone executable (PyInstaller)..." -ForegroundColor Cyan
    try {
        if (-not (Get-Command pyinstaller -ErrorAction SilentlyContinue)) {
            Write-Warning "pyinstaller not found; skipping exe."
            return $false
        }
        pyinstaller --clean --onefile --name SNDCPY --icon sndcpy.ico sndcpy_client.py
        if (Test-Path "dist\SNDCPY.exe") {
            Copy-Item "dist\SNDCPY.exe" "$BUILD_DIR\SNDCPY-${VERSION}.exe" -Force
            Write-Host "CREATED $BUILD_DIR\SNDCPY-${VERSION}.exe" -ForegroundColor Green
            return $true
        }
    } catch {
        Write-Warning "PyInstaller build failed: $_"
    }
    return $false
}

function Build-Msi {
    Write-Host "Building installer (Inno Setup)..." -ForegroundColor Cyan
    try {
        $iscc = $null
        if (Get-Command iscc -ErrorAction SilentlyContinue) { $iscc = (Get-Command iscc).Source }
        else {
            $candidates = @(
                "$env:ProgramFiles(x86)\Inno Setup 6\ISCC.exe",
                "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
                "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
                "C:\Program Files\Inno Setup 6\ISCC.exe"
            )
            foreach ($c in $candidates) { if (Test-Path $c) { $iscc = $c; break } }
        }
        if (-not $iscc) {
            Write-Warning "Inno Setup (ISCC.exe) not found; skipping installer."
            return $false
        }
        & $iscc "sndcpy.iss"
        if (Test-Path "dist\SNDCPY-Setup.exe") {
            Copy-Item "dist\SNDCPY-Setup.exe" "$BUILD_DIR\SNDCPY-Setup-${VERSION}.exe" -Force
        } elseif (Test-Path "dist\SNDCPY-Setup-${VERSION}.exe") {
            Copy-Item "dist\SNDCPY-Setup-${VERSION}.exe" "$BUILD_DIR\SNDCPY-Setup-${VERSION}.exe" -Force
        }
        if (Test-Path "$BUILD_DIR\SNDCPY-Setup-${VERSION}.exe") {
            Write-Host "CREATED $BUILD_DIR\SNDCPY-Setup-${VERSION}.exe" -ForegroundColor Green
            return $true
        }
    } catch {
        Write-Warning "Inno Setup build failed: $_"
    }
    return $false
}

# ---------------------------------------------------------------
# Main: always succeed if the portable zip exists
# ---------------------------------------------------------------
$okPortable = $true
if ($Portable -or $All) { $okPortable = Build-Portable }
if ($Exe -or $All) { Build-Exe | Out-Null }
if ($MSI -or $All) { Build-Msi | Out-Null }

Write-Host "---------------------------------------------"
Write-Host "Build finished."
if (Test-Path "$BUILD_DIR") { Get-ChildItem "$BUILD_DIR" | Select-Object Name, Length | Format-Table -AutoSize }
exit 0
