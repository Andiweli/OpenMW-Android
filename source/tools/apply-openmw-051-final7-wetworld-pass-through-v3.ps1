$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$WetWorld = Join-Path $ProjectRoot 'app\src\main\assets\android_omwfx\wetworld_android_051_weather.omwfx'
$MainActivity = Join-Path $ProjectRoot 'app\src\main\java\ui\activity\MainActivity.kt'

foreach ($Path in @($WetWorld, $MainActivity)) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Final-7 OMWFX source structure is incomplete. Missing: $Path"
    }
}

function Read-Lf([string]$Path) {
    return ([IO.File]::ReadAllText($Path) -replace "`r`n", "`n")
}

$MainText = Read-Lf $MainActivity
$WetText = Read-Lf $WetWorld

# Verify the actual Final-7 OMWFX architecture, not app version metadata.
# The app version may have been bumped independently and is irrelevant here.
foreach ($Need in @(
    'private const val OMWFX_PRESET_VALUE = "omwfx"',
    'private val OMWFX_RECOMMENDED_CHAIN = listOf(',
    '"wetworld_android_051_weather"',
    '"godrays_android_051_depthfixed_vivid"',
    '"lensflare_android_051_rayocc"',
    '"gateh_bloom051"',
    '"rainlens_android_051_v12_dense"'
)) {
    if (-not $MainText.Contains($Need)) {
        throw "Required Final-7 OMWFX launcher structure is missing: $Need"
    }
}

# Do not apply on the abandoned OMWFX+/cloud experiment branch.
if ($MainText.Contains('OMWFX_PLUS_PRESET_VALUE') -or
    $MainText.Contains('clouds_android_051') -or
    $MainText.Contains('private val OMWFX_PLUS_RECOMMENDED_CHAIN')) {
    throw 'OMWFX+/cloud launcher code is present. This cleanup is intended for the Final-7 OMWFX architecture only.'
}

# Accept pristine Final-7 and the earlier puddle-mask-zero variant.
foreach ($Need in @(
    'uniform_float wet_strength_v35',
    'uniform_float wet_darkening_v35',
    'uniform_float wet_sheen_v35',
    'uniform_float puddle_strength_v35',
    'float rainFactor051()',
    'vec3 reconstructedWorldNormal051(vec2 uv)',
    'float upFacing = smoothstep(0.30, 0.76, abs(n.z));',
    'float wetMask = clamp(rain * upFacing * distanceFade * wet_strength_v35, 0.0, 1.0);',
    'vec3 sheen = skyTint * broadSheen * wet_sheen_v35 * wetMask;',
    'version = "5.1-051-weather-puddles-mali-safe";'
)) {
    if (-not $WetText.Contains($Need)) {
        throw "WetWorld shader does not match the supported Final-7 family: $Need"
    }
}

$Marker = '// OPENMW_ANDROID_051_FINAL7_WETWORLD_PASS_THROUGH_V3'
$OldMarker = '// OPENMW_ANDROID_051_FINAL7_WETWORLD_PASS_THROUGH_V2'
if ($WetText.Contains($Marker) -or $WetText.Contains($OldMarker)) {
    Write-Host 'Final-7 WetWorld surface pass-through is already applied.'
    exit 0
}

$Anchor = '        vec4 base = omw_GetLastShader(omw_TexCoord);'
$AnchorCount = ([regex]::Matches($WetText, [regex]::Escape($Anchor))).Count
if ($AnchorCount -ne 1) {
    throw "Expected exactly one WetWorld main-color anchor, found $AnchorCount. No files were changed."
}

$Replacement = @"
$Anchor

        $Marker
        // Android GLES2/GL4ES has no reliable material/foliage classification
        // in this post-process pass. Depth-derived normals can classify alpha-
        // tested leaves and tree cards as upward-facing wet ground. Disable only
        // WetWorld's surface colouring. The later Final-7 OMWFX passes and native
        // water/rain-ripple renderer remain unchanged.
        omw_FragColor = base;
        return;
"@

$BackupDir = Join-Path $ProjectRoot 'tools\patch-backups\final7-wetworld-pass-through-v3'
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
Copy-Item -LiteralPath $WetWorld -Destination (Join-Path $BackupDir 'wetworld_android_051_weather.omwfx.before-v3') -Force

$WetText = $WetText.Replace($Anchor, $Replacement)
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($WetWorld, ($WetText -replace "`n", [Environment]::NewLine), $Utf8NoBom)

$Verify = Read-Lf $WetWorld
foreach ($Need in @(
    $Marker,
    "        omw_FragColor = base;`n        return;",
    'float wetMask = clamp(rain * upFacing * distanceFade * wet_strength_v35, 0.0, 1.0);',
    'vec3 sheen = skyTint * broadSheen * wet_sheen_v35 * wetMask;',
    'float puddleMask = smoothstep(0.56, 0.76, puddleField)',
    'version = "5.1-051-weather-puddles-mali-safe";'
)) {
    if (-not $Verify.Contains($Need)) {
        throw "Post-patch verification failed: $Need"
    }
}

# Confirm no unrelated source file was intentionally touched by this script.
Write-Host ''
Write-Host 'OpenMW Final-7 WetWorld surface pass-through v3: PASS'
Write-Host 'Changed file: app/src/main/assets/android_omwfx/wetworld_android_051_weather.omwfx ONLY'
Write-Host 'Procedural puddles: disabled by bypassing WetWorld surface colouring'
Write-Host 'False wet/glassy foliage/tree/ground patches: disabled'
Write-Host 'Godrays / Lensflare / Bloom / RainLens: unchanged'
Write-Host 'Water / refraction / reflection / native rain ripples: unchanged'
Write-Host 'Launcher / AAOS touch / shadows / native library: unchanged'
Write-Host 'App version metadata: ignored and unchanged'
Write-Host 'Native rebuild: NOT required'
