# Makes a release of The Armory from this computer. Run it through Studio\Release.cmd.
#
# What it does, in order:
#   1. Brings in the mod's latest work, as Update does, so a release always holds it.
#   2. Asks for the version, the changelog (in Notepad) and what to do with the release:
#      save it as a draft, save it as a draft with a message for us, or publish it.
#   3. For a publish, makes sure it is safe to ship: the mod's checks pass, no hold is placed on
#      the branch, and the mod's code has not changed since the last release (a code change needs
#      our own release checks). If one is not met, it says why and offers a draft instead.
#   4. Sets the version, builds the mod, optimises its model textures with proof, and stamps the jar.
#   5. Commits the version, tags it, pushes both, and creates the release on GitHub with the jar,
#      then downloads the jar back and compares it. A publish also moves main to the release.
#
# The first run installs Java 25 and the two picture libraries the optimiser needs, each checked
# against the fingerprint its publisher gives. Nothing is installed outside the Armory's own folder.
param(
    # Test switches. Leave out the GitHub steps, take a given jar instead of building, or answer
    # the questions from these values.
    [switch] $NoGitHub,
    [string] $Jar = '',
    [string] $Version = '',
    [ValidateSet('', 'draft', 'message', 'publish')] [string] $Choice = '',
    [string] $NotesFile = '',
    [string] $MessageFile = '',
    [switch] $AcceptDraft,
    [string] $ToolsHome = (Join-Path $env:LOCALAPPDATA 'TheArmory')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$main = 'main'
# Who a draft with a message is for. GitHub tells this account.
$reviewer = 'Larsonix'

$javaUrl = 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip'
$javaSha256 = '00C847D804F4A78E9F04F2683FAF14FED898535B177B7FC704486CB0284E9283'
$javaHome = Join-Path $ToolsHome 'jdk-25.0.4.1+1'
$wheels = @(
    @{ Name = 'numpy 2.5.4'; Test = 'numpy'; Sha256 = '332F3378FE077DD850E677EC01BDCC4F22368FB5D50EF10B2C79230B1BF5A592'
       Url = 'https://files.pythonhosted.org/packages/50/bc/d2651b155ecc608a77e6f4d15495c11f14f19bb98f8bf0c5b0d38f86dda1/numpy-2.5.4-cp315-cp315-win_amd64.whl' },
    @{ Name = 'Pillow 13.0.0'; Test = 'PIL'; Sha256 = 'D4737F50D23DDD11D6685FAACBB9252887DDE0BC7997127757247983D74399D6'
       Url = 'https://files.pythonhosted.org/packages/44/05/fdb6a79aee10861165526b41b7b440e36ee1ff58613b2fbefe3acbf06d4f/pillow-13.0.0-cp315-cp315-win_amd64.whl' }
)

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
    return @{ Code = $process.ExitCode; Out = $out.Result.Trim(); Text = ($out.Result + $err.Result).Trim() }
}

# Runs a program and returns its exit code. Windows PowerShell 5.1 stops a script whenever a
# program writes to its error stream, which git, Python and Gradle all do in ordinary work, so the
# program's own exit code is the only judge here.
function Invoke-Native([scriptblock] $block) {
    $ErrorActionPreference = 'Continue'
    & $block
    return $LASTEXITCODE
}

function Stop-Release([string] $text) {
    Write-Host ''
    Write-Host $text
    exit 1
}

function Ask([string] $question) {
    $answer = Read-Host "$question (y/n)"
    return $answer -match '^\s*y'
}

function Get-Checked([string] $url, [string] $sha256, [string] $what) {
    $file = Join-Path ([IO.Path]::GetTempPath()) ('TheArmory-' + [IO.Path]::GetFileName(([Uri]$url).LocalPath))
    Write-Host "Downloading $what..."
    $ProgressPreference = 'SilentlyContinue'
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $sha256) {
        Remove-Item -LiteralPath $file -Force
        Stop-Release "$what does not match the fingerprint its publisher gives, so it was refused. Nothing was changed."
    }
    return $file
}

# ---- what a release needs on this computer ----------------------------------------------------

