# One-time setup for working on The Armory in the game directly. The Set up button of The Armory's panel
# in HyCreator runs it (through run.ps1): first with -Report, which changes nothing and lists the other
# copies of the mod it would move, so the panel can ask her, then with -Yes once she agreed.
#
# What it does, in order:
#   1. Checks that git is installed and that this folder is the mod's repository, and moves a fresh
#      copy from main to her branch.
#   2. Finds every other copy of The Armory in the game's mods folders and, with a yes, moves it
#      to a folder beside them. The game refuses to start a world that holds two copies.
#   3. Links the game's mods folder to this repository's src\main\resources, so the game reads the
#      mod's files from here and a saved file shows in the game.
#   4. Copies the code-only jar beside that link, so the benches and powers run too.
#   5. Installs the HyCreator plugin, and tells git to repeat steps 4 and 5 after every pull.
#   6. Installs the Python the mod's checks run on, and runs them once. Git then runs them after
#      every commit, pull and branch switch, and HyCreator shows what they found.
#
# Running it again is safe: anything already in place is left as it is.
param(
    # Where the game keeps its mods and worlds. Only tests point this elsewhere.
    [string] $UserData = (Join-Path $env:APPDATA 'Hytale\UserData'),
    # Where HyCreator loads its plugins from.
    [string] $Plugins = (Join-Path $env:LOCALAPPDATA 'HyCreator\Plugins'),
    # Where the checks' Python is installed.
    [string] $PythonHome = (Join-Path $env:LOCALAPPDATA 'TheArmory\Python-3.15.1'),
    # A copy of the Python download already on this computer, used instead of downloading it. Only tests use it.
    [string] $PythonZip = '',
    # Answer yes to every question: what the panel passes once she agreed to what -Report listed.
    [switch] $Yes,
    # Writes what setup would do to this file and changes nothing: the other copies it would move, and
    # whether the copy would move from main to her branch.
    [string] $Report = '',
    # Her branch, which a fresh copy on main is moved to.
    [string] $Branch = 'lady'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$resources = Join-Path $repo 'src\main\resources'
$mods = Join-Path $UserData 'Mods'
$setAside = Join-Path $UserData 'Armory set aside'

function Stop-Setup([string] $text, [string] $detail = '') {
    Write-Host ''
    Write-Host $text
    if ($detail) { Write-Host "For Larsonix: $detail" }
    exit 1
}

function Ask([string] $question) {
    if ($Yes) { return $true }
    $answer = Read-Host "$question (y/n)"
    return $answer -match '^\s*y'
}

# The identity the game uses for a mod: Group and Name in its manifest.json.
function Read-Identity([string] $path) {
    try {
        $item = Get-Item -LiteralPath $path -Force
        if ($item.PSIsContainer) {
            $file = Join-Path $path 'manifest.json'
            if (-not (Test-Path -LiteralPath $file)) { return $null }
            $text = [IO.File]::ReadAllText($file)
        } elseif ($item.Extension -in '.jar', '.zip') {
            $zip = [IO.Compression.ZipFile]::OpenRead($item.FullName)
            try {
                $entry = $zip.GetEntry('manifest.json')
                if (-not $entry) { return $null }
                $reader = New-Object IO.StreamReader($entry.Open())
                $text = $reader.ReadToEnd()
                $reader.Close()
            } finally {
                $zip.Dispose()
            }
        } else {
            return $null
        }
        $manifest = $text | ConvertFrom-Json
        return "$($manifest.Group):$($manifest.Name)"
    } catch {
        return $null
    }
}

# Runs git and returns its exit code and what it printed. Windows PowerShell would stop the script on
# anything git writes to its error stream, which it does in ordinary work.
function Invoke-GitQuiet([string[]] $arguments) {
    $ErrorActionPreference = 'Continue'
    $out = & git -C $repo @arguments 2>&1
    return @{ Code = $LASTEXITCODE; Text = (($out | ForEach-Object { "$_" }) -join "`n").Trim() }
}

function Link-Target([string] $path) {
    $item = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    if (-not $item -or -not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { return $null }
    return [string]($item.Target | Select-Object -First 1)
}

Write-Host 'Setting up The Armory on this computer.'
Write-Host ''

# 1. The tools and the folders this needs.
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Stop-Setup 'Git is not installed. Install it from https://git-scm.com/download/win with its default choices, then click Set up again.'
}
if (-not (Test-Path -LiteralPath (Join-Path $resources 'manifest.json')) -or -not (Test-Path -LiteralPath (Join-Path $repo '.git'))) {
    Stop-Setup 'This folder is not a copy of the mod. Send Larsonix a screenshot.' $repo
}
if (-not (Test-Path -LiteralPath $UserData)) {
    Stop-Setup 'Hytale was not found. Start Hytale once, then click Set up again.' $UserData
}
# 1b. Her branch. A fresh clone holds main alone, and HyCreator's Branches list shows only the
# branches a copy holds, so her branch would not be there to pick. Setup moves a copy on main to it.
$onBranch = (Invoke-GitQuiet @('rev-parse', '--abbrev-ref', 'HEAD')).Text
$fromMain = $onBranch -eq 'main' -and (Invoke-GitQuiet @('rev-parse', '--verify', '--quiet', "origin/$Branch")).Code -eq 0
if ($fromMain -and -not $Report) {
    if ((Invoke-GitQuiet @('status', '--porcelain', '--untracked-files=no')).Text) {
        Stop-Setup "Your copy has changes on main. Send Larsonix a screenshot of this window."
    }
    $switch = Invoke-GitQuiet @('switch', '-q', $Branch)
    if ($switch.Code -ne 0) { Stop-Setup "Your copy could not be moved to your branch, $Branch. Send Larsonix a screenshot of this window." $switch.Text }
    Write-Host "Your copy is now on your branch, $Branch."
}

if (-not $Report) { New-Item -ItemType Directory -Force -Path $mods | Out-Null }
$identity = Read-Identity $resources
$ourLink = Join-Path $mods 'TheArmory'
$ourJar = Join-Path $mods 'TheArmory-code.jar'

# 2. Every other copy of the mod, in the shared mods folder and in each world's own.
$folders = @(@($mods) | Where-Object { Test-Path -LiteralPath $_ })
$saves = Join-Path $UserData 'Saves'
if (Test-Path -LiteralPath $saves) {
    $folders += Get-ChildItem -LiteralPath $saves -Directory | ForEach-Object { Join-Path $_.FullName 'mods' } |
        Where-Object { Test-Path -LiteralPath $_ }
}
$others = @()
foreach ($folder in $folders) {
    foreach ($entry in Get-ChildItem -LiteralPath $folder -Force) {
        if ($entry.FullName -eq $ourJar) { continue }
        if ($entry.FullName -eq $ourLink -and (Link-Target $ourLink) -eq $resources) { continue }
        if ((Read-Identity $entry.FullName) -eq $identity) { $others += $entry }
    }
}
if ($Report) {
    $current = Link-Target $ourLink
    $plan = [ordered]@{
        others = @($others | ForEach-Object { $_.FullName }); setAside = $setAside
        toBranch = $(if ($fromMain) { $Branch } else { $null })
        linkTaken = [bool]((Test-Path -LiteralPath $ourLink) -and $current -ne $resources)
    }
    [IO.File]::WriteAllText($Report, ($plan | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding $false))
    if ($others.Count -gt 0) { Write-Host "Other copies of The Armory to move aside: $($others.Count)." } else { Write-Host 'No other copy of The Armory to move.' }
    exit 0
}
if ($others.Count -gt 0) {
    Write-Host 'Hytale can load only one copy of The Armory, and these are in your mods folders:'
    $others | ForEach-Object { Write-Host "  $($_.FullName)" }
    Write-Host "Yes moves them to $setAside, where you can find them again. Nothing is deleted."
    Write-Host 'No stops the setup and leaves everything as it is.'
    if (-not (Ask 'Move them?')) {
        Stop-Setup 'Setup stopped, and nothing was moved.'
    }
    New-Item -ItemType Directory -Force -Path $setAside | Out-Null
    foreach ($entry in $others) {
        # Named after where it came from: the shared mods folder, or the world that held it.
        $parent = Split-Path -Parent $entry.FullName
        $from = if ($parent -eq $mods) { 'Mods' } else { 'World ' + (Split-Path -Leaf (Split-Path -Parent $parent)) }
        $place = Join-Path $setAside ($from + ' - ' + $entry.Name)
        if (Test-Path -LiteralPath $place) { $place += ' (' + (Get-Date -Format 'yyyy-MM-dd HH-mm-ss') + ')' }
        Move-Item -LiteralPath $entry.FullName -Destination $place
        Write-Host "  Moved to $place"
    }
}

# 3. The link from the game's mods folder to the mod's files.
$current = Link-Target $ourLink
if ($current -eq $resources) {
    Write-Host 'Hytale already loads The Armory from this copy.'
} elseif (Test-Path -LiteralPath $ourLink) {
    Stop-Setup "A folder named TheArmory is already in your mods folder: $ourLink. Rename it or move it away, then click Set up again."
} else {
    New-Item -ItemType Junction -Path $ourLink -Target $resources | Out-Null
    Write-Host 'Hytale now loads The Armory from this copy.'
}

# 4 and 5. Where the jar and the plugin go, remembered for every later pull.
git -C $repo config armory.mods $mods
git -C $repo config armory.plugins $Plugins
git -C $repo config core.hooksPath Studio/Tools/hooks
if (-not (Test-Path -LiteralPath $Plugins)) {
    Write-Host 'HyCreator was not found, so its Armory pages are not added yet. Start HyCreator once, then click Set up again.'
}
& (Join-Path $PSScriptRoot 'sync.ps1')
if ($LASTEXITCODE -ne 0) { exit 1 }

# 6. The checks' Python: the official embeddable build from python.org, which needs no install and
# changes nothing else on this computer. Its fingerprint is the one python.org publishes for it, so a
# damaged or altered download is refused.
$pythonUrl = 'https://www.python.org/ftp/python/3.15.1/python-3.15.1-embed-amd64.zip'
$pythonSha256 = '352E232F25B5120508F11A6DFC1BD9076D3E70C30977D552A63338838C2AE8FB'
$python = Join-Path $PythonHome 'python.exe'
$checksReady = Test-Path -LiteralPath $python
if (-not $checksReady) {
    $zip = $PythonZip
    try {
        if (-not $zip) {
            $zip = Join-Path ([IO.Path]::GetTempPath()) 'TheArmory-python-3.15.1-embed-amd64.zip'
            Write-Host 'Downloading the checks (14 MB)...'
            $ProgressPreference = 'SilentlyContinue'
            # Windows PowerShell 5.1 may still offer old encryption that python.org refuses.
            [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $pythonUrl -OutFile $zip -UseBasicParsing
        }
        if ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne $pythonSha256) {
            throw 'the download does not match its published checksum'
        }
        # Unpacked beside its place and moved in whole, so an interrupted run never leaves half of it.
        $staging = "$PythonHome.partial"
        if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
        if (Test-Path -LiteralPath $PythonHome) { Stop-Setup "The checks could not be installed. Delete this folder, then click Set up again: $PythonHome" }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $PythonHome) | Out-Null
        [IO.Compression.ZipFile]::ExtractToDirectory($zip, $staging)
        Move-Item -LiteralPath $staging -Destination $PythonHome
        $checksReady = Test-Path -LiteralPath $python
    } catch {
        Write-Host 'The checks could not be installed. Everything else is ready. Click Set up again later to add them.'
        Write-Host "For Larsonix: $($_.Exception.Message)"
    } finally {
        if (-not $PythonZip -and $zip -and (Test-Path -LiteralPath $zip)) { Remove-Item -LiteralPath $zip -Force }
    }
}
if ($checksReady) {
    git -C $repo config armory.python ($python -replace '\\', '/')
    & $python (Join-Path $repo 'Studio\Tools\Checks\check.py') --spawn
    Write-Host 'The checks are installed. They run by themselves each time you commit.'
}

Write-Host ''
Write-Host 'Done.'
$blockbench = Join-Path $repo 'Studio\Tools\Blockbench\armory.js'
if (Test-Path -LiteralPath $blockbench) {
    Write-Host ''
    Write-Host 'Once, in Blockbench: click File > Plugins... > Load Plugin from File, and pick this file:'
    Write-Host "  $blockbench"
}
exit 0
