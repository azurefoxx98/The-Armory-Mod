# Set up in The Armory panel, when the mod is open from the USB key. run.ps1 runs it in place of setup.ps1,
# which sets up a copy on the computer itself. It links this computer's Hytale to the mod on the key, so the
# game plays her work from the key and a file saved in HyCreator changes in the running world, as on her own
# computer. Done, or the key unplugged, puts the game back as it was (key.ps1, Disconnect-KeyGame). After one
# yes, the key links this computer's game again by itself at each start.
#
#   play.ps1 -Report <file>   writes what would move, so the panel can ask before anything moves
#   play.ps1 -Yes             links the game, once she agreed
param(
    [switch] $Yes,
    [string] $Report = ''
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'key.ps1')

$root = $env:ARMORY_KEY
$keyFolder = $env:ARMORY_KEY_FOLDER
# A window started before this script arrived through Get updates gives no folder, and would not put the game back at
# Done either, so the key starts again first.
if (-not $root -or -not $keyFolder) {
    Write-Host 'Click Done in the key''s window, then start the key again. Then click Set up again.'
    Write-Host "For Larsonix: ARMORY_KEY '$root', ARMORY_KEY_FOLDER '$keyFolder'"
    exit 1
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
    Write-Host 'Hytale could not be linked to your key. Close Hytale, then click Set up again. If it happens again, send Larsonix a screenshot.'
    Write-Host "For Larsonix: $($_.InvocationInfo.ScriptLineNumber) $($_.Exception.Message)"
    exit 1
}
Add-KeyComputer $root
foreach ($m in @($done.Record.moved | Where-Object { $_ })) { Write-Host "  Moved to $($m.to)" }
Write-Host 'Hytale now plays The Armory from your key. Done puts this computer back as it was.'
Write-Host 'On this computer, the key links Hytale again by itself each time you start it.'
if (-not (Get-KeyGame $root)) {
    Write-Host "The checks wait for Hytale's files. The key copies them from this computer while it is plugged in."
}
exit 0