function Install-Tools {
    $python = (Invoke-Git @('config', '--get', 'armory.python')).Out
    if (-not $python -or -not (Test-Path -LiteralPath $python)) {
        Stop-Release 'The checks are not installed yet. Double-click Studio\Setup.cmd once, then try again.'
    }
    $pythonHome = Split-Path -Parent $python
    $site = Join-Path $pythonHome 'Lib\site-packages'
    $pth = Get-ChildItem -LiteralPath $pythonHome -Filter 'python*._pth' | Select-Object -First 1
    if ($pth -and -not ((Get-Content -LiteralPath $pth.FullName) -contains 'Lib\site-packages')) {
        Add-Content -LiteralPath $pth.FullName 'Lib\site-packages'
    }
    foreach ($wheel in $wheels) {
        $test = $wheel.Test
        if ((Invoke-Native { & $python -c "import $test" 2>&1 | Out-Null }) -eq 0) { continue }
        $file = Get-Checked $wheel.Url $wheel.Sha256 $wheel.Name
        New-Item -ItemType Directory -Force -Path $site | Out-Null
        $zip = [IO.Compression.ZipFile]::OpenRead($file)
        try {
            foreach ($entry in $zip.Entries) {
                if (-not $entry.Name) { continue }
                $target = Join-Path $site $entry.FullName
                New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            }
        } finally { $zip.Dispose() }
        Remove-Item -LiteralPath $file -Force
    }
    if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) {
        $file = Get-Checked $javaUrl $javaSha256 'Java 25 (140 MB)'
        $staging = "$javaHome.partial"
        if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
        [IO.Compression.ZipFile]::ExtractToDirectory($file, $staging)
        $inner = Get-ChildItem -LiteralPath $staging -Directory | Select-Object -First 1
        Move-Item -LiteralPath $inner.FullName -Destination $javaHome
        Remove-Item -LiteralPath $staging -Recurse -Force
        Remove-Item -LiteralPath $file -Force
    }
    return $python
}

# ---- the version -----------------------------------------------------------------------------

function Read-ModVersion {
    $text = [IO.File]::ReadAllText((Join-Path $repo 'build.gradle'))
    if ($text -notmatch "(?m)^version = '([0-9]+\.[0-9]+\.[0-9]+)'") { Stop-Release 'build.gradle holds no version line. Tell Larsonix.' }
    return $Matches[1]
}

function Compare-Version([string] $a, [string] $b) {
    $x = $a.Split('.') | ForEach-Object { [int]$_ }
    $y = $b.Split('.') | ForEach-Object { [int]$_ }
    for ($i = 0; $i -lt 3; $i++) { if ($x[$i] -ne $y[$i]) { return [Math]::Sign($x[$i] - $y[$i]) } }
    return 0
}

