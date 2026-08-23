param()

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Helper = Join-Path $ProjectRoot 'buildscripts\patches\openmw051-final\apply-android-launcher-audit.py'

if (-not (Test-Path -LiteralPath $Helper)) {
    throw "Patch 49 helper missing: $Helper"
}

if (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 $Helper $ProjectRoot
} elseif (Get-Command python -ErrorAction SilentlyContinue) {
    & python $Helper $ProjectRoot
} else {
    throw 'Patch 49 requires Python 3 (py -3 or python).'
}

if ($LASTEXITCODE -ne 0) {
    throw "Patch 49 failed with exit code $LASTEXITCODE"
}
