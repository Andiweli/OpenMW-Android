param(
    [ValidateRange(1, 32)]
    [int]$Jobs = 6
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Read-Lf([string]$Path) {
    return ([IO.File]::ReadAllText($Path) -replace "`r`n", "`n")
}

function Convert-WindowsPathToWsl([string]$WindowsPath) {
    if ($WindowsPath -notmatch '^([A-Za-z]):(?:\\(.*))?$') {
        throw "Unsupported project path for WSL: $WindowsPath"
    }

    $DriveLetter = $Matches[1].ToLowerInvariant()
    $RelativePart = $Matches[2]
    if ([string]::IsNullOrWhiteSpace($RelativePart)) {
        return "/mnt/$DriveLetter"
    }

    return "/mnt/$DriveLetter/" + (($RelativePart -replace '\\', '/').TrimStart('/'))
}

$Patcher = Join-Path $ProjectRoot 'buildscripts\patches\gl4es\apply-powervr-packed-depth-stencil.py'
$CMakeFile = Join-Path $ProjectRoot 'buildscripts\CMakeLists.txt'
$SuperBuild = Join-Path $ProjectRoot 'buildscripts\build\arm64'
$Gl4esFramebuffers = Join-Path $SuperBuild 'gl4es-prefix\src\gl4es\src\gl\framebuffers.c'
$Wrapper = Join-Path $SuperBuild 'command_wrapper.sh'
$JniLib = Join-Path $ProjectRoot 'app\src\main\jniLibs\arm64-v8a\libGL.so'

foreach ($Required in @($Patcher, $CMakeFile)) {
    if (-not (Test-Path -LiteralPath $Required)) {
        throw "Patch 50 requires the updated project files from GitHub. Missing: $Required"
    }
}

# Future clean native builds apply the fix through the gl4es PATCH_COMMAND.
$CMake = Read-Lf $CMakeFile
if (-not $CMake.Contains('patches/gl4es/apply-powervr-packed-depth-stencil.py <SOURCE_DIR>')) {
    throw 'Patch 50: buildscripts\CMakeLists.txt does not schedule the gl4es PowerVR fix. Update the project from GitHub first.'
}

if (-not (Test-Path -LiteralPath $Gl4esFramebuffers) -or -not (Test-Path -LiteralPath $Wrapper)) {
    throw 'Patch 50: the native build tree (buildscripts\build\arm64) is missing. A full native build applies this fix automatically.'
}

if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw 'Patch 50 requires WSL for the Android native build.'
}

$WslProject = Convert-WindowsPathToWsl $ProjectRoot
$WindowsHelper = Join-Path $ProjectRoot 'tools\.openmw-051-patch50.sh'
$WslHelper = "$WslProject/tools/.openmw-051-patch50.sh"

$ShellScript = @'
#!/usr/bin/env bash
set -euo pipefail

PROJECT="${OPENMW_PATCH50_PROJECT:?OPENMW_PATCH50_PROJECT is required}"
JOBS="${OPENMW_PATCH50_JOBS:?OPENMW_PATCH50_JOBS is required}"

SUPER="$PROJECT/buildscripts/build/arm64"
SOURCE="$SUPER/gl4es-prefix/src/gl4es"
BUILD="$SUPER/gl4es-prefix/src/gl4es-build"
STAMPS="$SUPER/gl4es-prefix/src/gl4es-stamp"
PATCHER="$PROJECT/buildscripts/patches/gl4es/apply-powervr-packed-depth-stencil.py"
FRAMEBUFFERS="$SOURCE/src/gl/framebuffers.c"
PREFIX_LIB="$PROJECT/buildscripts/prefix/arm64/lib/libGL.so"
UNSTRIPPED="$BUILD/obj/local/arm64-v8a/libGL.so"
JNI="$PROJECT/app/src/main/jniLibs/arm64-v8a/libGL.so"
SYMBOLS="$PROJECT/buildscripts/symbols/arm64-v8a/libGL.so"

[[ -f "$FRAMEBUFFERS" && -f "$SUPER/command_wrapper.sh" && -f "$SUPER/Makefile" ]] || exit 71

python3 "$PATCHER" "$SOURCE"

grep -Fq 'OPENMW_ANDROID_GL4ES_POWERVR_PACKED_DEPTH_STENCIL' "$FRAMEBUFFERS" || exit 72
grep -Fq 'hardext.depthstencil==0 && hardext.glsl300es==0' "$FRAMEBUFFERS" || exit 73

touch "$FRAMEBUFFERS"

echo "Patch 50 source verification: PASS"
echo "Rebuilding gl4es (libGL.so) only..."

# Re-run only the gl4es build/install steps. libopenmw.so and all other
# third-party libraries stay untouched.
rm -f "$STAMPS/gl4es-build" "$STAMPS/gl4es-install" "$STAMPS/gl4es-done"

cd "$SUPER"
# Same compiler/linker environment as buildscripts/build.sh.
source ./command_wrapper.sh true
make -j"$JOBS" gl4es

[[ -f "$PREFIX_LIB" && "$PREFIX_LIB" -nt "$FRAMEBUFFERS" ]] || exit 74

mkdir -p "$(dirname "$JNI")" "$(dirname "$SYMBOLS")"
cp -f "$PREFIX_LIB" "$JNI"
if [[ -f "$UNSTRIPPED" ]]; then
    cp -f "$UNSTRIPPED" "$SYMBOLS"
fi

echo "Patch 50 gl4es rebuild: PASS"
'@

[IO.File]::WriteAllText(
    $WindowsHelper,
    ($ShellScript -replace "`r`n", "`n"),
    [Text.UTF8Encoding]::new($false)
)

try {
    & wsl.exe env "OPENMW_PATCH50_PROJECT=$WslProject" "OPENMW_PATCH50_JOBS=$Jobs" bash $WslHelper
    if ($LASTEXITCODE -ne 0) {
        throw "Patch 50 gl4es rebuild failed with exit code $LASTEXITCODE"
    }
}
finally {
    Remove-Item -LiteralPath $WindowsHelper -Force -ErrorAction SilentlyContinue
}

if (-not (Test-Path -LiteralPath $JniLib)) {
    throw "Patch 50 post-build verification failed: missing $JniLib"
}

$ActualSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $JniLib).Hash.ToLowerInvariant()

Write-Host ''
Write-Host 'OpenMW 0.51 Patch 50 gl4es PowerVR depth/stencil fix: PASS' -ForegroundColor Green
Write-Host 'Scope: gl4es packed depth/stencil renderbuffers (first-person hands/weapons, water RTT)'
Write-Host 'Adreno/Mali: unchanged'
Write-Host 'libopenmw.so: unchanged'
Write-Host "libGL.so SHA-256: $ActualSha"
Write-Host ''
Write-Host 'Build the APK normally and test first-person weapons on a PowerVR device.'
