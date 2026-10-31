# Commits her work with her own line, brings the mod's latest work into her branch, and sends the result to
# GitHub. Send my work in The Armory's panel in HyCreator runs it (through run.ps1), and so do HyCreator's start
# (-OnOpen), the USB key's Done, and Release before it builds.
#
# It solves what it meets and never leaves her stuck:
#   - her work is committed with the line she wrote (-WorkFile), so it keeps her own words,
#   - a copy on main or on no branch moves to her branch, carrying its work (commits only this case makes for her),
#   - an earlier update left unfinished is cleared,
#   - names both sides added are all kept (*.lang merges line by line),
#   - a file both sides changed is settled by who owns it, and we are told which,
#   - a file changed on two of her own computers keeps the later commit, and the other stays in history,
#   - a merge that still cannot finish sends her work to a branch of its own on GitHub, and we are told,
#   - no internet leaves everything saved here, and the next Update sends it.
#
# Her computer runs the copy of this file it already has, so a copy that cannot finish could never
# receive its own cure. After fetching, this script reads the newest copy from GitHub and runs that one.
#
# -Report is how the other tools reach us: it files a problem as a GitHub issue, and a problem filed
# with no internet waits in .git/armory-reports until a later run can send it.
param(
    # The branch every release lands on. Only tests point this elsewhere.
    [string] $Main = 'main',
    # Her branch, which a copy on main or on no branch moves to.
    [string] $Own = 'lady',
    # The file holding the line she wrote about her work.
    [string] $WorkFile = '',
    # The file holding a message she wrote for us, sent with her work.
    [string] $NoteFile = '',
    # HyCreator just opened: bring in what is new when her copy is clean, and never ask for anything.
    [switch] $OnOpen,
    # Leave out the final push. Only tests use it.
    [switch] $NoPush,
    # Run this file as it is, without reading the newest copy. Only tests use it.
    [switch] $NoRefresh,
    # The copy to work on, given when the newest copy of this file runs from outside it.
    [string] $Repo = '',
    # Set by the run of the newest copy, so it never starts another.
    [switch] $Fresh,
    # File this problem for us instead of updating.
    [string] $Report = '',
    [string] $Detail = ''
)
$ErrorActionPreference = 'Stop'
$repo = if ($Repo) { $Repo } else { (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path }

# Files only we change, where our side wins. Everything else is her work, where her side wins.
$oursPattern = '^(Studio/(Tools|Key)/|Studio/GUIDE\.md$|src/main/java/|src/test/|gradle/|gradlew|build\.gradle|settings\.gradle|\.gitattributes$|\.gitignore$|\.github/)'

# Runs git and returns its exit code and everything it printed. Git writes progress to its error
# stream, which Windows PowerShell would otherwise treat as a failure.
function Invoke-Git([string[]] $arguments) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = 'git'
    $info.Arguments = (@('-C', $repo, '-c', 'core.quotepath=off') + $arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardInput = $true
    $info.StandardOutputEncoding = New-Object Text.UTF8Encoding $false
    $info.EnvironmentVariables['GIT_EDITOR'] = 'true'
    $info.EnvironmentVariables['GIT_MERGE_AUTOEDIT'] = 'no'
    $process = [Diagnostics.Process]::Start($info)
    $process.StandardInput.Close()
    $out = $process.StandardOutput.ReadToEndAsync()
    $err = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    return @{ Code = $process.ExitCode; Out = $out.Result.Trim(); Text = ($out.Result + $err.Result).Trim() }
}

function Get-GitPath([string] $name) {
    $path = (Invoke-Git @('rev-parse', '--git-path', $name)).Out
    if ([IO.Path]::IsPathRooted($path)) { return $path }
    return Join-Path $repo $path
}

# ---- reaching us -----------------------------------------------------------------------------

# Her name and her folders stay off GitHub: her repository is public.
function Hide-Private([string] $text) {
    if ($env:USERPROFILE) { $text = $text.Replace($env:USERPROFILE, '~').Replace($env:USERPROFILE.Replace('\', '/'), '~') }
    if ($env:USERNAME) { $text = $text.Replace($env:USERNAME, 'her') }
    if ($text.Length -gt 3500) { $text = $text.Substring(0, 3500) + ' ...' }
    return $text
}

function Get-Hash([string] $text) {
    $sha = [Security.Cryptography.SHA1]::Create()
    return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($text))) -replace '-', '').Substring(0, 16)
}

function Send-Waiting {
    $folder = Get-GitPath 'armory-reports'
    if (-not (Test-Path -LiteralPath $folder)) { return }
    $waiting = @(Get-ChildItem -LiteralPath $folder -Filter '*.json' -File)
    if ($waiting.Count -eq 0) { return }
    try {
        $url = (Invoke-Git @('remote', 'get-url', 'origin')).Out
        if ($url -notmatch 'github\.com[:/]([^/]+)/([^/]+?)(\.git)?$') { return }
        $api = "https://api.github.com/repos/$($Matches[1])/$($Matches[2])/issues"
        $env:GCM_INTERACTIVE = 'never'
        $ErrorActionPreference = 'Continue'
        $answer = "protocol=https`nhost=github.com`n`n" | & git credential fill 2>$null
        $ErrorActionPreference = 'Stop'
        Remove-Item Env:\GCM_INTERACTIVE
        $token = ($answer | Where-Object { $_ -like 'password=*' } | Select-Object -First 1) -replace '^password=', ''
        if (-not $token) { return }
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $headers = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' }
        foreach ($file in $waiting) {
            $body = [IO.File]::ReadAllBytes($file.FullName)
            [void](Invoke-RestMethod -Method Post -Uri $api -Headers $headers -Body $body -ContentType 'application/json' -TimeoutSec 20)
            Add-Content -LiteralPath (Join-Path $folder 'sent.txt') $file.BaseName
            Remove-Item -LiteralPath $file.FullName -Force
        }
    } catch {
        # No internet or no sign-in yet. The reports wait for a later run.
        $ErrorActionPreference = 'Stop'
    }
}

# Files one problem once. The same problem met again files nothing more.
function Send-Problem([string] $title, [string] $detail) {
    try {
        $folder = Get-GitPath 'armory-reports'
        New-Item -ItemType Directory -Force -Path $folder | Out-Null
        $title = Hide-Private $title
        $detail = Hide-Private $detail
        $hash = Get-Hash "$title`n$detail"
        $sent = Join-Path $folder 'sent.txt'
        if ((Test-Path -LiteralPath $sent) -and ((Get-Content -LiteralPath $sent) -contains $hash)) { return }
        $body = @{ title = "The Armory tools: $title"; body = "@Larsonix This came from Lady's computer on its own.`n`n$detail" } | ConvertTo-Json
        [IO.File]::WriteAllText((Join-Path $folder "$hash.json"), $body, (New-Object Text.UTF8Encoding $false))
        Send-Waiting
    } catch {
        # A report never stops the work it reports on.
        $ErrorActionPreference = 'Stop'
    }
}

# A message she wrote for us: an issue that names him, kept until it can be sent, like a report.
function Send-Note([string] $file) {
    if (-not $file -or -not (Test-Path -LiteralPath $file)) { return }
    $text = ([IO.File]::ReadAllText($file)).Trim()
    if (-not $text) { return }
    try {
        $folder = Get-GitPath 'armory-reports'
        New-Item -ItemType Directory -Force -Path $folder | Out-Null
        $body = @{ title = 'A message from Lady'; body = "@Larsonix $(Hide-Private $text)" } | ConvertTo-Json
        [IO.File]::WriteAllText((Join-Path $folder "$(Get-Hash ((Get-Date).ToString('o') + $text)).json"), $body, (New-Object Text.UTF8Encoding $false))
        Send-Waiting
    } catch {
        $ErrorActionPreference = 'Stop'
    }
}

if ($Report) {
    Send-Problem $Report $Detail
    exit 0
}

# ---- leaving her work safe -------------------------------------------------------------------

# An interrupted git leaves its lock behind, and every later git refuses to run.
function Clear-Lock {
    $lock = Get-GitPath 'index.lock'
    if ((Test-Path -LiteralPath $lock) -and -not (Get-Process git -ErrorAction SilentlyContinue)) {
        Remove-Item -LiteralPath $lock -Force -ErrorAction SilentlyContinue
    }
}

function Clear-Unfinished {
    Clear-Lock
    if (Test-Path -LiteralPath (Get-GitPath 'MERGE_HEAD')) {
        if ((Invoke-Git @('merge', '--abort')).Code -ne 0) { [void](Invoke-Git @('reset', '--merge')) }
    }
    if ((Test-Path -LiteralPath (Get-GitPath 'rebase-merge')) -or (Test-Path -LiteralPath (Get-GitPath 'rebase-apply'))) { [void](Invoke-Git @('rebase', '--abort')) }
    if (Test-Path -LiteralPath (Get-GitPath 'CHERRY_PICK_HEAD')) { [void](Invoke-Git @('cherry-pick', '--abort')) }
    if (Test-Path -LiteralPath (Get-GitPath 'REVERT_HEAD')) { [void](Invoke-Git @('revert', '--abort')) }
}

# A commit made for her needs a name, which a fresh computer may not hold yet.
function Get-Identity {
    $identity = @()
    if (-not (Invoke-Git @('config', '--get', 'user.name')).Out) { $identity += @('-c', 'user.name=LadyPaladra') }
    if (-not (Invoke-Git @('config', '--get', 'user.email')).Out) { $identity += @('-c', 'user.email=ladypaladra@users.noreply.github.com') }
    return $identity
}

function Save-Work {
    if (-not (Invoke-Git @('status', '--porcelain')).Out) { return }
    [void](Invoke-Git @('add', '-A'))
    $words = if ($WorkFile -and (Test-Path -LiteralPath $WorkFile)) { @('-F', $WorkFile) } else { @('-m', 'Work saved by Update') }
    $saved = Invoke-Git ((Get-Identity) + @('commit', '-q') + $words)
    if ($saved.Code -ne 0) { Send-Problem 'Update could not commit her changes' $saved.Text }
}

# Keeps the names both sides added. A rule in .git/info is read before any merge, so it also works on
# a copy whose branch does not hold .gitattributes yet.
function Set-MergeRule {
    $file = Get-GitPath 'info/attributes'
    $rule = '*.lang merge=union'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
    if ((Test-Path -LiteralPath $file) -and ((Get-Content -LiteralPath $file) -contains $rule)) { return }
    Add-Content -LiteralPath $file $rule
}

# ---- joining the two sides -------------------------------------------------------------------

$settled = New-Object System.Collections.Generic.List[string]
$oursKept = New-Object System.Collections.Generic.List[string]
$laterKept = New-Object System.Collections.Generic.List[string]

# True when the coming side of this file is her own work from another computer, and the later of the two. The
# author tells the two apart from our work: both sides by one author is her on two computers.
function Test-HerLater([string] $path, [string] $source) {
    $base = (Invoke-Git @('merge-base', 'HEAD', $source)).Out
    if (-not $base) { return $false }
    $here = (Invoke-Git @('log', '-1', '--format=%an|%ct', "$base..HEAD", '--', $path)).Out -split '\|'
    $there = (Invoke-Git @('log', '-1', '--format=%an|%ct', "$base..$source", '--', $path)).Out -split '\|'
    if ($here.Count -lt 2 -or $there.Count -lt 2 -or $here[0] -ne $there[0]) { return $false }
    return [long]$there[1] -gt [long]$here[1]
}

# Keeps one side of one conflicted file. Stage 2 is her side, stage 3 ours. A side with no stage
# deleted the file.
function Resolve-Side([string] $path, [string] $source) {
    $stages = ((Invoke-Git @('ls-files', '-u', '--', $path)).Out -split "`n" | ForEach-Object { if ($_ -match '^\d+ \S+ (\d)\t') { $Matches[1] } })
    $ours = $path -match $oursPattern
    $later = -not $ours -and (Test-HerLater $path $source)
    $side = if ($ours -or $later) { '3' } else { '2' }
    if ($stages -contains $side) {
        $flag = if ($side -eq '3') { '--theirs' } else { '--ours' }
        $take = Invoke-Git @('checkout', $flag, '--', $path)
        if ($take.Code -ne 0) { return $false }
        if ((Invoke-Git @('add', '--', $path)).Code -ne 0) { return $false }
    } elseif ((Invoke-Git @('rm', '-q', '--', $path)).Code -ne 0) { return $false }
    if ($ours) { $oursKept.Add($path) } elseif ($later) { $laterKept.Add($path) } else { $settled.Add($path) }
    return $true
}

# Brings one side in. True when it is joined and committed.
function Join-Source([string] $source) {
    $merge = Invoke-Git ((Get-Identity) + @('merge', '--no-edit', '-m', "Bring in $source", $source))
    # A new file she never committed sits where our side adds one. Committing it keeps it and lets the merge run.
    if ($merge.Code -ne 0 -and $merge.Text -match 'untracked working tree files') {
        Save-Work
        $merge = Invoke-Git ((Get-Identity) + @('merge', '--no-edit', '-m', "Bring in $source", $source))
    }
    if ($merge.Code -eq 0) { return @{ Ok = $true } }
    $both = @((Invoke-Git @('diff', '--name-only', '--diff-filter=U')).Out -split "`n" | Where-Object { $_ })
    $done = $both.Count -gt 0
    foreach ($path in $both) { if (-not (Resolve-Side $path $source)) { $done = $false; break } }
    if ($done) {
        $commit = Invoke-Git ((Get-Identity) + @('commit', '-q', '--no-edit', '-m', "Bring in $source, each file settled by who owns it"))
        if ($commit.Code -eq 0) { return @{ Ok = $true } }
        $merge = $commit
    }
    Clear-Unfinished
    return @{ Ok = $false; Text = $merge.Text }
}

# A name both sides wrote stays twice after a line by line merge. Hytale keeps the first, which is
# hers, so the later line is removed. Only names this update doubled are touched: the translators'
# files go in exactly as written, and a line that continues on the next one is never read as a name.
function Remove-Doubled([string] $start) {
    $changed = @((Invoke-Git @('diff', '--name-only', $start, 'HEAD', '--', '*.lang')).Out -split "`n" | Where-Object { $_ })
    $differ = New-Object System.Collections.Generic.List[string]
    $touched = @()
    foreach ($path in $changed) {
        $file = Join-Path $repo ($path -replace '/', '\')
        if (-not (Test-Path -LiteralPath $file)) { continue }
        $before = @{}
        foreach ($line in ((Invoke-Git @('show', "${start}:$path")).Out -split "`n")) {
            if ($line -match '^\s*([^#=\s][^=]*?)\s*=') { $before[$Matches[1]] = 1 + [int]$before[$Matches[1]] }
        }
        $encoding = New-Object Text.UTF8Encoding $false
        $lines = $encoding.GetString([IO.File]::ReadAllBytes($file)) -split "(?<=`n)"
        $seen = @{}
        $kept = New-Object System.Collections.Generic.List[string]
        $continues = $false
        foreach ($line in $lines) {
            $wasContinued = $continues
            $continues = $line.TrimEnd().EndsWith('\')
            if (-not $wasContinued -and $line -match '^\s*([^#=\s][^=]*?)\s*=(.*)$') {
                $key = $Matches[1]
                $value = $Matches[2].Trim()
                if ($seen.ContainsKey($key) -and [int]$before[$key] -lt 2 -and -not $continues) {
                    if ($seen[$key] -ne $value) { $differ.Add("$key in $path, kept: $($seen[$key]), removed: $value") }
                    continue
                }
                if (-not $seen.ContainsKey($key)) { $seen[$key] = $value }
            }
            $kept.Add($line)
        }
        if ($kept.Count -ne $lines.Count) {
            [IO.File]::WriteAllBytes($file, $encoding.GetBytes(($kept -join '')))
            $touched += $path
        }
    }
    if ($touched.Count -gt 0) {
        [void](Invoke-Git (@('add', '--') + $touched))
        [void](Invoke-Git ((Get-Identity) + @('commit', '-q', '-m', 'Remove names written twice by joining the two sides')))
    }
    return $differ
}

# ---- the update ------------------------------------------------------------------------------

Write-Host 'Updating your copy of The Armory.'
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host 'Git is not installed. Install it from https://git-scm.com/download/win with its usual choices, then click Send my work.'
    exit 1
}

Send-Waiting
Clear-Unfinished
Set-MergeRule

Write-Host 'Getting the latest from GitHub...'
$online = $false
foreach ($try in 1..3) {
    $fetch = Invoke-Git @('fetch', 'origin')
    if ($fetch.Code -eq 0) { $online = $true; break }
    if ($try -lt 3) { Start-Sleep -Seconds 3 }
}

# The newest copy of this file does the rest, so a fix reaches a copy that could not finish.
if ($online -and -not $Fresh -and -not $NoRefresh) {
    $newest = Invoke-Git @('show', "origin/${Own}:Studio/Tools/update.ps1")
    $mine = ([IO.File]::ReadAllText($PSCommandPath) -replace "`r`n", "`n").Trim()
    if ($newest.Code -eq 0 -and ($newest.Out -replace "`r`n", "`n") -ne $mine) {
        $errors = $null
        [void][Management.Automation.Language.Parser]::ParseInput($newest.Out, [ref]$null, [ref]$errors)
        if (-not $errors) {
            $script = Get-GitPath 'armory-update.ps1'
            [IO.File]::WriteAllText($script, $newest.Out, (New-Object Text.UTF8Encoding $true))
            $passed = @{ Main = $Main; Own = $Own; Repo = $repo; Fresh = $true }
            if ($NoPush) { $passed.NoPush = $true }
            if ($OnOpen) { $passed.OnOpen = $true }
            if ($WorkFile) { $passed.WorkFile = $WorkFile }
            if ($NoteFile) { $passed.NoteFile = $NoteFile }
            & $script @passed
            exit $LASTEXITCODE
        }
    }
}

$branch = (Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD')).Out
$wrongBranch = $branch -eq 'HEAD' -or $branch -eq $Main -or -not $branch

# Her commit carries her own words about her work. The newest copy of this file has already run by here, and
# nothing has changed. On a wrong branch the commit waits for the move below.
if (-not $wrongBranch) {
    if ($WorkFile) { Save-Work }
    elseif ((Invoke-Git @('status', '--porcelain', '--untracked-files=no')).Out) {
        # HyCreator opening on work in progress: the look at GitHub above is all it needs.
        if ($OnOpen) { exit 0 }
        Write-Host ''
        Write-Host 'Write what you did in The Armory panel, then click Send my work.'
        exit 1
    }
}

if ($wrongBranch) {
    # The work on this branch comes along, so moving loses nothing.
    Save-Work
    $was = (Invoke-Git @('rev-parse', 'HEAD')).Out
    $move = if ((Invoke-Git @('rev-parse', '--verify', '--quiet', "refs/heads/$Own")).Code -eq 0) { @('switch', '-q', $Own) }
            elseif ((Invoke-Git @('rev-parse', '--verify', '--quiet', "origin/$Own")).Code -eq 0) { @('switch', '-q', '-c', $Own, "origin/$Own") }
            else { @('switch', '-q', '-c', $Own) }
    $moved = Invoke-Git $move
    if ($moved.Code -ne 0) { $moved = Invoke-Git ($move + @('--force')) }
    if ($moved.Code -eq 0) {
        $branch = $Own
        Write-Host "Your copy is now on your branch, $Own."
        if ((Invoke-Git @('rev-list', '--count', "HEAD..$was")).Out -ne '0') {
            $joined = Join-Source $was
            if ($joined.Ok -and (Invoke-Git @('rev-parse', '--verify', '--quiet', "origin/$Main")).Code -eq 0) { [void](Invoke-Git @('branch', '-f', $Main, "origin/$Main")) }
        }
    } else {
        Send-Problem 'Update could not move a copy to her branch' $moved.Text
    }
}

$start = (Invoke-Git @('rev-parse', 'HEAD')).Out
$brought = 0
$stuck = ''

# Joins both sides. A push that lost a race to a newer push comes back here.
function Join-All {
    foreach ($source in @("origin/$branch", "origin/$Main")) {
        if ((Invoke-Git @('rev-parse', '--verify', '--quiet', "$source^{commit}")).Code -ne 0) { continue }
        $new = [int](Invoke-Git @('rev-list', '--count', "HEAD..$source")).Out
        if ($new -eq 0) { continue }
        $joined = Join-Source $source
        if ($joined.Ok) { $script:brought += $new } else { $script:stuck = "$source`n$($joined.Text)" }
    }
}

Join-All
$differ = Remove-Doubled $start

if ($stuck) {
    # Git could not finish, and nothing here could settle it. Her work goes to GitHub on a branch of
    # its own, where we join it, and she goes on working.
    $aside = 'saved/' + (Invoke-Git @('rev-parse', '--short', 'HEAD')).Out
    $sent = if ($NoPush) { @{ Code = 0 } } else { Invoke-Git @('push', 'origin', "HEAD:refs/heads/$aside") }
    Send-Problem 'Update could not join two sides' "Her work: $aside (sent to GitHub: $($sent.Code -eq 0)).`n$stuck"
}
if ($settled.Count -gt 0 -or $oursKept.Count -gt 0 -or $laterKept.Count -gt 0 -or $differ.Count -gt 0) {
    $lines = @()
    if ($settled.Count -gt 0) { $lines += "Hers kept over ours:`n" + ($settled -join "`n") }
    if ($oursKept.Count -gt 0) { $lines += "Ours kept over hers:`n" + ($oursKept -join "`n") }
    if ($laterKept.Count -gt 0) { $lines += "Her later commit kept, from two computers:`n" + ($laterKept -join "`n") }
    if ($differ.Count -gt 0) { $lines += "Names written two ways, the first kept:`n" + ($differ -join "`n") }
    Send-Problem 'Update settled files both sides changed' (($lines -join "`n`n") + "`n`nBoth versions stay in the history.")
}

if ($brought -eq 0) { Write-Host 'Nothing new.' } else { Write-Host "Brought in $brought new change(s)." }

if (-not $NoPush) {
    $hasRemote = (Invoke-Git @('rev-parse', '--verify', '--quiet', "origin/$branch^{commit}")).Code -eq 0
    $ahead = if ($hasRemote) { [int](Invoke-Git @('rev-list', '--count', "origin/$branch..HEAD")).Out } else { 1 }
    if ($ahead -gt 0) {
        Write-Host 'Sending your work to GitHub...'
        $push = $null
        foreach ($try in 1..3) {
            $push = Invoke-Git @('push', '-u', 'origin', "HEAD:$branch")
            if ($push.Code -eq 0) { break }
            if ($push.Text -match 'fetch first|non-fast-forward|rejected') {
                [void](Invoke-Git @('fetch', 'origin'))
                Join-All
            } elseif ($try -lt 3) { Start-Sleep -Seconds 3 }
        }
        if ($push.Code -eq 0) {
            Write-Host 'Sent.'
        } else {
            Write-Host 'Your work is saved here. It goes to GitHub the next time it can.'
            if ($online) { Send-Problem 'Update could not send her work to GitHub' $push.Text }
        }
    }
}

Send-Note $NoteFile

# Git copies the code and the pages after a pull without a word, so a copy that fails while a world
# holds the code open would go unseen. Doing it here says so. A failed copy is not a failed update,
# so a release that runs this goes on: its line already says what to do.
& (Join-Path $repo 'Studio\Tools\sync.ps1')
if ($LASTEXITCODE -eq 0) { Write-Host ''; Write-Host 'Done.' }
exit 0
