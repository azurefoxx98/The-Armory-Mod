# Set up in The Armory panel, when the mod is open from the USB key. run.ps1 runs it in place of setup.ps1,
# which sets up a copy on the computer itself. It links this computer's Hytale to the mod on the key, so the
# game plays her work from the key and a file saved in HyCreator changes in the running world, as on her own
# computer. Done, or the key unplugged, puts the game back as it was (key.ps1, Disconnect-KeyGame). After one
# yes, the key links this computer's game again by itself at each start.
#
#   play.ps1 -Report <file>   writes what would move, so the panel can ask before anything moves
#   play.ps1 -Yes             links the game, once she agreed
#   play.ps1 -Watch <folder> -UserData <folder> [-Window <id>]
#                             puts the game back once the key is gone or that window has closed, for a link made
#                             without a window that does it
param(
    [switch] $Yes,
    [string] $Report = '',
    [string] $Watch = '',
    [string] $UserData = '',
    [int] $Window = 0
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'key.ps1')

# The watch runs on its own, started below, and outlives HyCreator and the key. Its script is read in whole when it
# starts, so the key can leave. One watch per computer is enough, since the put-back needs only the computer's record.
if ($Watch) {
    Set-Location -LiteralPath $env:SystemRoot
    $mutex = New-Object Threading.Mutex($false, 'Local\TheArmoryKeyWatch')
    if (-not $mutex.WaitOne(0)) { exit 0 }
    if (-not $UserData) { $UserData = Get-PcUserData }
    while ((Test-Path -LiteralPath $Watch) -and (-not $Window -or (Get-Process -Id $Window -ErrorAction SilentlyContinue))) { Start-Sleep -Seconds 2 }
    # The game holds the code jar while it runs, so the put-back waits for it. A newer window started meanwhile owns
    # the game from then on, and puts it back itself.
    while (-not (Disconnect-KeyGame $UserData)) {
        $newer = Find-KeyWindow $Watch
        if ($newer -and [int]$newer.ProcessId -ne $Window) { exit 0 }
        Start-Sleep -Seconds 5
    }
    exit 0
}

$root = $env:ARMORY_KEY
if (-not $root) { $root = Get-KeyFolder (Join-Path $PSScriptRoot '..\..\..') }
# HyCreator started by a window older than this script never has the key's folder, so it is found from the key's
# letter. When the window running now is such an old one too, nothing would put the game back at Done or when the key
# leaves, so a watch started after the link does it. A current window puts the game back itself.
$keyFolder = $env:ARMORY_KEY_FOLDER
$watchNeeded = $false
$windowId = 0
if (-not $keyFolder) {
    $keyFolder = Find-KeyFolder $root
    # Not $window: PowerShell names ignore case, and $Window is the watch's number.
    $running = Find-KeyWindow $keyFolder
    if ($running) { $windowId = [int]$running.ProcessId }
    $watchNeeded = -not (Test-KeyWindowCurrent $running)
}

$plan = Get-KeyPlayPlan $keyFolder
if (-not $plan) {
    Write-Host 'Hytale was not found on this computer. Start Hytale once, then click Set up again.'
    Write-Host "For Larsonix: $(Get-PcUserData)"
    exit 1
}

if ($Report) {
    $data = [ordered]@{ others = @($plan.Others); setAside = (Join-Path $plan.UserData $KeyPlayAside); toBranch = $null; linkTaken = $false; back = $true }
    [IO.File]::WriteAllText($Report, ($data | ConvertTo-Json -Depth 4), $KeyUtf8)
    if ($plan.Others.Count -gt 0) { Write-Host "Other copies of The Armory to move aside: $($plan.Others.Count)." } else { Write-Host 'No other copy of The Armory to move.' }
    exit 0
}

if (Test-HytaleRunning) {
    Write-Host 'Hytale is open. Close Hytale, then click Set up again.'
    exit 1
}
try {
    $done = Connect-KeyGame $root $keyFolder
} catch {
    Write-Host 'Hytale could not be linked to your key. Close Hytale, then click Set up again.'
    Write-Host "For Larsonix: $($_.InvocationInfo.ScriptLineNumber) $($_.Exception.Message)"
    & (Join-Path $PSScriptRoot '..\Tools\update.ps1') -Report 'The key could not link Hytale' -Detail "$($_.InvocationInfo.ScriptLineNumber) $($_.Exception.Message)"
    exit 1
}
Add-KeyComputer $root

# Through Windows' own process service, so the watch shares nothing with this task: HyCreator reads this task's output
# until every process holding it ends, and closes the processes it started when it closes.
if ($watchNeeded) {
    try {
        $shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $conhost = Join-Path $env:SystemRoot 'System32\conhost.exe'
        # A console of its own that never shows: Windows 11 hands a new visible console to its terminal, which ignores
        # a hidden style, and a process with no console at all ends at once.
        # This script by the key's own drive, since the key's letter may not exist for a process started this way.
        $self = $PSCommandPath
        if ($self.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { $self = Join-Path $keyFolder $self.Substring($root.Length) }
        $line = "`"$conhost`" --headless `"$shell`" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$self`" -Watch `"$keyFolder`" -UserData `"$($plan.UserData)`" -Window $windowId"
        $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }
        $made = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $line; CurrentDirectory = $env:SystemRoot; ProcessStartupInformation = $startup }
        if ($made.ReturnValue -ne 0) { throw "Win32_Process Create returned $($made.ReturnValue)" }
    } catch {
        & (Join-Path $PSScriptRoot '..\Tools\update.ps1') -Report 'The key could not watch for its own end' -Detail "$($_.InvocationInfo.ScriptLineNumber) $($_.Exception.Message)"
    }
}

foreach ($m in @($done.Record.moved | Where-Object { $_ })) { Write-Host "  Moved to $($m.to)" }
Write-Host 'Hytale now plays The Armory from your key. Done puts this computer back as it was.'
Write-Host 'On this computer, the key links Hytale again by itself each time you start it.'
if (-not (Get-KeyGame $root)) {
    Write-Host "The checks wait for Hytale's files. The key copies them from this computer while it is plugged in."
}
exit 0
