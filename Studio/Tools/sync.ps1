# Copies the code-only jar and the HyCreator plugin from this repository to where the game and
# HyCreator read them, when they changed, and keeps the link HyCreator's file picker needs. Setup
# runs it once, and git runs it after every pull and every branch switch, so both always match the
# files of the branch that is checked out.
#
# The two places are stored in this repository's git settings by setup (armory.mods and
# armory.plugins), so this script needs no arguments.
param(
    [switch] $Quiet
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Say([string] $text) {
    if (-not $Quiet) { Write-Host $text }
}

function Same-File([string] $a, [string] $b) {
    if (-not (Test-Path -LiteralPath $b)) { return $false }
    # A file another program holds open may not even be readable. It counts as different, so the copy
    # is tried and its own message says what to do.
    try { return (Get-FileHash -LiteralPath $a).Hash -eq (Get-FileHash -LiteralPath $b).Hash } catch { return $false }
}

function Copy-IfChanged([string] $from, [string] $to, [string] $what, [string] $fix) {
    if (Same-File $from $to) { return $true }
    try {
        Copy-Item -LiteralPath $from -Destination $to -Force
        Say "Updated $what."
        return $true
    } catch {
        # The game holds the jar open while a world runs, so a copy can fail until it closes.
        Write-Host "Could not update $what. $fix"
        return $false
    }
}

$mods = (git -C $repo config --get armory.mods) 2>$null
$plugins = (git -C $repo config --get armory.plugins) 2>$null
$ok = $true

if ($mods) {
    if (-not (Copy-IfChanged (Join-Path $PSScriptRoot 'TheArmory-code.jar') (Join-Path $mods 'TheArmory-code.jar') 'The Armory''s code in Hytale' 'Leave your Hytale world, then double-click Update.cmd.')) {
        $ok = $false
    }
}

$pluginSource = Join-Path $PSScriptRoot 'HyCreator'
if ($plugins -and (Test-Path -LiteralPath $pluginSource) -and (Test-Path -LiteralPath $plugins)) {
    Get-ChildItem -LiteralPath $pluginSource -File | Where-Object { $_.Extension -in '.js', '.hcplugin' } | ForEach-Object {
        if (-not (Copy-IfChanged $_.FullName (Join-Path $plugins $_.Name) 'The Armory''s pages in HyCreator (restart HyCreator to see them)' 'Close HyCreator, then double-click Update.cmd.')) {
            $ok = $false
        }
    }
}

# HyCreator's file picker lists the mod's own textures, models and animations from a Common folder at
# the top of the open folder, while this copy keeps them in src\main\resources\Common, so its tab for the
# mod stayed empty. A link named Common at the top lets the picker find them. Everything else in
# HyCreator already reads the right folder, and its own file list does not count them twice. Only a link
# this script made is ever replaced, and only the link itself: a real folder named Common is left alone.
$common = Join-Path $repo 'Common'
$commonFiles = Join-Path $repo 'src\main\resources\Common'
if (Test-Path -LiteralPath $commonFiles) {
    $item = Get-Item -LiteralPath $common -Force -ErrorAction SilentlyContinue
    $isLink = $item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)
    $pointsAt = if ($isLink) { [string]($item.Target | Select-Object -First 1) } else { $null }
    # A copy moved to another folder keeps a link to the old place.
    if ($isLink -and $pointsAt -ne $commonFiles) {
        try { [IO.Directory]::Delete($common, $false); $item = $null } catch { }
    }
    if (-not $item) {
        try {
            New-Item -ItemType Junction -Path $common -Target $commonFiles | Out-Null
            Say 'HyCreator''s file picker now shows the mod''s own textures and models.'
        } catch {
            Write-Host 'HyCreator''s file picker cannot show the mod''s own textures on this drive. Type their path instead.'
        }
    }
    # Git leaves the link out of every commit. This setting stays on this computer and is never sent.
    $exclude = (git -C $repo rev-parse --git-path info/exclude) 2>$null
    if ($exclude) {
        if (-not [IO.Path]::IsPathRooted($exclude)) { $exclude = Join-Path $repo $exclude }
        $lines = if (Test-Path -LiteralPath $exclude) { @(Get-Content -LiteralPath $exclude) } else { @() }
        if ($lines -notcontains '/Common') {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $exclude) | Out-Null
            Add-Content -LiteralPath $exclude -Value '/Common'
        }
    }
}

if ($ok) { exit 0 } else { exit 1 }
