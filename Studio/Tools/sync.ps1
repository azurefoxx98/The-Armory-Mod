# Copies the code-only jar and the HyCreator plugin from this repository to where the game and
# HyCreator read them, when they changed. Setup runs it once, and git runs it after every pull and
# every branch switch, so both always match the files of the branch that is checked out.
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
    if (-not (Copy-IfChanged (Join-Path $PSScriptRoot 'TheArmory-code.jar') (Join-Path $mods 'TheArmory-code.jar') 'The Armory''s code in Hytale' 'Leave your Hytale world, then click Send my work.')) {
        $ok = $false
    }
}

$pluginSource = Join-Path $PSScriptRoot 'HyCreator'
if ($plugins -and (Test-Path -LiteralPath $pluginSource) -and (Test-Path -LiteralPath $plugins)) {
    Get-ChildItem -LiteralPath $pluginSource -File | Where-Object { $_.Extension -in '.js', '.hcplugin' } | ForEach-Object {
        if (-not (Copy-IfChanged $_.FullName (Join-Path $plugins $_.Name) 'The Armory''s pages in HyCreator. They show once HyCreator restarts or reloads' 'Close HyCreator and open it again, then click Send my work.')) {
            $ok = $false
        }
    }
}

# HyCreator before 1.3.9 listed the mod's own textures and models from a Common folder at the top of
# the open folder, so this script used to keep a link named Common there, pointing at
# src\main\resources\Common. HyCreator 1.3.9 finds them where they are and nothing reads the link any
# more, so it is removed, as a link only: the files it points at stay. A real folder named Common is
# left alone.
$common = Join-Path $repo 'Common'
$item = Get-Item -LiteralPath $common -Force -ErrorAction SilentlyContinue
if ($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    try { [IO.Directory]::Delete($common, $false) } catch { }
}
# The line that kept the link out of git goes with it, so a real folder made there by mistake shows
# in git instead of being silently left out of every commit.
$exclude = (git -C $repo rev-parse --git-path info/exclude) 2>$null
if ($exclude) {
    if (-not [IO.Path]::IsPathRooted($exclude)) { $exclude = Join-Path $repo $exclude }
    if (Test-Path -LiteralPath $exclude) {
        $text = [IO.File]::ReadAllText($exclude)
        $kept = [regex]::Replace($text, '(?m)^/Common\r?(\n|$)', '')
        if ($kept -ne $text) { [IO.File]::WriteAllText($exclude, $kept, (New-Object Text.UTF8Encoding $false)) }
    }
}

if ($ok) { exit 0 } else { exit 1 }
