param(
    [ValidateRange(1, 2)]
    [int]$Jobs = 1
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

Write-Host 'OpenMW Patch 42 runner: 2.4-lowmem' -ForegroundColor Cyan
Write-Host "Native build policy: arm64 / Release / O3 / NO LTO / $Jobs job(s)" -ForegroundColor Yellow
Write-Host 'Downloads and the pinned NDK are retained; only generated arm64 LTO output is rebuilt.' -ForegroundColor DarkGray

function Read-Lf([string]$Path) {
    return ([IO.File]::ReadAllText($Path) -replace "`r`n", "`n")
}

function Write-Utf8Lf([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText(
        $Path,
        (($Text -replace "`r`n", "`n")),
        [Text.UTF8Encoding]::new($false)
    )
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

$BuildSh = Join-Path $ProjectRoot 'buildscripts\build.sh'
$CMakeFile = Join-Path $ProjectRoot 'buildscripts\CMakeLists.txt'
$RuntimePatcher = Join-Path $ProjectRoot 'buildscripts\patches\openmw051-final\apply-android-runtime-baseline.py'
$Shader = Join-Path $ProjectRoot 'app\src\main\assets\android_omwfx\wetworld_android_051_weather.omwfx'
$MainActivity = Join-Path $ProjectRoot 'app\src\main\java\ui\activity\MainActivity.kt'
$GradleFile = Join-Path $ProjectRoot 'app\build.gradle'
$JniDir = Join-Path $ProjectRoot 'app\src\main\jniLibs\arm64-v8a'
$JniLib = Join-Path $JniDir 'libopenmw.so'
$Patch42Sha = Join-Path $ProjectRoot 'buildscripts\openmw-051-patch42-libopenmw.sha256'
$SourceRoot = Join-Path $ProjectRoot 'buildscripts\build\arm64\openmw-prefix\src\openmw'
$BuildRoot = Join-Path $ProjectRoot 'buildscripts\build\arm64\openmw-prefix\src\openmw-build'

foreach ($Required in @($BuildSh, $CMakeFile, $RuntimePatcher, $Shader, $MainActivity, $GradleFile)) {
    if (-not (Test-Path -LiteralPath $Required)) {
        throw "Patch 42 v2.4 requires the completed 0.51 Final project with the v2.3 payload already installed. Missing: $Required"
    }
}

if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw 'WSL is required for the OpenMW Android native build.'
}

$RuntimeText = Read-Lf $RuntimePatcher
if (-not $RuntimeText.Contains('OPENMW_ANDROID_051_TRANSPARENT_DEPTH_DIRECT') -or
    -not $RuntimeText.Contains('alpha-depth postpass')) {
    throw 'Patch 42 v2.4: runtime patcher is not the Patch-42 alpha-depth version. Re-extract the v2.3 payload first.'
}

$ShaderText = Read-Lf $Shader
if (-not $ShaderText.Contains('5.3-051-ground-puddles-alpha-depth') -or
    -not $ShaderText.Contains('reconstructedSurface051') -or
    -not $ShaderText.Contains('maxSlope')) {
    throw 'Patch 42 v2.4: WetWorld 5.3 payload is incomplete. Re-extract the v2.3 payload first.'
}

$MainText = Read-Lf $MainActivity
if (-not $MainText.Contains('5.3-051-ground-puddles-alpha-depth')) {
    throw 'Patch 42 v2.4: MainActivity does not contain the WetWorld 5.3 payload guard.'
}

$GradleText = Read-Lf $GradleFile
if (-not $GradleText.Contains('openmw-051-patch42-libopenmw.sha256')) {
    throw 'Patch 42 v2.4: app/build.gradle does not contain the Patch-42 native SHA guard.'
}

# v2.3 bootstrapped with full LTO. A failed LTO tree must not be resumed as a
# non-LTO build because static dependency archives can already contain LLVM
# bitcode. We therefore rebuild only the generated arm64 build/prefix trees.
# downloads/ and toolchain/ are intentionally retained.
$BackupRoot = Join-Path $ProjectRoot 'tools\.patch42-v24-backup'
$BackupJni = Join-Path $BackupRoot 'arm64-v8a'
if (Test-Path -LiteralPath $BackupRoot) {
    Remove-Item -LiteralPath $BackupRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $BackupRoot -Force | Out-Null

$HadJniDir = Test-Path -LiteralPath $JniDir
if ($HadJniDir) {
    Copy-Item -LiteralPath $JniDir -Destination $BackupJni -Recurse -Force
}

# The CaveBros/OpenMW build has two hard-coded -j4 entries (Boost and ndk-build)
# that bypass build.sh --jobs. Temporarily cap those to the same low-memory job
# count. The source CMakeLists.txt is restored byte-for-byte in finally.
$CMakeOriginal = [IO.File]::ReadAllText($CMakeFile)
$CMakeLf = $CMakeOriginal -replace "`r`n", "`n"
$HardcodedJobPattern = '(?m)^([ \t]*)-j4[ \t]*$'
$HardcodedJobMatches = [regex]::Matches($CMakeLf, $HardcodedJobPattern)
if ($HardcodedJobMatches.Count -ne 2) {
    throw "Patch 42 v2.4 expected exactly two hard-coded -j4 entries in buildscripts/CMakeLists.txt, found $($HardcodedJobMatches.Count). Refusing to modify an unexpected build script."
}

$CMakeLowMemory = [regex]::Replace(
    $CMakeLf,
    $HardcodedJobPattern,
    [System.Text.RegularExpressions.MatchEvaluator]{
        param($Match)
        return $Match.Groups[1].Value + "-j$Jobs"
    }
)

$WslProject = Convert-WindowsPathToWsl $ProjectRoot
$WindowsHelper = Join-Path $ProjectRoot 'tools\.openmw-051-patch42-v24-lowmem.sh'
$WslHelper = "$WslProject/tools/.openmw-051-patch42-v24-lowmem.sh"

$ShellScript = @'
#!/usr/bin/env bash
set -euo pipefail

PROJECT="${OPENMW_PATCH42_PROJECT:?OPENMW_PATCH42_PROJECT is required}"
JOBS="${OPENMW_PATCH42_JOBS:?OPENMW_PATCH42_JOBS is required}"
BS="$PROJECT/buildscripts"
APP="$PROJECT/app"
SOURCE="$BS/build/arm64/openmw-prefix/src/openmw"
BUILD="$BS/build/arm64/openmw-prefix/src/openmw-build"
JNI="$APP/src/main/jniLibs/arm64-v8a/libopenmw.so"
SYMBOLS="$BS/symbols/arm64-v8a/libopenmw.so"
PATCHER="$BS/patches/openmw051-final/apply-android-runtime-baseline.py"

printf '\n============================================================\n'
printf 'Patch 42 v2.4 LOW-MEMORY native rebuild\n'
printf '  architecture : arm64-v8a\n'
printf '  build type   : Release / O3\n'
printf '  LTO          : OFF\n'
printf '  parallel jobs: %s\n' "$JOBS"
printf '  retained     : downloads + pinned NDK/toolchain\n'
printf '============================================================\n\n'

# Never mix the aborted v2.3 full-LTO objects/static archives with this build.
# Keep downloaded source archives and the NDK so this is not a from-zero download.
rm -rf "$BS/build/arm64" "$BS/prefix/arm64" "$BS/symbols/arm64-v8a"

cd "$BS"

# IMPORTANT: deliberately no --lto here.
# --jobs also limits the top-level ExternalProject build; CMakeLists.txt is
# temporarily patched by the PowerShell wrapper so Boost/ndk-build obey it too.
./build.sh --arch arm64 --release --no-resources --jobs "$JOBS"

[[ -f "$SOURCE/CMakeLists.txt" ]] || { echo 'ERROR: OpenMW source tree missing after build.' >&2; exit 71; }
[[ -f "$BUILD/CMakeCache.txt" ]] || { echo 'ERROR: OpenMW build tree missing after build.' >&2; exit 72; }
[[ -s "$JNI" ]] || { echo 'ERROR: packaged libopenmw.so missing after build.' >&2; exit 73; }
[[ -s "$SYMBOLS" ]] || { echo 'ERROR: symbol libopenmw.so missing after build.' >&2; exit 74; }

grep -Fq 'OPENMW_ANDROID_051_TRANSPARENT_DEPTH_DIRECT' "$SOURCE/apps/openmw/mwrender/transparentpass.cpp" || {
    echo 'ERROR: transparentpass.cpp was built without Patch-42 direct alpha-depth.' >&2
    exit 75
}

grep -aFq 'OpenMW 0.51 Android renderer:' "$JNI" || {
    echo 'ERROR: OpenMW Android renderer marker missing from final binary.' >&2
    exit 76
}

grep -aFq 'alpha-depth postpass' "$JNI" || {
    echo 'ERROR: Patch-42 alpha-depth runtime marker missing from final binary.' >&2
    exit 77
}

if grep -Fq -- '-flto' "$BS/build/arm64/command_wrapper.sh"; then
    echo 'ERROR: low-memory build unexpectedly contains -flto.' >&2
    exit 78
fi

JNI_SIZE=$(stat -c %s "$JNI")
SYMBOL_SIZE=$(stat -c %s "$SYMBOLS")
if [ "$JNI_SIZE" -ge "$SYMBOL_SIZE" ]; then
    echo "ERROR: packaged libopenmw.so is not stripped (jni=$JNI_SIZE symbols=$SYMBOL_SIZE)." >&2
    exit 79
fi

printf '\nPatch 42 v2.4 native verification: PASS\n'
printf 'Packaged lib: %s bytes\n' "$JNI_SIZE"
printf 'Symbol lib:   %s bytes\n' "$SYMBOL_SIZE"
'@

$Succeeded = $false
try {
    Write-Utf8Lf $CMakeFile $CMakeLowMemory
    Write-Utf8Lf $WindowsHelper $ShellScript

    Write-Host ''
    Write-Host 'Patch 42 v2.4: discarding the aborted arm64/LTO build output only.' -ForegroundColor Yellow
    Write-Host 'The NDK/toolchain and downloaded dependency archives are NOT deleted.' -ForegroundColor DarkGray
    Write-Host 'Building with one job and without LTO to avoid the previous ~24 GB memory spike.' -ForegroundColor Yellow

    & wsl.exe env "OPENMW_PATCH42_PROJECT=$WslProject" "OPENMW_PATCH42_JOBS=$Jobs" bash $WslHelper
    if ($LASTEXITCODE -ne 0) {
        throw "Patch 42 v2.4 low-memory native build failed with exit code $LASTEXITCODE"
    }

    if (-not (Test-Path -LiteralPath $JniLib)) {
        throw "Patch 42 v2.4 post-build verification failed: missing $JniLib"
    }

    $ActualSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $JniLib).Hash.ToLowerInvariant()
    Write-Utf8Lf $Patch42Sha "$ActualSha  $JniLib`n"

    $Succeeded = $true

    Write-Host ''
    Write-Host 'OpenMW 0.51 Patch 42 v2.4 LOW-MEMORY: PASS' -ForegroundColor Green
    Write-Host 'LTO:             OFF'
    Write-Host "Parallel jobs:   $Jobs"
    Write-Host 'WetWorld 5.3:    VERIFIED'
    Write-Host 'Alpha-depth:     VERIFIED in source and final libopenmw.so'
    Write-Host "JNI SHA-256:     $ActualSha"
    Write-Host ''
    Write-Host 'Now build the APK/AAB normally in Android Studio.' -ForegroundColor Cyan
}
catch {
    Write-Host ''
    Write-Host 'Patch 42 v2.4 failed. Restoring the previous arm64 JNI payload so the project remains buildable.' -ForegroundColor Red

    if (Test-Path -LiteralPath $JniDir) {
        Remove-Item -LiteralPath $JniDir -Recurse -Force
    }
    if ($HadJniDir -and (Test-Path -LiteralPath $BackupJni)) {
        Copy-Item -LiteralPath $BackupJni -Destination $JniDir -Recurse -Force
    }

    throw
}
finally {
    # Restore the original build definition even if WSL/CMake/clang fails.
    [IO.File]::WriteAllText($CMakeFile, $CMakeOriginal, [Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $WindowsHelper -Force -ErrorAction SilentlyContinue

    if ($Succeeded) {
        Remove-Item -LiteralPath $BackupRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
