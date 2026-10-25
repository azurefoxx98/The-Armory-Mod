# Brings the mod's latest work into this branch, keeps the work already on it, and sends the result
# to GitHub. Run it through Studio\Update.cmd.
#
# Why it exists: HyCreator's Pull only moves a branch forward. When a branch holds commits of its
# own and new work arrives beside them, Pull refuses ("Your branch and its remote have diverged").
# This joins the two instead. It brings in main, where every release of the mod lands, and this
# branch's own copy on GitHub, which another computer may have pushed to.
#
# It changes nothing when it cannot finish cleanly: with uncommitted work, on main itself, or when
# the same lines were changed on both sides. In that last case it undoes the attempt and names the
# files, and the branch is exactly as it was.
param(
    # The branch every release lands on. Only tests point this elsewhere.
    [string] $Main = 'main',
    # Leave out the final push. Only tests use it.
    [switch] $NoPush
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

# Runs git and returns its exit code and everything it printed. Git writes progress to its error
# stream, which Windows PowerShell would otherwise treat as a failure.
function Invoke-Git([string[]] $arguments) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = 'git'
    $info.Arguments = (@('-C', $repo) + $arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables['GIT_EDITOR'] = 'true'
    $process = [Diagnostics.Process]::Start($info)
    $out = $process.StandardOutput.ReadToEndAsync()
    $err = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    return @{ Code = $process.ExitCode; Text = ($out.Result + $err.Result).Trim() }
}

function Stop-Update([string] $text) {
    Write-Host ''
    Write-Host $text
    exit 1
}

Write-Host 'The Armory: bringing in the latest work.'
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Stop-Update 'Git is not installed. Install Git for Windows, then try again.' }

$branch = (Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD')).Text
if ($branch -eq 'HEAD' -or -not $branch) { Stop-Update 'This copy is on no branch. Tell Larsonix. Nothing was changed.' }
if ($branch -eq $Main) { Stop-Update "This copy is on $Main. Switch to your own branch in HyCreator's git menu, Branches..., then try again. Nothing was changed." }
if (Test-Path -LiteralPath (Join-Path $repo '.git\MERGE_HEAD')) { Stop-Update 'An earlier join was left unfinished. Tell Larsonix. Nothing was changed.' }
$dirty = (Invoke-Git @('status', '--porcelain', '--untracked-files=no')).Text
if ($dirty) { Stop-Update 'Some work is not committed yet. Commit it in HyCreator first (Commit changes...), then run Update again. Nothing was changed.' }

Write-Host 'Getting the latest from GitHub...'
$fetch = Invoke-Git @('fetch', 'origin')
if ($fetch.Code -ne 0) { Stop-Update "GitHub could not be reached: $($fetch.Text). Nothing was changed." }

$start = (Invoke-Git @('rev-parse', 'HEAD')).Text
$brought = 0
foreach ($source in @("origin/$branch", "origin/$Main")) {
    if ((Invoke-Git @('rev-parse', '--verify', '--quiet', "$source^{commit}")).Code -ne 0) { continue }
    $new = [int](Invoke-Git @('rev-list', '--count', "HEAD..$source")).Text
    if ($new -eq 0) { continue }
    $merge = Invoke-Git @('merge', '--no-edit', '-m', "Bring in $source", $source)
    if ($merge.Code -ne 0) {
        $both = (Invoke-Git @('diff', '--name-only', '--diff-filter=U')).Text -split "`n" | Where-Object { $_ }
        [void](Invoke-Git @('merge', '--abort'))
        [void](Invoke-Git @('reset', '--hard', '-q', $start))
        $list = if ($both) { ($both | ForEach-Object { "  $_" }) -join "`n" } else { "  $($merge.Text)" }
        Stop-Update "Your work and ours changed the same lines here:`n$list`nNothing was changed. Tell Larsonix which files, and keep working: your work is safe."
    }
    $brought += $new
}

if ($brought -eq 0) { Write-Host 'Nothing new to bring in.' } else { Write-Host "Brought in $brought new change(s). Your work is kept beside them." }

if (-not $NoPush) {
    $ahead = (Invoke-Git @('rev-list', '--count', "origin/$branch..HEAD")).Text
    $hasRemote = (Invoke-Git @('rev-parse', '--verify', '--quiet', "origin/$branch^{commit}")).Code -eq 0
    if (-not $hasRemote -or [int]$ahead -gt 0) {
        Write-Host 'Sending your branch to GitHub...'
        $push = Invoke-Git @('push', '-u', 'origin', "HEAD:$branch")
        if ($push.Code -ne 0) { Stop-Update "Everything is joined here, but sending it failed: $($push.Text)`nPush in HyCreator, or tell Larsonix." }
        Write-Host 'Sent.'
    }
}
Write-Host ''
Write-Host 'Done. If a world is open, restart it so the game runs the newest code. The checks run by themselves now.'
exit 0
