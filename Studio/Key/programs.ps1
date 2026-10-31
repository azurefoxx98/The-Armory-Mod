# Installs the key's programs from programs.json, each file checked against the sha256 its release page
# publishes. The builder runs it to make a key, and The Armory panel on the key runs it to offer every newer
# version listed, so a key that is built and a key that is updated hold the same programs.
#
#   programs.ps1 -Root L:\ -Install [-Cache <folder>]   install every listed program not yet installed
#   programs.ps1 -Root L:\ -List <file>                 writes the newer programs on offer to the file
#   programs.ps1 -Root L:\ -One <name>                  installs one of them
#
# A program is unpacked beside its place and swapped in whole, so a failed download or an unplugged key
# leaves the one already there. What each program keeps in its own folder (PhotoCraft's settings) moves
# to the new one. The versions installed are recorded in Key\key.json.
param(
    [Parameter(Mandatory)] [string] $Root,
    [switch] $Install,
    [string] $List = '',
    [string] $One = '',
    # Where downloads are kept and looked for first. Only the builder uses it.
    [string] $Cache = ''
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
. (Join-Path $PSScriptRoot 'key.ps1')

$keyJson = Join-Path $Root 'Key\key.json'
$sevenZip = Join-Path $Root 'Programs\7-Zip\7z.exe'

function Stop-Programs([string] $text, [string] $detail = '') {
    Write-Host ''
    Write-Host $text
    if ($detail) { Write-Host "For Larsonix: $detail" }
    if ($text.Contains('has been told')) {
        try { & (Join-Path $Root 'The-Armory-Mod\Studio\Tools\update.ps1') -Report "The key: $text" -Detail $detail } catch { }
    }
    exit 1
}

function Read-ProgramList {
    foreach ($place in @((Join-Path $Root 'The-Armory-Mod\Studio\Key\programs.json'), (Join-Path $Root 'Key\programs.json'))) {
        $list = Read-KeyJson $place
        if ($list -and $list.programs) { return @($list.programs) }
    }
    Stop-Programs 'The list of programs was not found on this key. You can keep working.' $Root
}

function Read-Installed {
    $key = Read-KeyJson $keyJson
    if ($null -eq $key) { $key = New-Object PSObject }
    if ($null -eq $key.programs) { Set-KeyJsonField $key 'programs' (New-Object PSObject) }
    return $key
}

function Save-Installed($key, [string] $name, [string] $version) {
    Set-KeyJsonField $key.programs $name $version
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $keyJson) | Out-Null
    Write-KeyText $keyJson ($key | ConvertTo-Json -Depth 10)
}

# A file of the list, from the cache when it is there and intact, else downloaded and checked.
function Get-ProgramFile($file, [string] $name) {
    $leaf = [IO.Path]::GetFileName(([Uri]$file.url).LocalPath)
    $folder = if ($Cache) { $Cache } else { [IO.Path]::GetTempPath() }
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    $path = Join-Path $folder $leaf
    if ((Test-Path -LiteralPath $path) -and (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -eq $file.sha256) { return $path }
    $ProgressPreference = 'SilentlyContinue'
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    try {
        Invoke-WebRequest -Uri $file.url -OutFile $path -UseBasicParsing
    } catch {
        Stop-Programs 'A download failed. Check your internet, then try again.' "$name, $($_.Exception.Message)"
    }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $file.sha256) {
        Remove-Item -LiteralPath $path -Force
        Stop-Programs 'A download arrived damaged, so it was refused. You can keep working.' "$($file.url) does not match its published checksum"
    }
    return $path
}

function Invoke-Quiet([string] $exe, [string[]] $arguments, [string] $what) {
    $ErrorActionPreference = 'Continue'
    $out = & $exe @arguments 2>&1
    if ($LASTEXITCODE -ne 0) { Stop-Programs "$what could not be installed. You can keep working." (($out | Select-Object -Last 5) -join ' ') }
}

