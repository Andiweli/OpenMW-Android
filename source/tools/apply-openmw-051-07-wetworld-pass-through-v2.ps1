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

# Require the actual Final-7 release identity. Do not rely on a single joined
# chain string: Final-7 stores the OMWFX techniques as a Kotlin list.
if (-not $GradleText.Contains("def openMwAndroidVersionName = '0.51.0-07'") -or
    -not $GradleText.Contains('def openMwVersionCode = 5107')) {
    throw 'This patch requires the original OpenMW Android 0.51.0-07 / versionCode 5107 source.'
}

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
        throw "Final-7 OMWFX launcher structure is missing: $Need"
    }
}

if ($MainText.Contains('OMWFX_PLUS_PRESET_VALUE') -or
    $MainText.Contains('clouds_android_051') -or
    $MainText.Contains('"omwfx_plus"')) {
    throw 'Refusing an OMWFX+/cloud-modified MainActivity. Start from 0.51.0-07 Final-7.'
}

# Accept either pristine Final-7 WetWorld or Final-7 after the earlier
# puddle-only disable patch. Both retain these compatibility/build-gate anchors.
foreach ($Need in @(
    'uniform_float wet_strength_v35',
    'uniform_float wet_darkening_v35',
    'uniform_float wet_sheen_v35',
    'uniform_float puddle_strength_v35',
    'float rainFactor051()',
    'vec3 reconstructedWorldNormal051(vec2 uv)',
    'float upFacing = smoothstep(0.30, 0.76, abs(n.z));',
    'float puddleMask = smoothstep(0.56, 0.76, puddleField)',
    'version = "5.1-051-weather-puddles-mali-safe";'
)) {
    if (-not $WetText.Contains($Need)) {
        throw "Final-7 WetWorld shader does not match the supported source: $Need"
    }
}

$Marker = '// OPENMW_ANDROID_051_FINAL7_WETWORLD_PASS_THROUGH_V2'
if ($WetText.Contains($Marker)) {
    Write-Host 'OpenMW 0.51.0-07 Final-7 WetWorld pass-through v2 is already applied.'
    exit 0
}

$Anchor = '        vec4 base = omw_GetLastShader(omw_TexCoord);'
$AnchorCount = ([regex]::Matches($WetText, [regex]::Escape($Anchor))).Count
if ($AnchorCount -ne 1) {
    throw "Expected exactly one Final-7 WetWorld main-color anchor, found $AnchorCount"
}

# On GLES2/GL4ES the depth-derived normal cannot reliably distinguish thin,
# alpha-tested foliage cards from solid ground. Therefore puddleMask=0 alone is
# insufficient: wetMask + broad Fresnel sheen can still look like water patches
# around leaves and trees. Bypass ONLY this WetWorld surface pass. The full old
# body remains below as unreachable code so Final-7 Gradle/runtime guards keep
# their exact compatibility anchors. Godrays/Lensflare/Bloom/RainLens are later
# passes and remain unchanged.
$Replacement = @"
$Anchor

        $Marker
        // Disable the entire WetWorld surface layer on Android GLES2/GL4ES.
        // This removes procedural puddles AND false wet/glassy patches on
        // terrain, leaves, foliage cards and tree geometry.
        // Later OMWFX passes and native water/rain ripples remain untouched.
        omw_FragColor = base;
        return;
"@

$BackupDir = Join-Path $ProjectRoot 'tools\patch-backups\final7-wetworld-pass-through-v2'
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
Copy-Item -LiteralPath $WetWorld -Destination (Join-Path $BackupDir 'wetworld_android_051_weather.omwfx.before-v2') -Force

$WetText = $WetText.Replace($Anchor, $Replacement)
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($WetWorld, ($WetText -replace "`n", [Environment]::NewLine), $Utf8NoBom)

$Verify = Read-Lf $WetWorld
foreach ($Need in @(
    $Marker,
    "$Marker`n        // Disable the entire WetWorld surface layer on Android GLES2/GL4ES.",
    "        omw_FragColor = base;`n        return;",
    '"godrays_android_051_depthfixed_vivid"',
    'version = "5.1-051-weather-puddles-mali-safe";'
)) {
    if ($Need -eq '"godrays_android_051_depthfixed_vivid"') {
        if (-not $MainText.Contains($Need)) { throw "Verification failed: $Need" }
    } elseif (-not $Verify.Contains($Need)) {
        throw "Verification failed: $Need"
    }
}

# Confirm the original compatibility anchors are still present below the early
# return; this is important because Final-7 build.gradle validates them.
foreach ($Need in @(
    'float puddleMask = smoothstep(0.56, 0.76, puddleField)',
    'vec3 sheen = skyTint * broadSheen * wet_sheen_v35 * wetMask;',
    'version = "5.1-051-weather-puddles-mali-safe";'
)) {
    if (-not $Verify.Contains($Need)) {
        throw "Verification failed: Final-7 compatibility anchor was lost: $Need"
    }
}

Write-Host ''
Write-Host 'OpenMW 0.51.0-07 Final-7 WetWorld pass-through v2: PASS'
Write-Host '  Procedural puddles: disabled'
Write-Host '  WetWorld wet/glassy foliage patches: disabled'
Write-Host '  WetWorld ground wet-surface layer: disabled'
Write-Host '  Godrays/Lensflare/Bloom/RainLens: unchanged'
Write-Host '  Water/refraction/reflection/native rain ripples: unchanged'
Write-Host '  Launcher/AAOS touch/input/shadows/native library: unchanged'
Write-Host '  Native rebuild: NOT required'
