# The Armory's window on Lady's USB key. Start The Armory.cmd, at the top of the key, runs it.
#
# What it does, in order:
#   0. Stops with a plain message when the key was unzipped only in part.
#   1. Opens once per key: starting it again brings the open window forward.
#   2. Gives the key's folder the letter L:, and points every program it starts at the key: their
#      settings, her sign-in and her copy of the mod all stay on it, and nothing is written to the computer.
#   3. The first time after the key's zip was unpacked by Windows, removes the "downloaded" mark from every
#      file, so Windows never warns before its programs again, and unpacks Gradle's folder.
#   4. Rewrites the paths kept in plain files for the letter in use.
#   5. Looks for Hytale on this computer and copies its files to the key, in the background, when the
#      key has none or older ones.
#   6. On a computer where she clicked Set up once, links its Hytale to the mod on the key (key.ps1).
#   7. Shows the window and starts HyCreator. Its buttons start the programs and Get updates, with the
#      key's Git. Her Update, Check, Release and Set up are buttons of The Armory panel inside HyCreator.
#   8. Done: closes the programs normally, sends her committed work, puts this computer's Hytale back as it
#      was, removes the letter and ejects the key. An unplugged key puts Hytale back too.
param(
    # The key's folder as this computer sees it.
    [Parameter(Mandatory)] [string] $Key
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$title = 'The Armory'
# Reaches us by itself, through Update's report, and never waits or stops the window.
function Send-KeyProblem([string] $text) {
    try {
        $info = New-Object Diagnostics.ProcessStartInfo
        $info.FileName = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $update = Join-Path $Key 'The-Armory-Mod\Studio\Tools\update.ps1'
        $info.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$update`" -Report `"The key: $($text -replace '"', '')`""
        $info.UseShellExecute = $false; $info.CreateNoWindow = $true
        [void][Diagnostics.Process]::Start($info)
    } catch { }
}
# Anything unexpected is said in a window, since the console it would print in is hidden.
trap {
    Send-KeyProblem "The Armory stopped on something unexpected."
    [void][Windows.Forms.MessageBox]::Show("The Armory stopped on something unexpected. Larsonix has been told, and you can keep working.`n`nFor Larsonix: $($_.InvocationInfo.ScriptName):$($_.InvocationInfo.ScriptLineNumber) $($_.Exception.Message)", $title)
    exit 0
}
# The console of Start The Armory.cmd, hidden now that this runs. It stays open behind, and shows again
# only if this could not run at all.
if (-not ('KeyConsole' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class KeyConsole {
    [DllImport("kernel32.dll")] static extern IntPtr GetConsoleWindow();
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hwnd, int show);
    public static void Hide() { IntPtr h = GetConsoleWindow(); if (h != IntPtr.Zero) ShowWindow(h, 0); }
}
'@
}
[KeyConsole]::Hide()
. (Join-Path $PSScriptRoot 'key.ps1')
$Key = Get-KeyFolder $Key
$testing = [bool]$env:ARMORY_KEY_TEST

function Show-Message([string] $text, [string] $buttons = 'OK') {
    # Tests read what a message said from this file, since no window shows on a hidden desktop.
    if ($testing -and $env:ARMORY_KEY_TEST_SAID) { [IO.File]::AppendAllText($env:ARMORY_KEY_TEST_SAID, "$text`n", $KeyUtf8) }
    if ($testing) { if ($env:ARMORY_KEY_TEST_ANSWER) { return $env:ARMORY_KEY_TEST_ANSWER } else { return 'Yes' } }
    return [string][Windows.Forms.MessageBox]::Show($text, $title, $buttons)
}

# Every button and the clock run through this. A failure inside a window's event would otherwise reach
# her as the .NET error box, and on a hidden desktop it waits for a click that never comes.
function Invoke-Safely([scriptblock] $block) {
    try { & $block } catch {
        Send-KeyProblem "The Armory stopped on something unexpected."
        [void](Show-Message "The Armory stopped on something unexpected. Larsonix has been told, and you can keep working.`n`nFor Larsonix: $($_.InvocationInfo.ScriptLineNumber) $($_.Exception.Message)")
    }
}

# 0. A key unzipped only in part, because the unzip stopped or the key ran out of room, says so before
# anything starts. Otherwise it reads as one program gone, and its button would be tried again.
$missing = @()
$listed = Read-KeyJson (Join-Path $PSScriptRoot 'programs.json')
foreach ($program in @($listed.programs)) {
    if (-not $program) { continue }
    $place = Join-Path $Key "Programs\$($program.folder)"
    if ($program.exe) { $place = Join-Path $place $program.exe }
    if (-not (Test-Path -LiteralPath $place)) { $missing += $program.name }
}
if (-not (Test-Path -LiteralPath (Join-Path $Key 'Programs\Python\python.exe'))) { $missing += 'Python' }
if (-not (Test-Path -LiteralPath (Join-Path $Key 'The-Armory-Mod\.git'))) { $missing += 'your copy of the mod' }
if ($missing.Count) {
    $names = if ($missing.Count -eq 1) { $missing[0] } else { ($missing[0..($missing.Count - 2)] -join ', ') + ' and ' + $missing[-1] }
    [void](Show-Message "This key is not fully unzipped. Missing: $names.`n`nDelete the folder The Armory key from the key. Then unzip the zip onto the key again, and wait until Windows has finished.`n`nThe key needs at least 7 GB free: 2 GB for this, and 5 GB for Hytale's files later.`n`n")
    exit 0
}

# 1. One window per key.
$mutex = New-Object Threading.Mutex($false, 'Local\TheArmoryKey')
if (-not $mutex.WaitOne(0)) {
    try { [void](New-Object -ComObject WScript.Shell).AppActivate($title) } catch { }
    exit 0
}

# 2. The letter and the environment.
$letter = Mount-KeyLetter $Key
if (-not $letter) {
    Send-KeyProblem "The key could not get a drive letter on this computer."
    [void](Show-Message "The key could not get a drive letter on this computer. Larsonix has been told, and you can keep working.`n`nFor Larsonix: no free letter among $($KeyLetters -join ', ')")
    exit 0
}
$root = Get-KeyRoot $letter
$elsewhere = $letter -ne $KeyMainLetter
Set-KeyEnvironment $root
# The key's folder by its own drive, for the link from this computer's game, which must not depend on the letter.
$env:ARMORY_KEY_FOLDER = $Key
# The window itself holds no folder of the key open, so the key can be ejected at Done.
Set-Location -LiteralPath $env:SystemRoot

# 3. The first start after the zip was unpacked, with a small window saying so while it runs: Windows'
# "downloaded" mark removed from every file, and Gradle's folder unpacked from its archive. Both go
# through the key's letter, whose short paths every file fits under.
$marked = Test-InternetMark (Join-Path $Key 'Start The Armory.cmd')
$packed = Test-Path -LiteralPath (Join-Path $root 'Key\gradle.zip')
if ($marked -or $packed) {
    $splash = New-Object Windows.Forms.Form
    $splash.Text = $title; $splash.Width = 420; $splash.Height = 120; $splash.StartPosition = 'CenterScreen'
    $splash.FormBorderStyle = 'FixedDialog'; $splash.ControlBox = $false
    $line = New-Object Windows.Forms.Label
    $line.Dock = 'Fill'; $line.TextAlign = 'MiddleCenter'; $line.Font = New-Object Drawing.Font('Segoe UI', 10)
    $line.Text = 'Getting the key ready. This happens only the first time.'
    $splash.Controls.Add($line)
    $splash.Show(); [Windows.Forms.Application]::DoEvents()
    if ($marked) { [void](Clear-InternetMark $root { param($n) [Windows.Forms.Application]::DoEvents() }) }
    if ($packed) {
        $unpack = [powershell]::Create()
        [void]$unpack.AddScript({ param($library, $root) . $library; Expand-KeyGradle $root }).AddArgument((Join-Path $PSScriptRoot 'key.ps1')).AddArgument($root)
        $running = $unpack.BeginInvoke()
        while (-not $running.IsCompleted) { Start-Sleep -Milliseconds 100; [Windows.Forms.Application]::DoEvents() }
        # A failed unpack leaves the archive for the next start. Meanwhile a release fetches what it needs.
        try { [void]$unpack.EndInvoke($running) } catch { }
    }
    $splash.Close()
}
$repo = Join-Path $root 'The-Armory-Mod'
# These scripts, through the key's letter: from her copy of the mod, or the key's own copy in Key\.
$scripts = $PSScriptRoot
if ($scripts.StartsWith($Key, [StringComparison]::OrdinalIgnoreCase)) { $scripts = Join-Path $root $scripts.Substring($Key.Length).TrimStart('\') }
$programList = Read-KeyJson (Join-Path $scripts 'programs.json')
# The key's spare copy follows her copy of the mod, so it is current whenever it is needed.
$spare = Join-Path $root 'Key'
if ($scripts -ieq (Join-Path $repo 'Studio\Key')) {
    foreach ($file in Get-ChildItem -LiteralPath $scripts -File) {
        $copy = Join-Path $spare $file.Name
        if (-not (Test-Path -LiteralPath $copy) -or (Get-FileHash -LiteralPath $copy).Hash -ne (Get-FileHash -LiteralPath $file.FullName).Hash) {
            try { Copy-Item -LiteralPath $file.FullName -Destination $copy -Force } catch { }
        }
    }
}
$stateFile = Join-Path $root 'Key\window.txt'

# 4. The paths kept in plain files, and the saved pages on a computer where the key has another letter.
$nothingOpen = (Get-KeyProcesses $root).Count -eq 0
if ($nothingOpen) {
    if ($elsewhere) { Save-KeyPages $root } else { Restore-KeyPages $root }
}
Update-KeyPaths $root

# 5. The game's files.
if ($nothingOpen) { [void](Complete-KeyGame $root) }
$script:hasGame = [bool](Get-KeyGame $root)
Set-KeyChecks $root $script:hasGame
$script:copy = $null
$pcGame = Find-PcGame
if ($pcGame -and (Test-PcGameNewer $pcGame $root)) {
    $state = [hashtable]::Synchronized(@{ Done = 0L; Total = 0L; Error = $null; Finished = $false })
    $runspace = [runspacefactory]::CreateRunspace(); $runspace.Open()
    $shell = [powershell]::Create(); $shell.Runspace = $runspace
    [void]$shell.AddScript({
        param($library, $from, $root, $state)
        . $library
        try { Copy-KeyGame $from $root $state } finally { $state.Finished = $true }
    }).AddArgument((Join-Path $PSScriptRoot 'key.ps1')).AddArgument($pcGame).AddArgument($root).AddArgument($state)
    $script:copy = @{ State = $state; Shell = $shell; Handle = $shell.BeginInvoke(); Reported = $false }
}

# 6. This computer's Hytale. After her yes here (Set up in The Armory panel), the key links the game to its mod
# at every start. Elsewhere the key's copy forgets any earlier computer's game, so the panel offers Set up.
# While Hytale is open its files are in use, so the link waits until it closes.
$script:playWaiting = $false
$script:playError = $null
function Start-KeyPlay {
    $script:playWaiting = $false
    if (-not (Test-KeyComputerAllowed $root) -or -not (Test-Path -LiteralPath (Get-PcUserData))) { Clear-KeyGamePath $root; return }
    if (Test-HytaleRunning) { $script:playWaiting = $true; return }
    try { [void](Connect-KeyGame $root $Key); $script:playError = $null } catch { $script:playError = "$($_.InvocationInfo.ScriptLineNumber) $($_.Exception.Message)" }
}
Start-KeyPlay

# What the game plays now: the key's mod, or not, read from the computer itself, since Set up runs apart.
function Test-KeyPlaying {
    $target = Get-KeyLinkTarget (Join-Path (Get-PcUserData) 'Mods\TheArmory')
    return ($target -and $target -ieq (Join-Path $Key 'The-Armory-Mod\src\main\resources'))
}

# ---- starting things ------------------------------------------------------------------------

function Get-ProgramExe([string] $name) {
    $entry = @($programList.programs | Where-Object { $_.name -eq $name }) | Select-Object -First 1
    if (-not $entry) { return $null }
    return Join-Path $root "Programs\$($entry.folder)\$($entry.exe)"
}

# A program of the key, started from the key with its environment, or brought forward when it runs.
function Open-Program([string] $name) {
    $exe = Get-ProgramExe $name
    if (-not $exe -or -not (Test-Path -LiteralPath $exe)) {
        Send-KeyProblem "$name is missing from this key."
        [void](Show-Message "$name is missing from this key. Larsonix has been told, and you can keep working.")
        return
    }
    $running = @(Get-KeyProcesses $root | Where-Object { $_.Path -ieq $exe -and $_.MainWindowHandle -ne 0 }) | Select-Object -First 1
    if ($running) {
        try { [void](New-Object -ComObject WScript.Shell).AppActivate($running.Id) } catch { }
        return
    }
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $exe
    $info.WorkingDirectory = $root
    $info.UseShellExecute = $false
    [void][Diagnostics.Process]::Start($info)
}

# Her own scripts, in a window of their own, with the key's Git.
function Open-Console([string] $file, [string[]] $arguments) {
    if ($file -like '*.ps1') {
        $all = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$file`"") + $arguments
        Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -ArgumentList $all -WorkingDirectory $repo
    } else {
        Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\cmd.exe') -ArgumentList @('/c', "`"$file`"") -WorkingDirectory $repo
    }
}

# ---- the window ------------------------------------------------------------------------------

$font = New-Object Drawing.Font('Segoe UI', 10)
$form = New-Object Windows.Forms.Form
$form.Text = $title; $form.Width = 560; $form.Height = 490; $form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedSingle'; $form.MaximizeBox = $false; $form.Font = $font

function New-Heading([string] $text, [int] $y) {
    $label = New-Object Windows.Forms.Label
    $label.Text = $text; $label.Left = 20; $label.Top = $y; $label.Width = 500; $label.Height = 22
    $label.Font = New-Object Drawing.Font('Segoe UI', 10, [Drawing.FontStyle]::Bold)
    $form.Controls.Add($label)
}

function New-Button([string] $text, [int] $x, [int] $y, [scriptblock] $click) {
    $button = New-Object Windows.Forms.Button
    $button.Text = $text; $button.Left = $x; $button.Top = $y; $button.Width = 160; $button.Height = 40
    $button.Add_Click($click)
    $form.Controls.Add($button)
    return $button
}

New-Heading 'Your programs' 14
$buttons = @{}
$buttons.HyCreator = New-Button 'HyCreator' 20 40 { Invoke-Safely { Open-Program 'HyCreator' } }
$buttons.Blockbench = New-Button 'Blockbench' 190 40 { Invoke-Safely { Open-Program 'Blockbench' } }
$buttons.PhotoCraft = New-Button 'PhotoCraft' 360 40 { Invoke-Safely { Open-Program 'PhotoCraft' } }
New-Heading 'Your work' 96
$buttons.Updates = New-Button 'Get updates' 20 122 { Invoke-Safely { Open-Console (Join-Path $scripts 'programs.ps1') @('-Root', $root, '-GetUpdates') } }
$where = New-Object Windows.Forms.Label
$where.Text = 'Check and Release are in HyCreator: click the hammer at the top.'
$where.Left = 190; $where.Top = 122; $where.Width = 330; $where.Height = 40; $where.TextAlign = 'MiddleLeft'
$form.Controls.Add($where)

$status = New-Object Windows.Forms.Label
$status.Left = 20; $status.Top = 180; $status.Width = 505; $status.Height = 200
$form.Controls.Add($status)

$buttons.Done = New-Button 'Done' 360 392 { Invoke-Safely { Invoke-Done } }
$buttons.Done.Font = New-Object Drawing.Font('Segoe UI', 10, [Drawing.FontStyle]::Bold)

# ---- what the window says ------------------------------------------------------------------------

$script:work = Get-KeyWork $root
$script:playing = Test-KeyPlaying
$script:online = Test-KeyOnline $root
$script:lastWork = Get-Date
$script:lastOnline = Get-Date
$script:said = ''
$script:busy = $false

function Get-GameLines {
    $lines = @()
    if ($script:copy) {
        $s = $script:copy.State
        if ($s.Error -eq 'space') {
            $lines += "The key is too full for Hytale's files. They need $([math]::Ceiling($s.Total / 1GB) + 1) GB free."
        } elseif ($s.Error -eq 'fat32') {
            Send-KeyProblem "This USB key cannot hold Hytale's largest file, which is over 4 GB."
            $lines += "This USB key cannot hold Hytale's largest file, which is over 4 GB. Larsonix has been told, and you can keep working."
            $lines += 'For Larsonix: the key is FAT32. Formatted as exFAT it would hold it.'
        } elseif ($s.Error) {
            Send-KeyProblem "Hytale's files could not be copied to the key."
            $lines += "Hytale's files could not be copied to the key. Larsonix has been told, and you can keep working."
            $lines += "For Larsonix: $($s.Error)"
        } elseif (-not $s.Finished) {
            $percent = if ($s.Total -gt 0) { [math]::Floor(100 * $s.Done / $s.Total) } else { 0 }
            $lines += "Copying Hytale's files from this computer: $percent%. You can keep working meanwhile."
        } elseif (Get-KeyGame $root) {
            if (-not $script:hasGame) {
                $script:hasGame = $true
                Set-KeyChecks $root $true
            }
            if (Test-Path -LiteralPath (Join-Path $root "$KeyGamePath.partial")) {
                $lines += "Hytale's newer files are copied. They take their place the next time you start the key."
            } else {
                $lines += "Hytale's files are on the key. Restart HyCreator to see the game previews."
            }
        }
    }
    if (-not $script:hasGame -and -not ($script:copy -and -not $script:copy.State.Finished -and -not $script:copy.State.Error)) {
        $lines += "Hytale's files are not on this key yet. Plug it into your own computer once, with Hytale installed, and it copies them."
        $lines += 'Until then HyCreator shows no game previews, and the checks and releases wait.'
    }
    return $lines
}

function Get-PlayLines {
    if (-not (Test-Path -LiteralPath (Get-PcUserData))) { return @() }
    if ($script:playing) { return @('Hytale plays The Armory from this key. Done puts this computer back as it was.') }
    if ($script:playWaiting) { return @('Hytale is open. When you close it, the key links Hytale to your work.') }
    if ($script:playError) {
        return @('Hytale could not be linked to this key. Close Hytale, then click Set up in The Armory panel.', "For Larsonix: $($script:playError)")
    }
    return @('To play your work from this key, click Set up in The Armory panel in HyCreator.')
}

function Update-Window {
    if ($script:busy) { return }
    $now = Get-Date
    if (($now - $script:lastWork).TotalSeconds -ge 10) {
        $script:work = Get-KeyWork $root; $script:lastWork = $now
        $script:playing = Test-KeyPlaying
        if ($script:playWaiting -and -not (Test-HytaleRunning)) { Start-KeyPlay; $script:playing = Test-KeyPlaying }
    }
    if (($now - $script:lastOnline).TotalSeconds -ge 60) { $script:online = Test-KeyOnline $root; $script:lastOnline = $now }
    $lines = @()
    if ($elsewhere) {
        $lines += "On this computer the key uses $($letter): instead of L:. In HyCreator, open the mod folder $($root)The-Armory-Mod once."
        $lines += "Blockbench opens here without The Armory's tools."
    }
    $lines += Get-GameLines
    $lines += Get-PlayLines
    $unsent = $script:work.Unsent
    if ($unsent -eq 1) { $lines += 'One commit is not on GitHub yet. Done sends it.' }
    elseif ($unsent -gt 1) { $lines += "$unsent commits are not on GitHub yet. Done sends them." }
    elseif ($script:work.Changed -eq 0) { $lines += 'All your work is on GitHub.' }
    if (-not $script:online) { $lines += 'No internet right now. Keep working: Done sends your work when you are online again.' }
    $text = $lines -join "`n`n"
    if ($text -ne $script:said) {
        $status.Text = $text
        $script:said = $text
        try { Write-KeyText $stateFile $text } catch { }
    }
}

# ---- Done ------------------------------------------------------------------------------------

# While Done works, the clock stops: it would read the key's letter after Done has removed it.
function Set-Busy([bool] $on, [string] $text) {
    $script:busy = $on
    if ($on) { $timer.Stop() } else { $timer.Start() }
    foreach ($b in $buttons.Values) { $b.Enabled = -not $on }
    if ($text) {
        $status.Text = $text
        $script:said = ''
        try { Write-KeyText $stateFile $text } catch { }
    }
    [Windows.Forms.Application]::DoEvents()
}

function Wait-Until([scriptblock] $done, [int] $seconds) {
    $until = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $until) {
        if (& $done) { return $true }
        Start-Sleep -Milliseconds 250
        [Windows.Forms.Application]::DoEvents()
    }
    return [bool](& $done)
}

function Get-OpenWindows { @(Get-KeyProcesses $root | Where-Object { $_.MainWindowHandle -ne 0 }) }

function Get-ProgramName($process) {
    foreach ($entry in $programList.programs) {
        if ($entry.exe -and $process.Path -ieq (Join-Path $root "Programs\$($entry.folder)\$($entry.exe)")) { return $entry.name }
    }
    return $process.ProcessName
}

# Runs Git or her Update with no window and returns its exit code and its last lines.
function Invoke-Hidden([string] $exe, [string] $arguments) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $exe; $info.Arguments = $arguments; $info.WorkingDirectory = $repo
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($info)
    $out = $process.StandardOutput.ReadToEndAsync(); $err = $process.StandardError.ReadToEndAsync()
    [void](Wait-Until { $process.HasExited } 900)
    $code = if ($process.HasExited) { $process.ExitCode } else { -1 }
    # A program it started may outlive it and hold its output open (Gradle's helper does), so the output
    # is read for a short while only, never waited on.
    $said = @()
    foreach ($task in $out, $err) { if ($task.Wait(10000)) { $said += $task.Result } }
    $last = (($said -join "`n").Trim() -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -Last 4) -join "`n"
    return @{ Code = $code; Text = $last }
}

# Her committed work to GitHub: through her own Update, which also joins any new work from GitHub, when
# nothing is left uncommitted, since Update refuses otherwise. Else a plain send of the commits.
function Send-Work($work) {
    if ($work.Changed -eq 0) {
        $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        return Invoke-Hidden $powershell "-NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $repo 'Studio\Tools\update.ps1')`""
    }
    return Invoke-Hidden (Join-Path $root 'Programs\Git\cmd\git.exe') "push origin HEAD:$($work.Branch)"
}

function Invoke-Done {
    if ($script:busy) { return }
    $work = Get-KeyWork $root
    if ($work.Changed -gt 0) {
        $answer = Show-Message "Some of your changes are not committed, so Done cannot send them.`n`nYes goes on, and they stay on this key. No lets you commit them first in HyCreator." 'YesNo'
        if ($answer -ne 'Yes') { return }
    }
    # The game holds the key's files open while it runs, so it closes before the key leaves it.
    if ((Test-KeyPlaying) -and (Test-HytaleRunning)) {
        [void](Show-Message "Hytale is open, and it plays The Armory from this key.`n`nClose Hytale, then click Done again.")
        return
    }
    if ($script:copy -and -not $script:copy.State.Finished) {
        $answer = Show-Message "Hytale's files are still being copied.`n`nYes stops the copy, and it starts again the next time. No keeps the window open." 'YesNo'
        if ($answer -ne 'Yes') { return }
        $script:copy.Shell.Stop()
    }
    Set-Busy $true 'Closing your programs.'
    foreach ($p in Get-OpenWindows) { try { [void]$p.CloseMainWindow() } catch { } }
    if (-not (Wait-Until { (Get-OpenWindows).Count -eq 0 } 60)) {
        $names = (Get-OpenWindows | ForEach-Object { Get-ProgramName $_ } | Select-Object -Unique) -join ', '
        Set-Busy $false "$names is still open. If it asks a question, answer it. Then click Done again."
        return
    }
    # The checks after a commit and Git finish by themselves. A release leaves Java waiting in the
    # background for its next build, which has nothing to save, so it is ended.
    Set-Busy $true 'Waiting for the checks to finish.'
    [void](Wait-Until { @(Get-KeyProcesses $root -Everywhere | Where-Object { $_.ProcessName -ne 'java' }).Count -eq 0 -and (Get-KeyBrowsers $root).Count -eq 0 } 180)
    Get-KeyProcesses $root -Everywhere | ForEach-Object { try { $_.Kill() } catch { } }
    $work = Get-KeyWork $root
    $script:online = Test-KeyOnline $root
    if ($work.Unsent -gt 0 -and $script:online) {
        Set-Busy $true 'Sending your work to GitHub.'
        $sent = Send-Work $work
        if ($sent.Code -ne 0) {
            $answer = Show-Message "Your work could not be sent to GitHub. It stays safe on this key.`n`n$($sent.Text)`n`nYes ends anyway. No keeps the window open." 'YesNo'
            if ($answer -ne 'Yes') { Set-Busy $false ''; return }
        }
    }
    if (-not (Disconnect-KeyGame)) {
        $answer = Show-Message "Hytale could not be put back as it was before the key. Close Hytale if it is open.`n`nYes ends anyway, and the next start of the key puts it back. No keeps the window open." 'YesNo'
        if ($answer -ne 'Yes') { Set-Busy $false ''; return }
    }
    Clear-KeyGamePath $root
    if ($elsewhere) { Restore-KeyPages $root }
    Dismount-KeyLetter $letter $Key
    $drive = New-Object IO.DriveInfo $Key.Substring(0, 1)
    $last = 'You can unplug the key now.'
    if ($drive.DriveType -eq 'Removable' -and -not $testing) {
        try {
            (New-Object -ComObject Shell.Application).NameSpace(17).ParseName($drive.Name).InvokeVerb('Eject')
            if (-not (Wait-Until { -not (Test-Path -LiteralPath $drive.Name) } 15)) {
                $last = 'Windows is still using the key. Close any window that shows it, then unplug it.'
            }
        } catch { }
    }
    Set-Busy $true $last
    $script:closing = $true
    [void](Show-Message $last)
    $form.Close()
}

$script:closing = $false
$form.Add_FormClosing({
    param($sender, $e)
    # Windows closing or signing out closes the programs itself, and must not wait on this window. The game
    # is put back first, so this computer never starts with a link to a key that may be gone.
    if ($e.CloseReason -eq 'WindowsShutDown') { try { [void](Disconnect-KeyGame) } catch { }; return }
    if (-not $script:closing) { $e.Cancel = $true; Invoke-Safely { Invoke-Done } }
})

$timer = New-Object Windows.Forms.Timer
$timer.Interval = 2000
$script:unplugged = $false
$timer.Add_Tick({
    Invoke-Safely {
        # The key pulled out without Done. Nothing on it can run now, but the computer's record of the game
        # can: the game is put back, and the window closes once it is, or waits while Hytale holds it.
        if ($script:unplugged -or -not (Test-Path -LiteralPath $Key)) {
            if (-not $script:unplugged) {
                $script:unplugged = $true
                $buttons.Values | ForEach-Object { $_.Enabled = $false }
            }
            if (Disconnect-KeyGame) {
                $script:closing = $true
                $timer.Stop()
                $form.Close()
            } else {
                $status.Text = 'The key was unplugged. Close Hytale, and this computer goes back as it was.'
            }
            return
        }
        Update-Window
        # Tests drive the window through a file, since no hand can click on a hidden desktop.
        if ($testing -and -not $script:busy) {
            $order = [IO.Path]::Combine($root, 'Key\test-do.txt')
            if ([IO.File]::Exists($order)) {
                $what = ([IO.File]::ReadAllText($order)).Trim()
                [IO.File]::Delete($order)
                switch ($what) {
                    'Done' { Invoke-Done }
                    default { Open-Program $what }
                }
            }
        }
    }
})
$form.Add_Shown({
    Invoke-Safely {
        Update-Window
        if (-not (Get-KeyProcesses $root | Where-Object { $_.Path -ieq (Get-ProgramExe 'HyCreator') })) { Open-Program 'HyCreator' }
        $timer.Start()
    }
})
[void]$form.ShowDialog()
$timer.Stop()
$mutex.ReleaseMutex()
