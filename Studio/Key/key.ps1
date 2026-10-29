# What every part of Lady's USB key shares, loaded by start.ps1 and programs.ps1, and by the key's
# builder and its tests, so the key that is built and the key that runs set things up the same way.
#
# The key's folder holds:
#   Start The Armory.cmd   the one file she double-clicks
#   Programs\<name>\       the programs, from programs.json
#   Home\                  the key's own user folder: every program's settings and her GitHub sign-in
#   The-Armory-Mod\        her copy of the mod, on her branch
#   Key\                   key.json (what the key holds) and a copy of these scripts
#
# Two programs remember places as full paths in their own saved pages, which nothing outside them can
# safely rewrite: HyCreator its open mod folder, Blockbench the file of our plugin. So the key's folder
# gets the same drive letter on every computer, through Windows' own subst, which needs no administrator
# and is gone at Done or at sign-out. Everything kept in plain files is rewritten for the letter in use.

$KeyLetters = @('L', 'K', 'J', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y')
$KeyMainLetter = 'L'

# The game's folder, at the same place under the key's user folder as under a computer's, so every tool
# that looks for it there finds it with no change.
$KeyGamePath = 'Home\AppData\Roaming\Hytale\install\release\package\game\latest'

# The saved pages that hold a full path, copied aside on a computer where the key cannot have its letter.
$KeySavedPages = [ordered]@{
    HyCreator  = 'Home\AppData\Local\HyCreator\EBWebView\EBWebView\Default\Local Storage'
    Blockbench = 'Home\AppData\Roaming\Blockbench\Local Storage'
}

$KeyUtf8 = New-Object Text.UTF8Encoding $false

function Get-KeyFolder([string] $path) {
    $full = [IO.Path]::GetFullPath($path).TrimEnd('\')
    if ($full -match '^[A-Za-z]:$') { $full += '\' }
    return $full
}

function Get-KeyRoot([string] $letter) { return "$($letter):\" }

# The letters subst maps now, as letter to folder.
function Get-SubstMap {
    $map = @{}
    $lines = & "$env:SystemRoot\System32\subst.exe" 2>$null
    foreach ($line in $lines) {
        if ($line -match '^([A-Za-z]):\\: => (.+)$') { $map[$Matches[1].ToUpper()] = (Get-KeyFolder $Matches[2]) }
    }
    return $map
}

# Gives the key's folder its letter: the one it already has, else L:, else the first free letter of the
# list. Returns the letter, or nothing when no letter could be given.
function Mount-KeyLetter([string] $folder) {
    $folder = Get-KeyFolder $folder
    if ($folder -ieq (Get-KeyRoot $KeyMainLetter)) { return $KeyMainLetter }
    $map = Get-SubstMap
    foreach ($letter in $KeyLetters) {
        if ($map[$letter] -and $map[$letter] -ieq $folder) { return $letter }
    }
    # A letter left pointing at a folder that is gone, by a key unplugged without Done, serves nothing.
    foreach ($letter in @($map.Keys)) {
        if ($KeyLetters -contains $letter -and -not (Test-Path -LiteralPath $map[$letter])) {
            & "$env:SystemRoot\System32\subst.exe" "$($letter):" /d 2>$null | Out-Null
            $map.Remove($letter)
        }
    }
    $used = @([IO.DriveInfo]::GetDrives() | ForEach-Object { $_.Name.Substring(0, 1).ToUpper() })
    foreach ($letter in $KeyLetters) {
        if ($used -contains $letter -or $map.ContainsKey($letter)) { continue }
        & "$env:SystemRoot\System32\subst.exe" "$($letter):" $folder 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath "$($letter):\")) { return $letter }
    }
    return $null
}

function Dismount-KeyLetter([string] $letter, [string] $folder) {
    $map = Get-SubstMap
    if ($map[$letter] -and $map[$letter] -ieq (Get-KeyFolder $folder)) {
        & "$env:SystemRoot\System32\subst.exe" "$($letter):" /d 2>$null | Out-Null
    }
}

# Points every program started from now on at the key: its user folder, its settings folders, Gradle's
# and Java's home, and the key's Git first on the PATH. Java asks Windows for the real profile and ignores
# a moved user folder, so its home is named explicitly, or a release would leave a cache on the computer.
# The computer's own folders are kept in ARMORY_PC_*, where the key looks for the computer's game.
function Set-KeyEnvironment([string] $root) {
    foreach ($name in 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'PATH', 'JAVA_TOOL_OPTIONS', 'ARMORY_GAME') {
        if ($null -eq [Environment]::GetEnvironmentVariable("ARMORY_PC_$name")) {
            $value = [Environment]::GetEnvironmentVariable($name)
            if ($null -eq $value) { $value = '' }
            [Environment]::SetEnvironmentVariable("ARMORY_PC_$name", $value)
        }
    }
    $keyHome = Join-Path $root 'Home'
    $env:USERPROFILE = $keyHome
    $env:HOME = $keyHome
    $env:HOMEDRIVE = $root.Substring(0, 2)
    $env:HOMEPATH = '\Home'
    $env:APPDATA = Join-Path $keyHome 'AppData\Roaming'
    $env:LOCALAPPDATA = Join-Path $keyHome 'AppData\Local'
    $env:GRADLE_USER_HOME = Join-Path $keyHome '.gradle'
    $env:JAVA_TOOL_OPTIONS = ("$env:ARMORY_PC_JAVA_TOOL_OPTIONS -Duser.home=$keyHome").Trim()
    $env:ARMORY_GAME = $null
    $env:ARMORY_KEY = $root
    $env:PATH = (Join-Path $root 'Programs\Git\cmd') + ';' + $env:ARMORY_PC_PATH
}

function Invoke-KeyGit([string] $root, [string[]] $arguments) {
    $git = Join-Path $root 'Programs\Git\cmd\git.exe'
    $ErrorActionPreference = 'Continue'
    $out = & $git @arguments 2>&1
    return @{ Code = $LASTEXITCODE; Text = (($out | ForEach-Object { "$_" }) -join "`n").Trim() }
}

function Read-KeyJson([string] $path) {
    try { return [IO.File]::ReadAllText($path) | ConvertFrom-Json } catch { return $null }
}

function Write-KeyText([string] $path, [string] $text) {
    $temp = "$path.partial"
    [IO.File]::WriteAllText($temp, $text, $KeyUtf8)
    Move-Item -LiteralPath $temp -Destination $path -Force
}

function Set-KeyJsonField($object, [string] $name, $value) {
    if ($object.PSObject.Properties[$name]) { $object.$name = $value } else { $object | Add-Member -NotePropertyName $name -NotePropertyValue $value }
}

# Rewrites every path kept in a plain file for the letter in use. Git's settings are edited as files,
# because Git refuses to read the settings of a copy on a drive that records no owner until that copy
# is trusted, and trusting it is one of the settings written here. Changes nothing already right.
function Update-KeyPaths([string] $root) {
    $l = $root.Substring(0, 1)
    $gitconfig = Join-Path $root 'Home\.gitconfig'
    $repoConfig = Join-Path $root 'The-Armory-Mod\.git\config'
    $store = "$($l):/Home/.gcm/store"
    if ((Invoke-KeyGit $root @('config', '--file', $gitconfig, '--get', 'credential.plaintextStorePath')).Text -ne $store) {
        [void](Invoke-KeyGit $root @('config', '--file', $gitconfig, 'credential.plaintextStorePath', $store))
    }
    $trusted = "$($l):/The-Armory-Mod"
    $have = (Invoke-KeyGit $root @('config', '--file', $gitconfig, '--get-all', 'safe.directory')).Text -split "`n"
    if ($have -notcontains $trusted) { [void](Invoke-KeyGit $root @('config', '--file', $gitconfig, '--add', 'safe.directory', $trusted)) }
    if (Test-Path -LiteralPath $repoConfig) {
        $plugins = "$($l):/Home/AppData/Local/HyCreator/Plugins"
        if ((Invoke-KeyGit $root @('config', '--file', $repoConfig, '--get', 'armory.plugins')).Text -ne $plugins) {
            [void](Invoke-KeyGit $root @('config', '--file', $repoConfig, 'armory.plugins', $plugins))
        }
    }
    $settingsPath = Join-Path $root 'Home\AppData\Local\HyCreator\settings.json'
    $settings = Read-KeyJson $settingsPath
    if ($null -eq $settings) { $settings = New-Object PSObject }
    $apps = $settings.apps; if ($null -eq $apps) { $apps = New-Object PSObject }
    $sources = $settings.sources; if ($null -eq $sources) { $sources = New-Object PSObject }
    $blockbench = "$($l):/Programs/Blockbench/Blockbench.exe"
    $release = "$($l):/" + ($KeyGamePath -replace '\\', '/') + '/Assets.zip'
    if ($apps.blockbench -ne $blockbench -or $sources.release -ne $release) {
        Set-KeyJsonField $apps 'blockbench' $blockbench
        Set-KeyJsonField $sources 'release' $release
        Set-KeyJsonField $settings 'apps' $apps
        Set-KeyJsonField $settings 'sources' $sources
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $settingsPath) | Out-Null
        Write-KeyText $settingsPath ($settings | ConvertTo-Json -Depth 20)
    }
}

# The checks run after each commit only while the key holds the game's files. Without them their own
# message would tell her to start Hytale, which a borrowed computer may not even have.
function Set-KeyChecks([string] $root, [bool] $on) {
    $repoConfig = Join-Path $root 'The-Armory-Mod\.git\config'
    if (-not (Test-Path -LiteralPath $repoConfig)) { return }
    $python = "$($root.Substring(0, 1)):/Programs/Python/python.exe"
    $now = (Invoke-KeyGit $root @('config', '--file', $repoConfig, '--get', 'armory.python')).Text
    if ($on -and $now -ne $python) { [void](Invoke-KeyGit $root @('config', '--file', $repoConfig, 'armory.python', $python)) }
    if (-not $on -and $now) { [void](Invoke-KeyGit $root @('config', '--file', $repoConfig, '--unset-all', 'armory.python')) }
}

# ---- the game's files ---------------------------------------------------------------------------

function Get-KeyGame([string] $root) {
    $place = Join-Path $root $KeyGamePath
    if (Test-Path -LiteralPath (Join-Path $place 'Assets.zip')) { return $place }
    return $null
}

# This computer's game, looked for the way the checks look for it, with the computer's own folders.
function Find-PcGame {
    $candidates = @($env:ARMORY_PC_ARMORY_GAME)
    $local = $env:ARMORY_PC_LOCALAPPDATA
    if (-not $local) { $local = $env:LOCALAPPDATA }
    $roaming = $env:ARMORY_PC_APPDATA
    if (-not $roaming) { $roaming = $env:APPDATA }
    $settings = Read-KeyJson (Join-Path $local 'HyCreator\settings.json')
    if ($settings -and $settings.sources -and $settings.sources.release) {
        $release = [string]$settings.sources.release
        if ($release -match '\.zip$') { $candidates += Split-Path -Parent $release } else { $candidates += $release }
    }
    $candidates += Join-Path $roaming 'Hytale\install\release\package\game\latest'
    foreach ($place in $candidates) {
        if ($place -and (Test-Path -LiteralPath (Join-Path $place 'Assets.zip'))) { return (Get-Item -LiteralPath $place).FullName }
    }
    return $null
}

# The computer's game is worth copying when the key has none, or when the computer's is newer. A friend's
# older game never replaces a newer one on the key.
function Test-PcGameNewer([string] $pcGame, [string] $root) {
    $keyGame = Get-KeyGame $root
    if (-not $keyGame) { return $true }
    $theirs = (Get-Item -LiteralPath (Join-Path $pcGame 'Assets.zip')).LastWriteTimeUtc
    $ours = (Get-Item -LiteralPath (Join-Path $keyGame 'Assets.zip')).LastWriteTimeUtc
    return $theirs -gt $ours
}

function Get-FolderBytes([string] $folder) {
    $sum = 0L
    foreach ($f in Get-ChildItem -LiteralPath $folder -Recurse -File -Force -ErrorAction SilentlyContinue) { $sum += $f.Length }
    return $sum
}

# Copies the computer's game folder into the key beside its place, then swaps it in whole, so an unplugged
# key never holds half a game. $state is a synchronized hashtable the window reads: Done, Total, Error.
# When the old copy is in use (HyCreator reading it), the finished copy waits beside it, marked complete,
# and Complete-KeyGame swaps it in at the next start.
function Copy-KeyGame([string] $from, [string] $root, $state) {
    $place = Join-Path $root $KeyGamePath
    $partial = "$place.partial"
    try {
        $state.Total = Get-FolderBytes $from
        # A subst letter reports the free space and the file system of the drive it points into.
        $drive = New-Object IO.DriveInfo $root.Substring(0, 1)
        if ($drive.AvailableFreeSpace -lt ($state.Total + 300MB)) { $state.Error = 'space'; return }
        # FAT32, the format of many USB keys, holds no file of 4 GB or more, and the game's largest is close.
        $largest = (Get-ChildItem -LiteralPath $from -Recurse -File -Force | Measure-Object Length -Maximum).Maximum
        if ($drive.DriveFormat -eq 'FAT32' -and $largest -ge 4GB) { $state.Error = 'fat32'; return }
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $partial | Out-Null
        $buffer = New-Object byte[] (4MB)
        foreach ($file in Get-ChildItem -LiteralPath $from -Recurse -File -Force) {
            $target = Join-Path $partial $file.FullName.Substring($from.Length).TrimStart('\')
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            $in = [IO.File]::Open($file.FullName, 'Open', 'Read', 'ReadWrite')
            try {
                $out = [IO.File]::Create($target)
                try {
                    while (($n = $in.Read($buffer, 0, $buffer.Length)) -gt 0) { $out.Write($buffer, 0, $n); $state.Done += $n }
                } finally { $out.Dispose() }
            } finally { $in.Dispose() }
            [IO.File]::SetLastWriteTimeUtc($target, $file.LastWriteTimeUtc)
        }
        [IO.File]::WriteAllText((Join-Path $partial '.complete'), $from, $KeyUtf8)
        Complete-KeyGame $root | Out-Null
    } catch {
        $state.Error = $_.Exception.Message
    }
}

# Swaps a finished copy into place. Returns $true when the key's game is in place and complete.
function Complete-KeyGame([string] $root) {
    $place = Join-Path $root $KeyGamePath
    $partial = "$place.partial"
    if (-not (Test-Path -LiteralPath (Join-Path $partial '.complete'))) {
        if ((Test-Path -LiteralPath $partial)) { Remove-Item -LiteralPath $partial -Recurse -Force -ErrorAction SilentlyContinue }
        return [bool](Get-KeyGame $root)
    }
    $old = "$place.old"
    try {
        if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force }
        if (Test-Path -LiteralPath $place) { Move-Item -LiteralPath $place -Destination $old }
        Move-Item -LiteralPath $partial -Destination $place
        Remove-Item -LiteralPath (Join-Path $place '.complete') -Force
        if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force -ErrorAction SilentlyContinue }
        return $true
    } catch {
        if (-not (Test-Path -LiteralPath $place) -and (Test-Path -LiteralPath $old)) { Move-Item -LiteralPath $old -Destination $place }
        return $false
    }
}

# ---- the saved pages, on a computer where the key has another letter ---------------------------------

function Save-KeyPages([string] $root) {
    $aside = Join-Path $root 'Key\saved-pages'
    if (Test-Path -LiteralPath $aside) { return }
    foreach ($name in $KeySavedPages.Keys) {
        $from = Join-Path $root $KeySavedPages[$name]
        if (Test-Path -LiteralPath $from) {
            New-Item -ItemType Directory -Force -Path (Join-Path $aside $name) | Out-Null
            Copy-Item -LiteralPath $from -Destination (Join-Path $aside $name) -Recurse -Force
        }
    }
    New-Item -ItemType Directory -Force -Path $aside | Out-Null
}

# Puts the saved pages back. Only while their programs are closed, or the programs would write over them.
function Restore-KeyPages([string] $root) {
    $aside = Join-Path $root 'Key\saved-pages'
    if (-not (Test-Path -LiteralPath $aside)) { return }
    foreach ($name in $KeySavedPages.Keys) {
        $saved = Join-Path (Join-Path $aside $name) (Split-Path -Leaf $KeySavedPages[$name])
        $place = Join-Path $root $KeySavedPages[$name]
        if (Test-Path -LiteralPath $saved) {
            if (Test-Path -LiteralPath $place) { Remove-Item -LiteralPath $place -Recurse -Force }
            Copy-Item -LiteralPath $saved -Destination $place -Recurse -Force
        }
    }
    Remove-Item -LiteralPath $aside -Recurse -Force
}

# ---- her work and the programs -------------------------------------------------------------------

# Commits on this copy that GitHub does not have, and files changed but not committed.
function Get-KeyWork([string] $root) {
    $repo = Join-Path $root 'The-Armory-Mod'
    $branch = (Invoke-KeyGit $root @('-C', $repo, 'rev-parse', '--abbrev-ref', 'HEAD')).Text
    $unsent = 0
    $range = 'HEAD'
    if ((Invoke-KeyGit $root @('-C', $repo, 'rev-parse', '--verify', '--quiet', "origin/$branch")).Code -eq 0) { $range = "origin/$branch..HEAD" }
    $count = Invoke-KeyGit $root @('-C', $repo, 'rev-list', '--count', $range)
    if ($count.Code -eq 0) { $unsent = [int]$count.Text }
    $changed = @((Invoke-KeyGit $root @('-C', $repo, 'status', '--porcelain', '--untracked-files=no')).Text -split "`n" | Where-Object { $_ }).Count
    return @{ Branch = $branch; Unsent = $unsent; Changed = $changed }
}

# Whether the server her copy sends to answers: GitHub, or for the tests a folder, which always does.
function Test-KeyOnline([string] $root) {
    $url = (Invoke-KeyGit $root @('-C', (Join-Path $root 'The-Armory-Mod'), 'remote', 'get-url', 'origin')).Text
    $server = 'github.com'
    if ($url -match '^https?://([^/:]+)') { $server = $Matches[1] }
    elseif ($url -match '^[A-Za-z]:[\\/]|^\\\\|^file:') { return $true }
    $client = New-Object Net.Sockets.TcpClient
    try {
        $wait = $client.BeginConnect($server, 443, $null, $null)
        if (-not $wait.AsyncWaitHandle.WaitOne(3000)) { return $false }
        $client.EndConnect($wait)
        return $true
    } catch { return $false } finally { $client.Close() }
}

# The key's programs now running: every process started from the key's Programs folder, or with
# -Everywhere from anywhere on the key (the Java a release installs lives in the key's user folder).
function Get-KeyProcesses([string] $root, [switch] $Everywhere) {
    $from = if ($Everywhere) { $root.TrimEnd('\') + '\' } else { (Join-Path $root 'Programs').TrimEnd('\') + '\' }
    return @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
        try { $_.Path -and $_.Path.StartsWith($from, [StringComparison]::OrdinalIgnoreCase) } catch { $false }
    })
}

# HyCreator's page runs in Windows' own web browser part, started from the computer's folders, not the
# key's. It writes HyCreator's saved pages on the key and ends a moment after HyCreator, so the key is
# only safe to eject once these are gone.
function Get-KeyBrowsers([string] $root) {
    $data = (Join-Path $root 'Home').TrimEnd('\') + '\'
    return @(Get-CimInstance Win32_Process -Filter "Name='msedgewebview2.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($data, [StringComparison]::OrdinalIgnoreCase) -ge 0 })
}

# Removes Windows' "downloaded from the internet" mark from every file of the key, which a zip unpacked by
# Windows gives every file it unpacks. Windows warns before it runs a marked program, so this leaves the
# key as quiet as it was built. Only when the given file carries the mark, so it runs once.
function Test-InternetMark([string] $path) {
    try { $null = Get-Content -LiteralPath $path -Stream Zone.Identifier -ErrorAction Stop; return $true } catch { return $false }
}

# Windows' own file calls, because a command per file is slow over tens of thousands of files on a key.
# A read-only file (Git keeps its objects so) refuses to lose its mark until the flag is lifted, so the
# flag is lifted and put back around it. Folders Windows keeps to itself, and links to other folders,
# are passed over instead of ending the walk.
function Clear-InternetMark([string] $folder, [scriptblock] $progress) {
    if (-not ('KeyMark' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
public static class KeyMark {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool DeleteFileW(string path);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern uint GetFileAttributesW(string path);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool SetFileAttributesW(string path, uint attributes);
    public static bool Clear(string path) {
        string stream = path + ":Zone.Identifier";
        if (DeleteFileW(stream)) return true;
        if (Marshal.GetLastWin32Error() != 5) return false;
        uint attributes = GetFileAttributesW(path);
        if (attributes == 0xFFFFFFFF || (attributes & 1) == 0) return false;
        SetFileAttributesW(path, attributes & ~1u);
        bool cleared = DeleteFileW(stream);
        SetFileAttributesW(path, attributes);
        return cleared;
    }
    public static List<string> Files(string root) {
        var files = new List<string>();
        var folders = new Stack<string>();
        folders.Push(root);
        while (folders.Count > 0) {
            string folder = folders.Pop();
            try {
                files.AddRange(Directory.GetFiles(folder));
                foreach (string inner in Directory.GetDirectories(folder)) {
                    if ((File.GetAttributes(inner) & FileAttributes.ReparsePoint) == 0) folders.Push(inner);
                }
            } catch (UnauthorizedAccessException) { } catch (IOException) { }
        }
        return files;
    }
}
'@
    }
    $n = 0
    foreach ($file in [KeyMark]::Files($folder)) {
        [void][KeyMark]::Clear($file)
        $n++
        if ($progress -and $n % 1000 -eq 0) { & $progress $n }
    }
    return $n
}

# Gradle's folder travels inside the key as one archive (Key\gradle.zip), because its deepest paths run past
# the 260 characters Windows' own unzip can write when the key is unpacked in a long folder: it skips such
# files without a word. Unpacked under the key's short letter, they fit. Returns $true when it is in place.
function Expand-KeyGradle([string] $root) {
    $archive = Join-Path $root 'Key\gradle.zip'
    $place = Join-Path $root 'Home\.gradle'
    if (-not (Test-Path -LiteralPath $archive)) { return (Test-Path -LiteralPath $place) }
    $partial = "$place.partial"
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Recurse -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($archive, $partial)
    if (Test-Path -LiteralPath $place) { Remove-Item -LiteralPath $place -Recurse -Force }
    Move-Item -LiteralPath $partial -Destination $place
    Remove-Item -LiteralPath $archive -Force
    return $true
}

# ---- this computer's Hytale playing the mod on the key -------------------------------------------
#
# Set up in The Armory panel (play.ps1) links this computer's Hytale to the mod on the key, the way setup.ps1
# links it to her copy on her own computer. Any other copy of the mod moves aside meanwhile, because the game
# shuts a world down when two packs share one name. Done puts everything back. So does an unplugged key,
# through the window that still runs, the next start of the key, or Set up on her own computer. Everything
# to put back is written on the computer, in the record below, so it can be put back once the key is gone.

$KeyPlayAside = 'Armory set aside\While the key plays'
$KeyPlayRecord = 'Armory set aside\key.json'

# Where this computer's Hytale keeps its mods and worlds. Only tests point it elsewhere.
function Get-PcUserData {
    if ($env:ARMORY_KEY_TEST_USERDATA) { return $env:ARMORY_KEY_TEST_USERDATA }
    $roaming = $env:ARMORY_PC_APPDATA
    if (-not $roaming) { $roaming = $env:APPDATA }
    return Join-Path $roaming 'Hytale\UserData'
}

# This computer and this Windows user, so a yes given on one computer never links the game of another.
function Get-KeyComputerId {
    $user = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    return "$env:COMPUTERNAME|$user"
}

function Test-KeyComputerAllowed([string] $root) {
    $list = Read-KeyJson (Join-Path $root 'Key\computers.json')
    return ($list -and @($list.computers) -contains (Get-KeyComputerId))
}

function Add-KeyComputer([string] $root) {
    $path = Join-Path $root 'Key\computers.json'
    $list = Read-KeyJson $path
    $ids = @(); if ($list) { $ids = @($list.computers | Where-Object { $_ }) }
    $id = Get-KeyComputerId
    if ($ids -notcontains $id) { Write-KeyText $path (@{ computers = @($ids + $id) } | ConvertTo-Json) }
}

# The game's own name for a mod, Group and Name in its manifest.json, as setup.ps1 reads it.
function Read-KeyModIdentity([string] $path) {
    try {
        $item = Get-Item -LiteralPath $path -Force
        if ($item.PSIsContainer) {
            $file = Join-Path $path 'manifest.json'
            if (-not (Test-Path -LiteralPath $file)) { return $null }
            $text = [IO.File]::ReadAllText($file)
        } elseif ($item.Extension -in '.jar', '.zip') {
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            $zip = [IO.Compression.ZipFile]::OpenRead($item.FullName)
            try {
                $entry = $zip.GetEntry('manifest.json')
                if (-not $entry) { return $null }
                $reader = New-Object IO.StreamReader($entry.Open())
                $text = $reader.ReadToEnd()
                $reader.Close()
            } finally { $zip.Dispose() }
        } else {
            return $null
        }
        $manifest = $text | ConvertFrom-Json
        return "$($manifest.Group):$($manifest.Name)"
    } catch { return $null }
}

# Where a link points, or nothing for a plain file or folder. It reads a link whose folder is gone too.
function Get-KeyLinkTarget([string] $path) {
    $item = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    if (-not $item -or -not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { return $null }
    return [string]($item.Target | Select-Object -First 1)
}

function Test-KeyExists([string] $path) { return [bool](Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue) }

# The game holds the mod's files and its code open while it runs, so the link changes only while it is closed.
function Test-HytaleRunning {
    if ($env:ARMORY_KEY_TEST_HYTALE) { return $env:ARMORY_KEY_TEST_HYTALE -eq 'running' }
    return [bool](Get-Process -Name 'HytaleClient' -ErrorAction SilentlyContinue)
}

# What linking this computer's game to the key needs: the game's folders, and every other copy of the mod in
# them, in the shared mods folder and in each world's own. Returns nothing when this computer has no Hytale.
function Get-KeyPlayPlan([string] $keyFolder, [string] $userData = (Get-PcUserData)) {
    if (-not (Test-Path -LiteralPath $userData)) { return $null }
    $mods = Join-Path $userData 'Mods'
    $plan = [ordered]@{
        UserData = $userData; Mods = $mods
        Resources = Join-Path $keyFolder 'The-Armory-Mod\src\main\resources'
        Link = Join-Path $mods 'TheArmory'; Jar = Join-Path $mods 'TheArmory-code.jar'
        RecordPath = Join-Path $userData $KeyPlayRecord; Record = Read-KeyJson (Join-Path $userData $KeyPlayRecord)
        Others = @()
    }
    $identity = Read-KeyModIdentity $plan.Resources
    $folders = @(@($mods) | Where-Object { Test-Path -LiteralPath $_ })
    $saves = Join-Path $userData 'Saves'
    if (Test-Path -LiteralPath $saves) {
        $folders += Get-ChildItem -LiteralPath $saves -Directory | ForEach-Object { Join-Path $_.FullName 'mods' } |
            Where-Object { Test-Path -LiteralPath $_ }
    }
    foreach ($folder in $folders) {
        foreach ($entry in Get-ChildItem -LiteralPath $folder -Force) {
            if ($entry.FullName -ieq $plan.Jar) { continue }
            if ($entry.FullName -ieq $plan.Link) {
                $target = Get-KeyLinkTarget $plan.Link
                # The key's own link: already right, or left by an earlier start of the key and replaced below.
                if ($target -and ($target -ieq $plan.Resources -or ($plan.Record -and $target -ieq $plan.Record.target))) { continue }
                # Anything else at the key's name has to move, or the link cannot be made.
                $plan.Others += $entry.FullName
                continue
            }
            if ($identity -and (Read-KeyModIdentity $entry.FullName) -eq $identity) { $plan.Others += $entry.FullName }
        }
    }
    return $plan
}

function Save-KeyPlayRecord($plan, $record) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $plan.RecordPath) | Out-Null
    Write-KeyText $plan.RecordPath ($record | ConvertTo-Json -Depth 5)
}

# Links this computer's game to the key: the other copies aside, the computer's own code jar aside, the key's
# code jar in, the link to the key's mod. The record is written before each step, so a key unplugged halfway
# still leaves everything findable. Returns the plan, with Done set when the link is in place.
function Connect-KeyGame([string] $root, [string] $keyFolder, [string] $userData = (Get-PcUserData)) {
    $plan = Get-KeyPlayPlan $keyFolder $userData
    if (-not $plan) { return $null }
    $record = $plan.Record
    if (-not $record) { $record = [pscustomobject]@{ target = $null; key = $keyFolder; moved = @(); jar = $null; ourJar = $false } }
    $record.key = $keyFolder
    $aside = Join-Path $plan.UserData $KeyPlayAside
    foreach ($path in $plan.Others) {
        $parent = Split-Path -Parent $path
        $from = if ($parent -ieq $plan.Mods) { 'Mods' } else { 'World ' + (Split-Path -Leaf (Split-Path -Parent $parent)) }
        $place = Join-Path $aside ($from + ' - ' + (Split-Path -Leaf $path))
        if (Test-KeyExists $place) { $place += ' (' + (Get-Date -Format 'yyyy-MM-dd HH-mm-ss') + ')' }
        $record.moved = @($record.moved) + [pscustomobject]@{ from = $path; to = $place }
        Save-KeyPlayRecord $plan $record
        New-Item -ItemType Directory -Force -Path $aside | Out-Null
        Move-Item -LiteralPath $path -Destination $place
    }
    # The code jar has one fixed name, so the computer's own is kept aside once and comes back at the end.
    if (-not $record.ourJar) {
        if (Test-KeyExists $plan.Jar) {
            $record.jar = Join-Path $aside ('Mods - ' + (Split-Path -Leaf $plan.Jar))
            Save-KeyPlayRecord $plan $record
            New-Item -ItemType Directory -Force -Path $aside | Out-Null
            Move-Item -LiteralPath $plan.Jar -Destination $record.jar -Force
        }
        $record.ourJar = $true
        Save-KeyPlayRecord $plan $record
    }
    New-Item -ItemType Directory -Force -Path $plan.Mods | Out-Null
    Copy-Item -LiteralPath (Join-Path $keyFolder 'The-Armory-Mod\Studio\Tools\TheArmory-code.jar') -Destination $plan.Jar -Force
    if ((Get-KeyLinkTarget $plan.Link) -ine $plan.Resources) {
        if (Test-KeyExists $plan.Link) { [IO.Directory]::Delete($plan.Link, $false) }
        $record.target = $plan.Resources
        Save-KeyPlayRecord $plan $record
        New-Item -ItemType Junction -Path $plan.Link -Target $plan.Resources | Out-Null
    }
    $record.target = $plan.Resources
    Save-KeyPlayRecord $plan $record
    # The key's copy copies its code jar to the game after each update, as her own computer's copy does.
    [void](Invoke-KeyGit $root @('config', '--file', (Join-Path $root 'The-Armory-Mod\.git\config'), 'armory.mods', $plan.Mods))
    $plan.Record = $record
    $plan.Done = $true
    return $plan
}

# Puts this computer's game back as it was before the key: the key's link and code jar out, everything moved
# aside back in its place. Needs only the record, so it works with the key gone. Returns $true when nothing is
# left to put back. What cannot move yet (the game holds its code jar open) stays in the record for next time.
function Disconnect-KeyGame([string] $userData = (Get-PcUserData)) {
    $recordPath = Join-Path $userData $KeyPlayRecord
    $record = Read-KeyJson $recordPath
    if (-not $record) { return $true }
    $mods = Join-Path $userData 'Mods'
    $link = Join-Path $mods 'TheArmory'
    $jar = Join-Path $mods 'TheArmory-code.jar'
    $left = $false
    $target = Get-KeyLinkTarget $link
    if ($target -and $record.target -and $target -ieq $record.target) {
        try { [IO.Directory]::Delete($link, $false) } catch { $left = $true }
    }
    if ($record.ourJar) {
        try {
            if (Test-KeyExists $jar) { Remove-Item -LiteralPath $jar -Force }
            if ($record.jar -and (Test-KeyExists $record.jar)) { Move-Item -LiteralPath $record.jar -Destination $jar }
            $record.ourJar = $false; $record.jar = $null
        } catch { $left = $true }
    }
    $still = @()
    foreach ($m in @($record.moved | Where-Object { $_ })) {
        # Gone from aside, or its place taken again meanwhile (Set up on her own computer remakes its link):
        # either way it stays where it is, in the set aside folder, and nothing is overwritten.
        if (-not (Test-KeyExists $m.to) -or (Test-KeyExists $m.from)) { continue }
        try {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $m.from) | Out-Null
            Move-Item -LiteralPath $m.to -Destination $m.from
        } catch { $still += $m; $left = $true }
    }
    $record.moved = $still
    if ($left) {
        try { Write-KeyText $recordPath ($record | ConvertTo-Json -Depth 5) } catch { }
        return $false
    }
    Remove-Item -LiteralPath $recordPath -Force -ErrorAction SilentlyContinue
    $aside = Join-Path $userData $KeyPlayAside
    if ((Test-Path -LiteralPath $aside) -and -not (Get-ChildItem -LiteralPath $aside -Force)) { Remove-Item -LiteralPath $aside -Force }
    return $true
}

# The key's copy forgets this computer's game, so after Done or on another computer the panel shows Set up again.
function Clear-KeyGamePath([string] $root) {
    $repoConfig = Join-Path $root 'The-Armory-Mod\.git\config'
    if (-not (Test-Path -LiteralPath $repoConfig)) { return }
    if ((Invoke-KeyGit $root @('config', '--file', $repoConfig, '--get', 'armory.mods')).Text) {
        [void](Invoke-KeyGit $root @('config', '--file', $repoConfig, '--unset-all', 'armory.mods'))
    }
}
