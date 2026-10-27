# Brings the mod's latest work into this branch, keeps the work already on it, and sends the result
# to GitHub. The Update button of The Armory's panel in HyCreator runs it (through run.ps1), and so
# do the USB key's Get updates and Done.
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

function Stop-Update([string] $text, [string] $detail = '') {
    Write-Host ''
    Write-Host $text
    if ($detail) { Write-Host "For Larsonix: $detail" }
    exit 1
}

Write-Host 'Updating your copy of The Armory.'
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Stop-Update 'Git is not installed. Install it from https://git-scm.com/download/win with its default choices, then try again.' }

$branch = (Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD')).Text
if ($branch -eq 'HEAD' -or -not $branch) { Stop-Update 'Your copy is on no branch. In HyCreator, click HEAD at the top, then Branches..., then lady. Then click Update again.' }
if ($branch -eq $Main) { Stop-Update "Your copy is on $Main. In HyCreator, click $Main at the top, then Branches..., then lady. Then click Update again." }
if (Test-Path -LiteralPath (Join-Path $repo '.git\MERGE_HEAD')) { Stop-Update 'An earlier update did not finish. Send Larsonix a screenshot.' }
$dirty = (Invoke-Git @('status', '--porcelain', '--untracked-files=no')).Text
if ($dirty) { Stop-Update "Some of your changes are not committed. In HyCreator, click $branch at the top, then Commit changes... and commit them. Then click Update again." }

Write-Host 'Getting the latest from GitHub...'
$fetch = Invoke-Git @('fetch', 'origin')
if ($fetch.Code -ne 0) { Stop-Update 'GitHub could not be reached. Check your internet, then try again.' $fetch.Text }

# HyCreator keeps its own copy of each language file and builds every name it saves from that copy, so a
# name that arrives here while it is open would be erased by her next save. The Armory's add-on in
# HyCreator sees the file change and has HyCreator read it again, so an update goes on with HyCreator open.

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
        Stop-Update "Your work and ours changed the same lines in these files:`n$list`nNothing was changed, and you can keep working. Send Larsonix a screenshot."
    }
    $brought += $new
}

if ($brought -eq 0) { Write-Host 'Nothing new.' } else { Write-Host "Brought in $brought new change(s)." }

if (-not $NoPush) {
    $ahead = (Invoke-Git @('rev-list', '--count', "origin/$branch..HEAD")).Text
    $hasRemote = (Invoke-Git @('rev-parse', '--verify', '--quiet', "origin/$branch^{commit}")).Code -eq 0
    if (-not $hasRemote -or [int]$ahead -gt 0) {
        Write-Host 'Sending your work to GitHub...'
        $push = Invoke-Git @('push', '-u', 'origin', "HEAD:$branch")
        if ($push.Code -ne 0) { Stop-Update "Your copy is updated, but sending it to GitHub failed. In HyCreator, click $branch at the top, then Push. If that fails too, send Larsonix a screenshot." $push.Text }
        Write-Host 'Sent.'
    }
}

# Git copies the code and the pages after a pull without a word, so a copy that fails while a world
# holds the code open would go unseen. Doing it here says so. A failed copy is not a failed update,
# so a release that runs this goes on: its line already says what to do.
& (Join-Path $PSScriptRoot 'sync.ps1')
if ($LASTEXITCODE -eq 0) { Write-Host ''; Write-Host 'Done.' }
exit 0
