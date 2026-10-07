# More Balls - new-ball integration check (resource side)
#
# The code-side check lives in BallIntegrationCheck.java (runs at startup and verifies
# tag <-> sources() consistency). This script checks RESOURCE FILES instead:
# textures, models, crossbow charge cases, and orphan detection.
#
# NOTE: this file must stay pure ASCII. Windows PowerShell 5.1 decodes .ps1 as GBK,
# so any CJK literal (even inside comments) turns into mojibake and breaks parsing.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File check-ball-integration.ps1
#   powershell -ExecutionPolicy Bypass -File check-ball-integration.ps1 -ResourceRoot <path>
#
# Exit code: 0 = all good; 1 = hard errors (missing texture / model / orphan)

param(
    [string]$ResourceRoot = 'C:\mcbuild\07-NeoForge-26.2\src\main\resources'
)

$ErrorActionPreference = 'Stop'

function ReadJson([string]$path) {
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
}

$errors   = New-Object System.Collections.ArrayList
$warnings = New-Object System.Collections.ArrayList
$ok       = New-Object System.Collections.ArrayList

Write-Host ''
Write-Host '===== More Balls / new-ball integration check =====' -ForegroundColor Cyan
Write-Host "Resource root: $ResourceRoot"
Write-Host ''

# ---------- 1. read the balls tag ----------
$ballsTagPath = Join-Path $ResourceRoot 'data\more_balls\tags\item\balls.json'
if (-not (Test-Path -LiteralPath $ballsTagPath)) {
    Write-Host "[FAIL] balls tag not found: $ballsTagPath" -ForegroundColor Red
    exit 1
}
$balls = @((ReadJson $ballsTagPath).values)
$ownBalls = @($balls | Where-Object { $_ -like 'more_balls:*' })

# Balls that deliberately do NOT follow the one-texture-one-model convention.
# Keep this list in sync with BallIntegrationCheck.INTENTIONALLY_EXCLUDED in Java.
# combo_ball is an ASSEMBLED item: it renders via a 4-layer composite in
# assets/more_balls/items/combo_ball.json and has no single texture of its own.
$specialBalls = @('combo_ball')
[void]$ok.Add("balls tag has $($balls.Count) entries, $($ownBalls.Count) from this mod")

# ---------- 2. every own ball needs a texture + an item model ----------
$texDir   = Join-Path $ResourceRoot 'assets\more_balls\textures\item'
$modelDir = Join-Path $ResourceRoot 'assets\more_balls\models\item'

foreach ($id in $ownBalls) {
    $short = $id -replace '^more_balls:', ''
    if ($specialBalls -contains $short) { continue }

    if (-not (Test-Path -LiteralPath (Join-Path $texDir ($short + '.png')))) {
        [void]$errors.Add("missing texture: $short.png")
    }
    if (-not (Test-Path -LiteralPath (Join-Path $modelDir ($short + '.json')))) {
        [void]$errors.Add("missing item model: $short.json")
    }
}

# ---------- 3. crossbow: every tag member needs a case, and a fallback must exist ----------
$crossbowPath = Join-Path $ResourceRoot 'assets\minecraft\items\crossbow.json'
if (Test-Path -LiteralPath $crossbowPath) {
    $cb = ReadJson $crossbowPath
    $ballLayer = $null
    foreach ($layer in $cb.model.models) {
        if ($layer.type -eq 'minecraft:select' -and $layer.property -eq 'more_balls:charged_ball') {
            $ballLayer = $layer
            break
        }
    }
    if (-not $ballLayer) {
        [void]$errors.Add('crossbow.json has no charged_ball select layer (charge icons all broken)')
    } else {
        $cases = @($ballLayer.cases | ForEach-Object { $_.when })
        $missing = @($balls | Where-Object { $cases -notcontains $_ })
        if ($missing.Count -gt 0) {
            [void]$warnings.Add("crossbow missing cases: $($missing -join ', ') (fallback covers it, but the icon will not match)")
        } else {
            [void]$ok.Add("crossbow covers all $($cases.Count) cases")
        }
        if (-not $ballLayer.fallback) {
            [void]$errors.Add('crossbow ball layer has no fallback -- unregistered balls render blank')
        } else {
            [void]$ok.Add('crossbow ball layer has a fallback')
        }
    }
} else {
    [void]$warnings.Add("crossbow.json not found ($crossbowPath)")
}