function Set-ModVersion([string] $old, [string] $new) {
    $files = @(
        @{ Path = 'build.gradle'; From = "version = '$old'"; To = "version = '$new'" },
        @{ Path = 'src\main\resources\manifest.json'; From = "`"Version`": `"$old`""; To = "`"Version`": `"$new`"" },
        @{ Path = '.hytale\project.json'; From = "`"version`": `"$old`""; To = "`"version`": `"$new`"" },
        @{ Path = '.hytale\project.json'; From = "TheArmoryMod-$old.jar"; To = "TheArmoryMod-$new.jar" }
    )
    foreach ($f in $files) {
        $path = Join-Path $repo $f.Path
        $text = [IO.File]::ReadAllText($path)
        $count = ([regex]::Matches($text, [regex]::Escape($f.From))).Count
        if ($count -ne 1) { Stop-Release "$($f.Path) does not hold its version where expected. Tell Larsonix. Nothing was released." }
        [IO.File]::WriteAllText($path, $text.Replace($f.From, $f.To), (New-Object Text.UTF8Encoding $false))
    }
}

# A text she writes in a window of its own, with a reminder beside it. Notepad is not used: on
# Windows 11 it opens a file in a tab of a Notepad already open and returns at once, which would
# read an empty text.
function Read-Written([string] $title, [string] $reminder, [string] $given) {
    if ($given) { return ([IO.File]::ReadAllText($given)).Trim() }
    Add-Type -AssemblyName System.Windows.Forms
    $form = New-Object Windows.Forms.Form
    $form.Text = "The Armory: $title"; $form.Width = 900; $form.Height = 640; $form.StartPosition = 'CenterScreen'
    $help = New-Object Windows.Forms.TextBox
    $help.Multiline = $true; $help.ReadOnly = $true; $help.ScrollBars = 'Vertical'; $help.Dock = 'Top'; $help.Height = 200
    $help.Text = $reminder
    $box = New-Object Windows.Forms.TextBox
    $box.Multiline = $true; $box.ScrollBars = 'Vertical'; $box.Dock = 'Fill'; $box.AcceptsReturn = $true
    $box.Font = New-Object Drawing.Font('Segoe UI', 11)
    $buttons = New-Object Windows.Forms.FlowLayoutPanel
    $buttons.Dock = 'Bottom'; $buttons.Height = 44; $buttons.FlowDirection = 'RightToLeft'
    $ok = New-Object Windows.Forms.Button; $ok.Text = 'Done'; $ok.Width = 120; $ok.DialogResult = 'OK'
    $cancel = New-Object Windows.Forms.Button; $cancel.Text = 'Cancel'; $cancel.Width = 120; $cancel.DialogResult = 'Cancel'
    $buttons.Controls.AddRange(@($ok, $cancel))
    $form.Controls.AddRange(@($box, $help, $buttons))
    $form.CancelButton = $cancel
    $form.Add_Shown({ $box.Focus() })
    if ($form.ShowDialog() -ne 'OK') { return '' }
    return $box.Text.Trim()
}

# ---- GitHub --------------------------------------------------------------------------------

function Get-GitHub {
    $url = (Invoke-Git @('remote', 'get-url', 'origin')).Out
    if ($url -notmatch 'github\.com[:/]([^/]+)/([^/]+?)(\.git)?$') { Stop-Release "This copy's GitHub address was not understood: $url" }
    $owner, $name = $Matches[1], $Matches[2]
    # The sign-in git already keeps for GitHub, the one HyCreator's Push uses.
    $ErrorActionPreference = 'Continue'
    $answer = "protocol=https`nhost=github.com`n`n" | & git credential fill 2>$null
    $ErrorActionPreference = 'Stop'
    $token = ($answer | Where-Object { $_ -like 'password=*' } | Select-Object -First 1) -replace '^password=', ''
    if (-not $token) { Stop-Release 'GitHub has no sign-in on this computer yet. Push once in HyCreator, then try again.' }
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    return @{ Api = "https://api.github.com/repos/$owner/$name"; Upload = "https://uploads.github.com/repos/$owner/$name"
              Headers = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' } }
}

# ---- the release ---------------------------------------------------------------------------

Write-Host 'The Armory: making a release.'
$branch = (Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD')).Out
if ($branch -eq $main -or $branch -eq 'HEAD' -or -not $branch) { Stop-Release "Releases are made from your own branch, not $main. Switch in HyCreator's git menu, Branches..., then try again." }
if ((Invoke-Git @('status', '--porcelain', '--untracked-files=no')).Out) { Stop-Release 'Some work is not committed yet. Commit it in HyCreator first, then try again. Nothing was changed.' }

if (-not $NoGitHub) {
    & (Join-Path $PSScriptRoot 'update.ps1')
    if ($LASTEXITCODE -ne 0) { exit 1 }
}
[void](Invoke-Git @('fetch', '--tags', 'origin'))

$current = Read-ModVersion
$tags = (Invoke-Git @('tag', '--list', 'v*')).Out -split "`n" | Where-Object { $_ -match '^v[0-9]+\.[0-9]+\.[0-9]+$' } | ForEach-Object { $_.Substring(1) }
$last = $tags | Sort-Object { [version]$_ } | Select-Object -Last 1
$base = if ($last -and (Compare-Version $last $current) -ge 0) { $last } else { $current }
$parts = $base.Split('.'); $suggested = "$($parts[0]).$($parts[1]).$([int]$parts[2] + 1)"
if (-not $Version) {
    Write-Host ''
    Write-Host "The mod is at $current. The last release is $(if ($last) { $last } else { 'none' })."
    $Version = Read-Host "Version of this release (Enter for $suggested)"
    if (-not $Version.Trim()) { $Version = $suggested }
}
$Version = $Version.Trim().TrimStart('v')
if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { Stop-Release "$Version is not a version like 1.25.1. Nothing was changed." }
if ($last -and (Compare-Version $Version $last) -le 0) { Stop-Release "$Version is not newer than the last release, $last. Nothing was changed." }
if ($tags -contains $Version) { Stop-Release "v$Version exists already. Nothing was changed." }

if (-not $Choice) {
    Write-Host ''
    Write-Host 'What should happen with this release?'
    Write-Host '  1  Save it as a draft. Nobody sees it yet.'
    Write-Host '  2  Save it as a draft with a message for us, such as an effect you would like added.'
    Write-Host '  3  Publish it. Players can download it at once.'
    $pick = Read-Host 'Type 1, 2 or 3'
    $Choice = @{ '1' = 'draft'; '2' = 'message'; '3' = 'publish' }[$pick.Trim()]
    if (-not $Choice) { Stop-Release 'Nothing was chosen. Nothing was changed.' }
}

$since = if ($last) { "v$last..HEAD" } else { 'HEAD' }
$changes = (Invoke-Git @('log', '--no-merges', '--format=%s', $since)).Out -split "`n" | Where-Object { $_ } | Select-Object -First 60
$reminder = "Write the changelog players will read in the box below, then press Done.`r`n`r`nWhat changed since the last release, as a reminder only:`r`n" + (($changes | ForEach-Object { "  - $_" }) -join "`r`n")
$notes = Read-Written "changelog of $Version" $reminder $NotesFile
if (-not $notes) { Stop-Release 'The changelog is empty, so nothing was released.' }
$message = ''
if ($Choice -eq 'message') {
    $message = Read-Written 'message for us' "What would you like us to do with this release? Write it below, then press Done.`r`nIt stays inside the draft, which only the mod's team can see." $MessageFile
    if (-not $message) { Stop-Release 'The message is empty, so nothing was released.' }
}

# What a publish needs, checked before anything changes.
if ($Choice -eq 'publish') {
    $why = @()
    $hold = Join-Path $repo 'Studio\release-hold.txt'
    if (Test-Path -LiteralPath $hold) { $why += 'This branch holds work that may not be released yet: ' + ([IO.File]::ReadAllText($hold)).Trim() }
    if ($last) {
        $code = (Invoke-Git @('diff', '--name-only', "v$last", 'HEAD', '--', 'src/main/java', 'settings.gradle', 'gradle')).Out
        $gradle = (Invoke-Git @('diff', "v$last", 'HEAD', '--', 'build.gradle')).Out -split "`n" |
            Where-Object { $_ -match '^[+-][^+-]' -and $_ -notmatch "^[+-]version = '" }
        if ($code -or $gradle) { $why += 'The code of the mod changed since the last release, and a code change goes through our own release checks first.' }
    }
    $python = (Invoke-Git @('config', '--get', 'armory.python')).Out
    if ($python -and (Test-Path -LiteralPath $python)) {
        Write-Host 'Running the checks...'
        $checker = Join-Path $repo 'Studio\Tools\Checks\check.py'
        if ((Invoke-Native { & $python $checker 2>&1 | Out-Host }) -ne 0) { $why += 'The checks found something, listed above.' }
    } else {
        $why += 'The checks are not installed. Double-click Studio\Setup.cmd once.'
    }
    if ($why.Count) {
        Write-Host ''
        Write-Host 'This release cannot be published yet:'
        $why | ForEach-Object { Write-Host "  - $_" }
        if (-not $AcceptDraft -and -not (Ask 'Save it as a draft with a message for us instead?')) { Stop-Release 'Nothing was changed.' }
        $Choice = 'message'
        $message = ($why | ForEach-Object { "- $_" }) -join "`r`n"
    }
}

# Build. A draft made again for the same version finds the version set already.
if ($current -ne $Version) { Set-ModVersion $current $Version }
try {
    if ($Jar) {
        $built = $Jar
    } else {
        $python = Install-Tools
        # The engine jar is copied from the installed game only when missing, so an old copy would be
        # compiled against after a game update. Take the installed one every time.
        $engine = Join-Path $repo 'libs\HytaleServer.jar'
        if (Test-Path -LiteralPath $engine) { Remove-Item -LiteralPath $engine -Force }
        $env:JAVA_HOME = $javaHome
        $tools = (Join-Path $repo 'Studio\Tools\Release') -replace '\\', '/'
        Write-Host 'Building the release. The first time takes a few minutes.'
        Push-Location $repo
        try {
            $pythonArg = "-Ptexopt.python=$($python -replace '\\', '/')"
            $code = Invoke-Native { & .\gradlew.bat clean build releaseJar --console=plain $pythonArg '-Ptexopt.pythonpath=' "-Ptexopt.tools=$tools" 2>&1 | Out-Host }
        } finally { Pop-Location }
        if ($code -ne 0) { throw 'the build failed, see above' }
        $built = Join-Path $repo "build\release\TheArmoryMod-$Version.jar"
        if (-not (Test-Path -LiteralPath $built)) { throw "no release jar at $built" }
    }
} catch {
    [void](Invoke-Git @('checkout', '--', 'build.gradle', 'src/main/resources/manifest.json', '.hytale/project.json'))
    Stop-Release "The release could not be built: $($_.Exception.Message). The version was put back. Tell Larsonix."
}

# The version files, and the code-only jar when the build rewrote it (it changes only with the code).
$paths = @('build.gradle', 'src/main/resources/manifest.json', '.hytale/project.json', 'Studio/Tools/TheArmory-code.jar')
if ((Invoke-Git (@('status', '--porcelain', '--') + $paths)).Out) {
    $commit = Invoke-Git (@('commit', '-q', '-m', "Release $Version", '--') + $paths)
    if ($commit.Code -ne 0) { Stop-Release "The version could not be committed: $($commit.Text)" }
}
# Only a publish is tagged here. A draft names its tag and GitHub makes it when the draft is
# published, so a draft can be made again, or thrown away, without a tag left behind.
if ($Choice -eq 'publish') {
    $tag = Invoke-Git @('tag', '-a', "v$Version", '-m', "The Armory $Version")
    if ($tag.Code -ne 0) { Stop-Release "The version could not be tagged: $($tag.Text)" }
}
$releaseCommit = (Invoke-Git @('rev-parse', 'HEAD')).Out
$hash = (Get-FileHash -LiteralPath $built -Algorithm SHA256).Hash.ToLower()
Write-Host "Built TheArmoryMod-$Version.jar, SHA-256 $hash"

if ($NoGitHub) {
    Write-Host "Done here, GitHub left out ($Choice)."
    exit 0
}

$refs = @("HEAD:$branch"); if ($Choice -eq 'publish') { $refs += "v$Version" }
$push = Invoke-Git (@('push', 'origin') + $refs)
if ($push.Code -ne 0) { Stop-Release "The release is built here, but sending it failed: $($push.Text)`nTell Larsonix." }
$gh = Get-GitHub
try {
$body = @{ tag_name = "v$Version"; target_commitish = $releaseCommit; name = "The Armory $Version"; body = $notes; draft = ($Choice -ne 'publish') } | ConvertTo-Json
$release = Invoke-RestMethod -Method Post -Uri "$($gh.Api)/releases" -Headers $gh.Headers -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/json'
$asset = Invoke-RestMethod -Method Post -Uri "$($gh.Upload)/releases/$($release.id)/assets?name=TheArmoryMod-$Version.jar" -Headers $gh.Headers -InFile $built -ContentType 'application/java-archive'

# The jar as GitHub serves it must be the jar that was built.
$back = Join-Path ([IO.Path]::GetTempPath()) "TheArmory-check-$Version.jar"
$headers = $gh.Headers.Clone(); $headers['Accept'] = 'application/octet-stream'
Invoke-WebRequest -Uri "$($gh.Api)/releases/assets/$($asset.id)" -Headers $headers -OutFile $back -UseBasicParsing
$same = (Get-FileHash -LiteralPath $back -Algorithm SHA256).Hash.ToLower() -eq $hash
Remove-Item -LiteralPath $back -Force
if (-not $same) { Stop-Release "GitHub holds a different jar than the one built. The release is $($release.html_url). Tell Larsonix." }

if ($Choice -eq 'message') {
    $issue = @{ title = "A release draft from $branch is waiting: $Version"; body = "@$reviewer A release draft with a message for you is on GitHub, under Releases. The message is in the draft." } | ConvertTo-Json
    [void](Invoke-RestMethod -Method Post -Uri "$($gh.Api)/issues" -Headers $gh.Headers -Body ([Text.Encoding]::UTF8.GetBytes($issue)) -ContentType 'application/json')
    $withMessage = @{ body = "$notes`r`n`r`n---`r`nMessage for us:`r`n$message" } | ConvertTo-Json
    [void](Invoke-RestMethod -Method Patch -Uri "$($gh.Api)/releases/$($release.id)" -Headers $gh.Headers -Body ([Text.Encoding]::UTF8.GetBytes($withMessage)) -ContentType 'application/json')
}
if ($Choice -eq 'publish') {
    # Main always equals the latest release. This branch holds main already (Update joined it), so
    # main only moves forward and nothing on it is replaced.
    $move = @{ sha = $releaseCommit; force = $false } | ConvertTo-Json
    try {
        [void](Invoke-RestMethod -Method Patch -Uri "$($gh.Api)/git/refs/heads/$main" -Headers $gh.Headers -Body $move -ContentType 'application/json')
    } catch {
        Write-Host "The release is out, but $main could not be moved to it. Tell Larsonix."
    }
}
} catch {
    Stop-Release "GitHub refused a step of the release: $($_.Exception.Message)`nThe release commit is on your branch. Tell Larsonix what this says."
}

Write-Host ''
switch ($Choice) {
    'draft' { Write-Host "Saved as a draft: $($release.html_url)" }
    'message' { Write-Host "Saved as a draft, and Larsonix is told: $($release.html_url)" }
    'publish' { Write-Host "Published: $($release.html_url)" }
}
exit 0
