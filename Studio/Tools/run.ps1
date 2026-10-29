# Runs one task for The Armory's panel in HyCreator and writes what it prints, line by line as it prints it, to
# Studio\Tools\Runs\<task>.json, which the panel reads. The panel starts it with no window, through Windows' own
# console host:
#
#   conhost.exe --headless powershell.exe -NoProfile -ExecutionPolicy Bypass -File run.ps1 -Task <task> -Id <id> ...
#
#   -Task update          brings in the mod's latest work and sends hers (update.ps1)
#   -Task release-info    what the release form shows: the last version, the next one, what changed since
#   -Task release         -Version 1.2.3 -Choice draft|message|publish -NotesFile <file> [-MessageFile <file>]
#   -Task setup-report    what setup would move, so the panel can ask before anything moves
#   -Task setup           setup, once she agreed (setup.ps1 -Yes)
#
# On her USB key both setup tasks run Studio\Key\play.ps1 instead, which links this computer's Hytale to the key.
#
# Any other argument is passed on to the task's script. Only tests use that.
#
# Each task runs as a process of its own, so a task that stops in any way still leaves its result here. One task runs
# at a time, because two would join or build the same copy at once. A result still marked running whose process is
# gone was cut off (the computer slept or shut down), and never stops the next task.
param(
    [string] $Task = '',
    # This start's own name, written into the result, so the panel tells this run's result from an older one.
    [string] $Id = '',
    [string] $Version = '',
    [string] $Choice = '',
    [string] $NotesFile = '',
    [string] $MessageFile = ''
)
$ErrorActionPreference = 'Stop'
$tools = $PSScriptRoot
$runs = Join-Path $tools 'Runs'
$tasks = @{
    'update' = 'update.ps1'; 'release-info' = 'release.ps1'; 'release' = 'release.ps1'
    'setup-report' = 'setup.ps1'; 'setup' = 'setup.ps1'
}
if (-not $tasks.ContainsKey($Task)) { Write-Host "run.ps1: no task named '$Task'. Tasks: $($tasks.Keys -join ', ')"; exit 2 }
New-Item -ItemType Directory -Force -Path $runs | Out-Null
$resultFile = Join-Path $runs "$Task.json"
$dataFile = Join-Path $runs "$Task.data.json"

$result = [ordered]@{
    task = $Task; id = $Id; state = 'running'; started = (Get-Date).ToString('o'); finished = $null; code = $null
    pid = $PID; lines = New-Object System.Collections.Generic.List[string]; data = $null
}

# Written to a file beside it, then moved over it, so the panel never reads half a result.
function Save-Result {
    $text = $result | ConvertTo-Json -Depth 6
    $temp = "$resultFile.tmp"
    [IO.File]::WriteAllText($temp, $text, (New-Object Text.UTF8Encoding $false))
    Move-Item -LiteralPath $temp -Destination $resultFile -Force
}

function Stop-Run([string] $text, [int] $code = 1) {
    $result.lines.Add($text)
    $result.state = 'failed'; $result.code = $code; $result.finished = (Get-Date).ToString('o')
    Save-Result
    exit $code
}

# Another task still running, by a result marked running whose process is still this runner.
foreach ($other in Get-ChildItem -LiteralPath $runs -Filter '*.json' -File) {
    if ($other.Name -like '*.data.json' -or $other.FullName -eq $resultFile) { continue }
    try { $seen = [IO.File]::ReadAllText($other.FullName) | ConvertFrom-Json } catch { continue }
    if ($seen.state -ne 'running' -or -not $seen.pid) { continue }
    $alive = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$seen.pid)" -ErrorAction SilentlyContinue
    if ($alive -and $alive.CommandLine -like '*run.ps1*') {
        Stop-Run "Another task is still running: $($seen.task). Wait until it ends, then try again."
    }
}
Save-Result

# On the USB key, Set up links this computer's Hytale to the mod on the key. The key sets up everything else itself.
if ($env:ARMORY_KEY -and $Task -like 'setup*') { $tasks[$Task] = '..\Key\play.ps1' }

$arguments = New-Object System.Collections.Generic.List[string]
switch ($Task) {
    'release-info' { $arguments.AddRange([string[]]@('-Info', $dataFile)) }
    'release' {
        foreach ($pair in @(@('-Version', $Version), @('-Choice', $Choice), @('-NotesFile', $NotesFile), @('-MessageFile', $MessageFile))) {
            if ($pair[1]) { $arguments.AddRange([string[]]$pair) }
        }
    }
    'setup-report' { $arguments.AddRange([string[]]@('-Report', $dataFile)) }
    'setup' { $arguments.Add('-Yes') }
}
foreach ($a in $args) { $arguments.Add([string]$a) }
if (Test-Path -LiteralPath $dataFile) { Remove-Item -LiteralPath $dataFile -Force }

# The task's words reach the panel as written, accents included: the console the task shares with this runner
# speaks UTF-8 from here on, and its output is read as UTF-8.
try { [Console]::OutputEncoding = New-Object Text.UTF8Encoding $false } catch { }
$quote = { param($s) if ($s -match '[\s"]' -or $s -eq '') { '"' + ($s -replace '"', '\"') + '"' } else { $s } }
$info = New-Object Diagnostics.ProcessStartInfo
$info.FileName = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$info.Arguments = (@('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $tools $tasks[$Task])) + $arguments | ForEach-Object { & $quote $_ }) -join ' '
$info.UseShellExecute = $false
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$info.RedirectStandardInput = $true
$info.StandardOutputEncoding = New-Object Text.UTF8Encoding $false
$info.StandardErrorEncoding = New-Object Text.UTF8Encoding $false
$info.WorkingDirectory = (Resolve-Path (Join-Path $tools '..\..')).Path
try {
    $process = [Diagnostics.Process]::Start($info)
} catch {
    Stop-Run "The task could not start. Send Larsonix a screenshot."
}
# Nothing a task asks can be answered here: an input that is closed makes any question end at once instead of waiting.
$process.StandardInput.Close()
$errors = $process.StandardError.ReadToEndAsync()
while ($null -ne ($line = $process.StandardOutput.ReadLine())) {
    $result.lines.Add($line.TrimEnd())
    Save-Result
}
$process.WaitForExit()
foreach ($line in ($errors.Result -split "`r?`n")) { if ($line.Trim()) { $result.lines.Add("For Larsonix: $($line.TrimEnd())") } }
if (Test-Path -LiteralPath $dataFile) {
    try { $result.data = [IO.File]::ReadAllText($dataFile) | ConvertFrom-Json } catch { $result.data = $null }
}
$result.code = $process.ExitCode
$result.state = if ($process.ExitCode -eq 0) { 'done' } else { 'failed' }
$result.finished = (Get-Date).ToString('o')
Save-Result
exit $process.ExitCode