# ---------- 4. cutting recipes must use the balls tag ----------
$recipeDir = Join-Path $ResourceRoot 'data\more_balls\recipe'
$cutRecipes = @(Get-ChildItem $recipeDir -Filter '*cut*' -File -ErrorAction SilentlyContinue)
if ($cutRecipes.Count -eq 0) {
    [void]$errors.Add('no cutting recipes found')
} else {
    # Cutting recipes must use the cuttable tag (solid + hollow, snowballs excluded).
    # Using the whole balls tag would wrongly make snowballs cuttable.
    $tagged = 0
    foreach ($r in $cutRecipes) {
        $o = ReadJson $r.FullName
        if ($o.ingredient -eq '#more_balls:balls/cuttable') { $tagged++ }
        else { [void]$warnings.Add("$($r.Name): ingredient should be #more_balls:balls/cuttable, got $($o.ingredient)") }
    }
    if ($tagged -eq $cutRecipes.Count) {
        [void]$ok.Add("all $($cutRecipes.Count) cutting recipes use #more_balls:balls/cuttable (solid + hollow, no snowballs)")
    }
}

# ---------- 5. orphan check: textures / models nothing references ----------
$referenced = New-Object System.Collections.Generic.HashSet[string]

$scanDirs = @(
    (Join-Path $ResourceRoot 'assets\more_balls\models'),
    (Join-Path $ResourceRoot 'assets\more_balls\items'),
    (Join-Path $ResourceRoot 'assets\minecraft\items')
)
foreach ($d in $scanDirs) {
    if (-not (Test-Path -LiteralPath $d)) { continue }
    foreach ($f in (Get-ChildItem $d -Recurse -Filter '*.json' -File)) {
        $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
        foreach ($m in [regex]::Matches($txt, '"more_balls:item/([^"]+)"')) {
            [void]$referenced.Add($m.Groups[1].Value)
        }
    }
}

$orphanTex = @()
foreach ($f in (Get-ChildItem $texDir -Filter '*.png' -File -ErrorAction SilentlyContinue)) {
    if (-not $referenced.Contains($f.BaseName)) { $orphanTex += $f.Name }
}
if ($orphanTex.Count -gt 0) {
    [void]$warnings.Add("orphan textures (referenced by no model): $($orphanTex -join ', ')")
} else {
    [void]$ok.Add('no orphan textures')
}

# ---------- 6. example-only items: must exist, and must be excluded from creative ----------
$exTagPath = Join-Path $ResourceRoot 'data\more_balls\tags\item\example_only.json'
if (Test-Path -LiteralPath $exTagPath) {
    $examples = @((ReadJson $exTagPath).values)
    foreach ($id in $examples) {
        $short = $id -replace '^more_balls:', ''
        # each example needs SOME model (either models/item/<x>.json or items/<x>.json)
        $hasModel = (Test-Path -LiteralPath (Join-Path $modelDir ($short + '.json'))) -or
                    (Test-Path -LiteralPath (Join-Path $ResourceRoot ('assets\more_balls\items\' + $short + '.json')))
        if (-not $hasModel) { [void]$errors.Add("example item has no model: $short") }

        # and it must NOT be in the balls tag (examples are not real balls)
        if ($balls -contains $id) { [void]$errors.Add("example item $id is also in the balls tag -- it would appear as a real ball") }
    }
    [void]$ok.Add("$($examples.Count) example-only items present with models")

    # the cuttable tag must exclude snowballs
    $cutTagPath = Join-Path $ResourceRoot 'data\more_balls\tags\item\balls\cuttable.json'
    if (Test-Path -LiteralPath $cutTagPath) {
        $cv = @((ReadJson $cutTagPath).values)
        if ($cv -match 'snowball') {
            [void]$errors.Add("cuttable tag references snowball -- snowballs must not be cuttable")
        } else {
            [void]$ok.Add('cuttable tag excludes snowballs (solid + hollow only)')
        }
    }
} else {
    [void]$warnings.Add('no example_only tag found')
}
# ---------- report ----------
Write-Host '--- passed ---' -ForegroundColor Green
foreach ($s in $ok) { Write-Host "  [OK]   $s" }

if ($warnings.Count -gt 0) {
    Write-Host ''
    Write-Host '--- warnings ---' -ForegroundColor Yellow
    foreach ($s in $warnings) { Write-Host "  [WARN] $s" }
}

if ($errors.Count -gt 0) {
    Write-Host ''
    Write-Host '--- errors ---' -ForegroundColor Red
    foreach ($s in $errors) { Write-Host "  [FAIL] $s" }
    Write-Host ''
    Write-Host "FAILED: $($errors.Count) error(s), $($warnings.Count) warning(s)" -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host "PASSED: 0 errors, $($warnings.Count) warning(s)" -ForegroundColor Green
exit 0
