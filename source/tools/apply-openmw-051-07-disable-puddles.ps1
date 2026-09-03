$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Shader = Join-Path $ProjectRoot 'app\src\main\assets\android_omwfx\wetworld_android_051_weather.omwfx'
$BuildGradle = Join-Path $ProjectRoot 'app\build.gradle'
$MainActivity = Join-Path $ProjectRoot 'app\src\main\java\ui\activity\MainActivity.kt'
$BackupDir = Join-Path $ProjectRoot 'tools\patch-backups\final7-disable-puddles'

function Read-Lf([string]$Path) {
    return ([IO.File]::ReadAllText($Path) -replace "`r`n", "`n")
}

function Write-Utf8Lf([string]$Path, [string]$Text) {
    $Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, ($Text -replace "`r`n", "`n"), $Utf8NoBom)
}

foreach ($Required in @($Shader, $BuildGradle, $MainActivity)) {
    if (-not (Test-Path -LiteralPath $Required)) {
        throw "OpenMW 0.51.0-07 Final-7 source is incomplete. Missing: $Required"
    }
}

$GradleText = Read-Lf $BuildGradle
if (-not $GradleText.Contains("0.51.0-07") -or -not $GradleText.Contains("5107")) {
    throw 'This patch is only for the original OpenMW Android 0.51.0-07 / versionCode 5107 source.'
}

$MainText = Read-Lf $MainActivity
$ExpectedChain = '"wetworld_android_051_weather",' + "`n" +
                 '            "godrays_android_051_depthfixed_vivid",' + "`n" +
                 '            "lensflare_android_051_rayocc",' + "`n" +
                 '            "gateh_bloom051",' + "`n" +
                 '            "rainlens_android_051_v12_dense"'
if (-not $MainText.Contains($ExpectedChain)) {
    throw 'Final-7 OMWFX chain is not the expected WetWorld + Godrays + Lensflare + Bloom + RainLens chain.'
}
if ($MainText.Contains('omwfx_plus') -or $MainText.Contains('clouds_android_051')) {
    throw 'OMWFX+/cloud changes are present. Start from the clean OpenMW-Android_0.51-Final-7 (AAOS Touch Fixed) source.'
}

$ShaderText = Read-Lf $Shader
$Old = @'
        float puddleMask = smoothstep(0.56, 0.76, puddleField)
            * wetMask * puddle_strength_v35;
'@
$New = @'
        float puddleMask = smoothstep(0.56, 0.76, puddleField)
            * wetMask * puddle_strength_v35 * 0.0;
'@

if ($ShaderText.Contains($New.TrimStart("`r", "`n"))) {
    Write-Host 'OpenMW Final-7 puddles are already disabled.'
} else {
    $OldNormalized = $Old.TrimStart("`r", "`n")
    $NewNormalized = $New.TrimStart("`r", "`n")
    $Count = ([regex]::Matches($ShaderText, [regex]::Escape($OldNormalized))).Count
    if ($Count -ne 1) {
        throw "Expected exactly one original Final-7 puddleMask anchor, found $Count. No files were changed."
    }

    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    Copy-Item -LiteralPath $Shader -Destination (Join-Path $BackupDir 'wetworld_android_051_weather.omwfx.before-no-puddles') -Force

    $ShaderText = $ShaderText.Replace($OldNormalized, $NewNormalized)
    Write-Utf8Lf $Shader $ShaderText
}

$Verify = Read-Lf $Shader
foreach ($Need in @(
    'uniform_float wet_strength_v35',
    'uniform_float wet_darkening_v35',
    'uniform_float wet_sheen_v35',
    'float rainFactor051()',
    'vec3 reconstructedWorldNormal051(vec2 uv)',
    'float upFacing = smoothstep(0.30, 0.76, abs(n.z));',
    'float wetMask = clamp(rain * upFacing * distanceFade * wet_strength_v35, 0.0, 1.0);',
    'vec3 sheen = skyTint * broadSheen * wet_sheen_v35 * wetMask;',
    '* wetMask * puddle_strength_v35 * 0.0;',
    'if (base.a < 0.25)',
    'version = "5.1-051-weather-puddles-mali-safe";'
)) {
    if (-not $Verify.Contains($Need)) {
        throw "Post-patch validation failed. Missing preserved Final-7 token: $Need"
    }
}

# Ensure every visual contribution driven by puddleMask remains present but is fed zero.
foreach ($Need in @(
    '0.16 * puddleMask',
    '* puddleMask * 0.62',
    'puddleMask * 0.18'
)) {
    if (-not $Verify.Contains($Need)) {
        throw "Post-patch validation failed. Original downstream WetWorld structure changed unexpectedly: $Need"
    }
}

Write-Host ''
Write-Host 'OpenMW Android 0.51.0-07 Final-7 no-puddles patch: PASS'
Write-Host 'Changed file: wetworld_android_051_weather.omwfx only'
Write-Host 'Puddle mask: forced to 0.0 everywhere (ground, leaves, foliage, trees, objects)'
Write-Host 'WetWorld rain darkening: preserved'
Write-Host 'WetWorld broad wet sheen: preserved'
Write-Host 'Water exclusion / water shader: untouched'
Write-Host 'Godrays / Lensflare / Bloom / RainLens: untouched'
Write-Host 'Launcher / AAOS touch / shadows / native library: untouched'
Write-Host 'Version: remains 0.51.0-07 / 5107'
Write-Host 'Native rebuild: NOT required'
