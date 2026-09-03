$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$WetWorld = Join-Path $ProjectRoot 'app\src\main\assets\android_omwfx\wetworld_android_051_weather.omwfx'
$MainActivity = Join-Path $ProjectRoot 'app\src\main\java\ui\activity\MainActivity.kt'
$BuildGradle = Join-Path $ProjectRoot 'app\build.gradle'

foreach ($Path in @($WetWorld, $MainActivity, $BuildGradle)) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "OpenMW 0.51.0-07 Final-7 source required. Missing: $Path"
    }
}

function Read-Lf([string]$Path) {
    return ([IO.File]::ReadAllText($Path) -replace "`r`n", "`n")
}

$MainText = Read-Lf $MainActivity
$GradleText = Read-Lf $BuildGradle
$WetText = Read-Lf $WetWorld

# Refuse later OMWFX+/cloud experiments and require the original Final-7 OMWFX chain.
$Final7Chain = 'wetworld_android_051_weather,godrays_android_051_depthfixed_vivid,lensflare_android_051_rayocc,gateh_bloom051,rainlens_android_051_v12_dense'
if (-not $MainText.Contains($Final7Chain)) {
    throw 'This patch requires the original 0.51.0-07 Final-7 OMWFX chain.'
}
if ($MainText.Contains('OMWFX_PLUS') -or $MainText.Contains('omwfx_plus') -or $MainText.Contains('clouds_android_051')) {
    throw 'Refusing a later OMWFX+/cloud-modified source. Apply only to fresh Final-7.'
}

# Verify the original Final-7 WetWorld implementation. These are deliberately
# left in place so the existing Final-7 launcher/Gradle payload guards remain valid.
foreach ($Need in @(
    'uniform_float wet_strength_v35',
    'uniform_float wet_darkening_v35',
    'uniform_float wet_sheen_v35',
    'uniform_float puddle_strength_v35',
    'float rainFactor051()',
    'float valueNoise051(vec2 p)',
    'vec3 reconstructedWorldNormal051(vec2 uv)',
    'float upFacing = smoothstep(0.30, 0.76, abs(n.z));',
    'float puddleMask = smoothstep(0.56, 0.76, puddleField)',
    'vec3 sheen = skyTint * broadSheen * wet_sheen_v35 * wetMask;',
    'version = "5.1-051-weather-puddles-mali-safe";'
)) {
    if (-not $WetText.Contains($Need)) {
        throw "Final-7 WetWorld shader does not match expected source: $Need"
    }
}

$Marker = '// OPENMW_ANDROID_051_FINAL7_WETWORLD_VISUALS_DISABLED'
if ($WetText.Contains($Marker)) {
    Write-Host 'Final-7 WetWorld visual disable is already applied.'
    exit 0
}

$Anchor = '        vec4 base = omw_GetLastShader(omw_TexCoord);'
$AnchorCount = ([regex]::Matches($WetText, [regex]::Escape($Anchor))).Count
if ($AnchorCount -ne 1) {
    throw "Expected exactly one WetWorld main-color anchor, found $AnchorCount"
}

# The Final-7 depth-only normal reconstruction cannot reliably distinguish
# alpha-tested foliage cards from solid ground on GLES2/GL4ES. Therefore every
# WetWorld surface operation (generic wet darkening, broad Fresnel sheen and
# procedural puddles) must be bypassed to guarantee that leaves, trees and
# terrain never receive false water-like patches. Keep the full original code
# below as dead code so all Final-7 build/runtime payload guards remain intact.
$Replacement = @"
$Anchor

        $Marker
        // Pass the scene through unchanged. This disables ONLY the WetWorld
        // surface layer; later OMWFX passes (Godrays, Lensflare, Bloom,
        // RainLens) and native water/rain ripples remain untouched.
        omw_FragColor = base;
        return;
"@

$WetText = $WetText.Replace($Anchor, $Replacement)

$BackupDir = Join-Path $ProjectRoot 'tools\patch-backups\final7-disable-wetworld-surfaces'
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
Copy-Item -LiteralPath $WetWorld -Destination (Join-Path $BackupDir 'wetworld_android_051_weather.omwfx.before-disable') -Force

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($WetWorld, ($WetText -replace "`n", [Environment]::NewLine), $Utf8NoBom)

$Verify = Read-Lf $WetWorld
if (-not $Verify.Contains($Marker)) {
    throw 'Verification failed: WetWorld disable marker missing.'
}
if (-not $Verify.Contains("$Marker`n        // Pass the scene through unchanged.")) {
    throw 'Verification failed: WetWorld pass-through block malformed.'
}
# Ensure the original Final-7 shader body remains present byte-for-text after the kill switch.
foreach ($Need in @(
    'float puddleMask = smoothstep(0.56, 0.76, puddleField)',
    'vec3 sheen = skyTint * broadSheen * wet_sheen_v35 * wetMask;',
    'vec3 puddleReflection = skyTint * (0.20 + fresnel * 0.80)',
    'version = "5.1-051-weather-puddles-mali-safe";'
)) {
    if (-not $Verify.Contains($Need)) {
        throw "Verification failed: original Final-7 WetWorld body was altered: $Need"
    }
}

Write-Host ''
Write-Host 'OpenMW 0.51.0-07 Final-7 WetWorld surface cleanup: PASS'
Write-Host '  WetWorld puddles: disabled'
Write-Host '  WetWorld broad sheen/glassy patches: disabled'
Write-Host '  WetWorld rain darkening on foliage/ground: disabled'
Write-Host '  Godrays/Lensflare/Bloom/RainLens: unchanged'
Write-Host '  Native water/rain ripples: unchanged'
Write-Host '  Launcher/AAOS/input/shadows/native library: unchanged'
Write-Host '  Native rebuild: NOT required'