# Git's own first step after unpacking, which its self-extractor runs: it must run from Git's folder. Its
# scripts may write in the user folder, so they get a folder of their own that is thrown away after.
function Complete-PortableGit([string] $folder) {
    $scratch = Join-Path ([IO.Path]::GetTempPath()) ('armory-git-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $scratch | Out-Null
    try {
        $info = New-Object Diagnostics.ProcessStartInfo
        $info.FileName = Join-Path $env:SystemRoot 'System32\cmd.exe'
        # By its full path: some computers never run a script by its bare name from the current folder.
        $info.Arguments = '/d /c ""' + (Join-Path $folder 'post-install.bat') + '""'
        $info.WorkingDirectory = $folder
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.EnvironmentVariables['HOME'] = $scratch
        $info.EnvironmentVariables['USERPROFILE'] = $scratch
        $process = [Diagnostics.Process]::Start($info)
        if (-not $process.WaitForExit(300000)) { $process.Kill(); Stop-Programs 'Git could not be installed. You can keep working.' 'post-install.bat did not finish' }
    } finally {
        Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath (Join-Path $folder 'post-install.bat')) { Stop-Programs 'Git could not be installed. You can keep working.' 'post-install.bat is still there' }
}

function Install-Program($program) {
    $name = $program.name
    $target = Join-Path $Root "Programs\$($program.folder)"
    $new = "$target.new"
    $old = "$target.old"
    $running = @(Get-KeyProcesses $Root | Where-Object { $_.Path.StartsWith("$target\", [StringComparison]::OrdinalIgnoreCase) })
    if ($running.Count) { Stop-Programs "$name is open. Close it, then try again." }
    foreach ($leftover in $new, $old) { if (Test-Path -LiteralPath $leftover) { Remove-Item -LiteralPath $leftover -Recurse -Force } }
    $files = @($program.files | ForEach-Object { Get-ProgramFile $_ $name })
    Write-Host "Installing $name $($program.version)..."
    New-Item -ItemType Directory -Force -Path $new | Out-Null
    switch ($program.kind) {
        '7zip' {
            Invoke-Quiet $files[0] @('x', $files[1], "-o$new", '-y') $name
            Get-ChildItem -LiteralPath $new | Where-Object { $_.Name -notin '7z.exe', '7z.dll', 'License.txt' } | Remove-Item -Recurse -Force
        }
        'portablegit' {
            Invoke-Quiet $sevenZip @('x', $files[0], "-o$new", '-y') $name
            Complete-PortableGit $new
        }
        'blockbench' {
            # The installer holds the program as one archive inside it. Unpacked, it runs from any folder.
            # Its updater needs app-update.yml, and without it the program never installs a newer version
            # by itself, which on a borrowed computer would run an installer at quit.
            $unpack = "$new.unpack"
            Invoke-Quiet $sevenZip @('x', $files[0], '$PLUGINSDIR\app-64.7z', "-o$unpack", '-y') $name
            Invoke-Quiet $sevenZip @('x', (Join-Path $unpack '$PLUGINSDIR\app-64.7z'), "-o$new", '-y') $name
            Remove-Item -LiteralPath $unpack -Recurse -Force
            Remove-Item -LiteralPath (Join-Path $new 'resources\app-update.yml') -Force -ErrorAction SilentlyContinue
        }
        'zip' {
            [IO.Compression.ZipFile]::ExtractToDirectory($files[0], $new)
            # A single folder at the top is unwrapped, so the program's place never names its version.
            $top = @(Get-ChildItem -LiteralPath $new -Force)
            if ($top.Count -eq 1 -and $top[0].PSIsContainer) {
                Get-ChildItem -LiteralPath $top[0].FullName -Force | Move-Item -Destination $new
                Remove-Item -LiteralPath $top[0].FullName -Force
            }
        }
        'exe' {
            Copy-Item -LiteralPath $files[0] -Destination (Join-Path $new $program.exe)
        }
        default { Stop-Programs "$name could not be installed. You can keep working." "unknown kind $($program.kind)" }
    }
    if ($program.exe -and -not (Test-Path -LiteralPath (Join-Path $new $program.exe))) {
        Stop-Programs "$name could not be installed. You can keep working." "no $($program.exe) after unpacking"
    }
    if ($program.keep -and (Test-Path -LiteralPath (Join-Path $target $program.keep))) {
        Move-Item -LiteralPath (Join-Path $target $program.keep) -Destination (Join-Path $new $program.keep)
    }
    if (Test-Path -LiteralPath $target) { Move-Item -LiteralPath $target -Destination $old }
    Move-Item -LiteralPath $new -Destination $target
    if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force -ErrorAction SilentlyContinue }
}

$programs = Read-ProgramList
# 7-Zip unpacks the others, so it comes first.
$programs = @($programs | Where-Object { $_.kind -eq '7zip' }) + @($programs | Where-Object { $_.kind -ne '7zip' })

if ($Install) {
    $key = Read-Installed
    foreach ($program in $programs) {
        $have = $key.programs.($program.name)
        $place = Join-Path $Root "Programs\$($program.folder)"
        if ($have -eq $program.version -and (Test-Path -LiteralPath $place)) { continue }
        Install-Program $program
        Save-Installed $key $program.name $program.version
    }
    exit 0
}

if ($List) {
    $key = Read-Installed
    $newer = @()
    foreach ($program in Read-ProgramList) {
        # HyCreator replaces itself when it finds a newer version, so it is never offered here.
        if ($program.updatesItself) { continue }
        if ($key.programs.($program.name) -ne $program.version) { $newer += @{ name = $program.name; version = $program.version } }
    }
    [IO.File]::WriteAllText($List, (ConvertTo-Json @{ newer = @($newer) } -Depth 4), (New-Object Text.UTF8Encoding $false))
    exit 0
}

if ($One) {
    $program = @(Read-ProgramList | Where-Object { $_.name -eq $One }) | Select-Object -First 1
    if (-not $program) { Stop-Programs "$One could not be installed. You can keep working." 'not on the list' }
    $key = Read-Installed
    Install-Program $program
    Save-Installed $key $program.name $program.version
    Write-Host "$One is installed."
    exit 0
}

Write-Host 'Run this from the key with -Install, -List or -One.'
exit 2
