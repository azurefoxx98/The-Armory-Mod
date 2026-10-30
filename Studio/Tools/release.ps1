# Makes a release of The Armory from this computer. The release form of The Armory's panel in HyCreator
# runs it (through run.ps1), and -Info is how that form learns the last version and what changed since.
#
# What it does, in order:
#   1. Brings in the mod's latest work, as Update does, so a release always holds it.
#   2. Takes the version, the changelog and what to do with the release from the form: save it as a
#      draft, save it as a draft with a message for us, or publish it.
#   3. Sets the version, builds the mod, optimises its model textures with proof, and stamps the jar.
#   4. Commits the version, tags it, pushes both, and creates the release on GitHub with the jar,
#      then downloads the jar back and compares it. A publish also moves main to the release.
#
# Nothing here refuses a publish she chose. When to release is agreed between people, never held by a
# tool. What the checks find shows in her panel, hers and ours, and stops nothing.
#
# The first run installs Java 25 and the two picture libraries the optimiser needs, each checked
# against the fingerprint its publisher gives. Nothing is installed outside the Armory's own folder.
param(
    # What the form gives: the version, a draft, a draft with a message, or a publish, and the files
    # holding the changelog and the message.
    [string] $Version = '',
    [ValidateSet('', 'draft', 'message', 'publish')] [string] $Choice = '',
    [string] $NotesFile = '',
    [string] $MessageFile = '',
    # Writes what the form shows to this file, then stops: the branch, the version now, the last
    # release, the next version, and the changes since the last release. Nothing else is done.
    [string] $Info = '',
    # Test switches. Leave out the GitHub steps, or take a given jar instead of building.
    [switch] $NoGitHub,
    [string] $Jar = '',
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

function Stop-Release([string] $text, [string] $detail = '') {
    Write-Host ''
    Write-Host $text
    if ($detail) { Write-Host "For Larsonix: $detail" }
    if ($text.Contains($send)) { & (Join-Path $PSScriptRoot 'update.ps1') -Report $text -Detail $detail }
    exit 1
}

# What she reads about anything only we can fix. Stop-Release sends it to us by itself.
$send = 'Larsonix has been told, and you can keep working.'
$downloadsAnnounced = $false

function Get-Checked([string] $url, [string] $sha256, [string] $what) {
    $file = Join-Path ([IO.Path]::GetTempPath()) ('TheArmory-' + [IO.Path]::GetFileName(([Uri]$url).LocalPath))
    if (-not $script:downloadsAnnounced) {
        Write-Host 'First release on this computer: downloading what releases need (about 160 MB, only once)...'
        $script:downloadsAnnounced = $true
    }
    $ProgressPreference = 'SilentlyContinue'
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    foreach ($try in 1..3) {
        try {
            Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing
        } catch {
            if ($try -lt 3) { Start-Sleep -Seconds 5; continue }
            Stop-Release 'A release needs the internet for its first download. Click Release once you are back online.' "$what, $($_.Exception.Message)"
        }
        if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -eq $sha256) { return $file }
        Remove-Item -LiteralPath $file -Force
    }
    Stop-Release "A download kept arriving damaged. $send" "$what does not match its published checksum"
}

# ---- what a release needs on this computer ----------------------------------------------------

function Install-Tools {
    $python = (Invoke-Git @('config', '--get', 'armory.python')).Out
    if (-not $python -or -not (Test-Path -LiteralPath $python)) {
        & (Join-Path $PSScriptRoot 'setup.ps1') -ChecksOnly
        $python = (Invoke-Git @('config', '--get', 'armory.python')).Out
        if (-not $python -or -not (Test-Path -LiteralPath $python)) { Stop-Release "The release tools could not be installed. $send" 'still missing after the install' }
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
        $file = Get-Checked $javaUrl $javaSha256 'Java 25'
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
    $text = [IO.File]::ReadAllText((Join-Path $repo 'gradle.properties'))
    if ($text -notmatch "(?m)^version\s*=\s*([0-9]+\.[0-9]+\.[0-9]+)\s*$") { Stop-Release "The mod's version was not found. $send" 'no version line found' }
    return $Matches[1]
}

function Compare-Version([string] $a, [string] $b) {
    $x = $a.Split('.') | ForEach-Object { [int]$_ }
    $y = $b.Split('.') | ForEach-Object { [int]$_ }
    for ($i = 0; $i -lt 3; $i++) { if ($x[$i] -ne $y[$i]) { return [Math]::Sign($x[$i] - $y[$i]) } }
    return 0
}

# Each file is set whatever version it holds: joining two sides can leave the three disagreeing.
function Set-ModVersion([string] $new) {
    $number = '[0-9]+\.[0-9]+\.[0-9]+'
    $files = @(
        @{ Path = 'gradle.properties'; From = "(?m)^(version\s*=\s*)$number"; To = "`${1}$new" },
        @{ Path = 'src\main\resources\manifest.json'; From = "(`"Version`":\s*`")$number(`")"; To = "`${1}$new`${2}" },
        @{ Path = '.hytale\project.json'; From = "(`"version`":\s*`")$number(`")"; To = "`${1}$new`${2}" },
        @{ Path = '.hytale\project.json'; From = "(TheArmoryMod-)$number(\.jar)"; To = "`${1}$new`${2}" }
    )
    foreach ($f in $files) {
        $path = Join-Path $repo $f.Path
        $text = [IO.File]::ReadAllText($path)
        if ([regex]::Matches($text, $f.From).Count -ne 1) { Stop-Release "The mod's version could not be set. $send" "$($f.Path) does not hold its version where expected" }
        [IO.File]::WriteAllText($path, [regex]::Replace($text, $f.From, $f.To), (New-Object Text.UTF8Encoding $false))
    }
}

# A text she wrote in the form, kept in a file by the panel.
function Read-Written([string] $given) {
    if (-not $given -or -not (Test-Path -LiteralPath $given)) { return '' }
    return ([IO.File]::ReadAllText($given)).Trim()
}

# The last released version and the one after it, from the version tags and the build file.
function Get-Versions {
    $current = Read-ModVersion
    $tags = @((Invoke-Git @('tag', '--list', 'v*')).Out -split "`n" | Where-Object { $_ -match '^v[0-9]+\.[0-9]+\.[0-9]+$' } | ForEach-Object { $_.Substring(1) })
    $last = $tags | Sort-Object { [version]$_ } | Select-Object -Last 1
    $base = if ($last -and (Compare-Version $last $current) -ge 0) { $last } else { $current }
    $parts = $base.Split('.')
    return @{ Current = $current; Tags = $tags; Last = $last; Suggested = "$($parts[0]).$($parts[1]).$([int]$parts[2] + 1)" }
}

# What changed since the last release, as the commits name it, to help her write the changelog.
function Get-Changes($versions) {
    $since = if ($versions.Last) { "v$($versions.Last)..HEAD" } else { 'HEAD' }
    return @((Invoke-Git @('log', '--no-merges', '--format=%s', $since)).Out -split "`n" | Where-Object { $_ } | Select-Object -First 60)
}

# ---- GitHub --------------------------------------------------------------------------------

function Get-GitHub {
    $url = (Invoke-Git @('remote', 'get-url', 'origin')).Out
    if ($url -notmatch 'github\.com[:/]([^/]+)/([^/]+?)(\.git)?$') { Stop-Release "This copy's GitHub address was not understood. $send" $url }
    $owner, $name = $Matches[1], $Matches[2]
    # The sign-in git already keeps for GitHub, the one HyCreator's Push uses.
    $ErrorActionPreference = 'Continue'
    $answer = "protocol=https`nhost=github.com`n`n" | & git credential fill 2>$null
    $ErrorActionPreference = 'Stop'
    $token = ($answer | Where-Object { $_ -like 'password=*' } | Select-Object -First 1) -replace '^password=', ''
    # GitHub's own command line keeps a sign-in too, when it is installed.
    if (-not $token -and (Get-Command gh -ErrorAction SilentlyContinue)) {
        $ErrorActionPreference = 'Continue'
        $token = (& gh auth token 2>$null | Select-Object -First 1)
        $ErrorActionPreference = 'Stop'
    }
    if (-not $token) { Stop-Release "GitHub does not know you on this computer yet. In HyCreator, click $branch at the top, then Push, and sign in. Then click Release again." }
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    return @{ Api = "https://api.github.com/repos/$owner/$name"; Upload = "https://uploads.github.com/repos/$owner/$name"
              Headers = @{ Authorization = "Bearer $token"; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' } }
}

# ---- the release ---------------------------------------------------------------------------

$branch = (Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD')).Out

if ($Info) {
    # The tags others pushed are fetched first. Offline, the ones this copy has are used.
    [void](Invoke-Git @('fetch', '--tags', 'origin'))
    $versions = Get-Versions
    $form = [ordered]@{
        branch = $branch; current = $versions.Current; last = $versions.Last; suggested = $versions.Suggested
        released = @($versions.Tags); changes = @(Get-Changes $versions)
        uncommitted = [bool](Invoke-Git @('status', '--porcelain', '--untracked-files=no')).Out
    }
    [IO.File]::WriteAllText($Info, ($form | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding $false))
    exit 0
}

Write-Host 'Making a release of The Armory.'

# Update commits her work, puts the copy on her branch and joins the latest, so a release holds all of it.
if ($NoGitHub) { & (Join-Path $PSScriptRoot 'update.ps1') -NoPush -NoRefresh } else { & (Join-Path $PSScriptRoot 'update.ps1') }
if ($LASTEXITCODE -ne 0) { exit 1 }
$branch = (Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD')).Out
[void](Invoke-Git @('fetch', '--tags', 'origin'))

$versions = Get-Versions
$current = $versions.Current
$tags = $versions.Tags
$last = $versions.Last
$suggested = $versions.Suggested
# An empty version in the form means the one it suggests.
if (-not $Version.Trim()) { $Version = $suggested }
$Version = $Version.Trim().TrimStart('v')
$again = "Type a version like $suggested, then click Release again."
if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { Stop-Release "$Version is not a version number. $again" }
if ($last -and (Compare-Version $Version $last) -le 0) { Stop-Release "$Version is not newer than the last release, $last. $again" }
if ($tags -contains $Version) { Stop-Release "$Version is already released. $again" }
if (-not $Choice) { Stop-Release 'Release stopped: choose a draft, a draft with a message, or publish.' }

$notes = Read-Written $NotesFile
if (-not $notes) { Stop-Release 'Release stopped: the changelog was empty.' }
$message = ''
if ($Choice -eq 'message') {
    $message = Read-Written $MessageFile
    if (-not $message) { Stop-Release 'Release stopped: the message was empty.' }
}

# Build.
Set-ModVersion $Version
try {
    if ($Jar) {
        $built = $Jar
    } else {
        $python = Install-Tools
        # The build fetches the newest release of the game's server from Hypixel by itself, so there
        # is no engine copy here to fall behind after a game update.
        $env:JAVA_HOME = $javaHome
        $tools = (Join-Path $repo 'Studio\Tools\Release') -replace '\\', '/'
        Write-Host 'Building the release. This takes a few minutes.'
        # The build prints thousands of lines nobody needs to read. They go to a file, and only
        # the end of it is shown when the build fails.
        $log = Join-Path $ToolsHome 'last-release-build.log'
        New-Item -ItemType Directory -Force -Path $ToolsHome | Out-Null
        Push-Location $repo
        try {
            $pythonArg = "-Ptexopt.python=$($python -replace '\\', '/')"
            $code = Invoke-Native { & .\gradlew.bat clean build releaseJar --console=plain $pythonArg '-Ptexopt.pythonpath=' "-Ptexopt.tools=$tools" 2>&1 | ForEach-Object { "$_" } | Out-File -LiteralPath $log -Encoding utf8 }
        } finally { Pop-Location }
        if ($code -ne 0) {
            Write-Host ''
            Get-Content -LiteralPath $log -Tail 25 | ForEach-Object { Write-Host "  $_" }
            throw "the build failed, the whole log is $log"
        }
        $built = Join-Path $repo "build\release\TheArmoryMod-$Version.jar"
        if (-not (Test-Path -LiteralPath $built)) { throw "no release jar at $built" }
    }
} catch {
    [void](Invoke-Git @('checkout', '--', 'gradle.properties', 'src/main/resources/manifest.json', '.hytale/project.json'))
    $tail = if ($log -and (Test-Path -LiteralPath $log)) { (Get-Content -LiteralPath $log -Tail 25) -join "`n" } else { '' }
    Stop-Release "The release could not be built, so nothing was released. $send" "$($_.Exception.Message)`n$tail"
}
Write-Host 'Built.'

# The version files, and the code-only jar when the build rewrote it (it changes only with the code).
$paths = @('gradle.properties', 'src/main/resources/manifest.json', '.hytale/project.json', 'Studio/Tools/TheArmory-code.jar')
if ((Invoke-Git (@('status', '--porcelain', '--') + $paths)).Out) {
    $commit = Invoke-Git (@('commit', '-q', '-m', "Release $Version", '--') + $paths)
    if ($commit.Code -ne 0) { Stop-Release "The version could not be committed. $send" $commit.Text }
}
# Only a publish is tagged here. A draft names its tag and GitHub makes it when the draft is
# published, so a draft can be made again, or thrown away, without a tag left behind.
if ($Choice -eq 'publish') {
    $tag = Invoke-Git @('tag', '-a', "v$Version", '-m', "The Armory $Version")
    if ($tag.Code -ne 0) { Stop-Release "The version could not be tagged. $send" $tag.Text }
}
$releaseCommit = (Invoke-Git @('rev-parse', 'HEAD')).Out
$hash = (Get-FileHash -LiteralPath $built -Algorithm SHA256).Hash.ToLower()

if ($NoGitHub) {
    Write-Host "Done here, GitHub left out ($Choice)."
    exit 0
}

$refs = @("HEAD:$branch"); if ($Choice -eq 'publish') { $refs += "v$Version" }
$push = Invoke-Git (@('push', 'origin') + $refs)
if ($push.Code -ne 0) {
    # The tag goes too, so the next Release does not read this version as released already.
    if ($Choice -eq 'publish') { [void](Invoke-Git @('tag', '-d', "v$Version")) }
    if ((Invoke-Git @('fetch', 'origin')).Code -ne 0) { Stop-Release 'The release is built, but GitHub cannot be reached. Click Release once you are back online.' }
    Stop-Release "The release is built, but GitHub did not take it. $send" $push.Text
}
$gh = Get-GitHub
try {
# The message goes in with the changelog when the draft is made: editing a draft afterwards makes
# GitHub forget the tag it names, and publishing it would then make no tag.
$text = if ($Choice -eq 'message') { "$notes`r`n`r`n---`r`nMessage for us:`r`n$message" } else { $notes }
$body = @{ tag_name = "v$Version"; target_commitish = $releaseCommit; name = "The Armory $Version"; body = $text; draft = ($Choice -ne 'publish') } | ConvertTo-Json
$release = Invoke-RestMethod -Method Post -Uri "$($gh.Api)/releases" -Headers $gh.Headers -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/json'
$asset = Invoke-RestMethod -Method Post -Uri "$($gh.Upload)/releases/$($release.id)/assets?name=TheArmoryMod-$Version.jar" -Headers $gh.Headers -InFile $built -ContentType 'application/java-archive'

# The jar as GitHub serves it must be the jar that was built.
$back = Join-Path ([IO.Path]::GetTempPath()) "TheArmory-check-$Version.jar"
$headers = $gh.Headers.Clone(); $headers['Accept'] = 'application/octet-stream'
Invoke-WebRequest -Uri "$($gh.Api)/releases/assets/$($asset.id)" -Headers $headers -OutFile $back -UseBasicParsing
$same = (Get-FileHash -LiteralPath $back -Algorithm SHA256).Hash.ToLower() -eq $hash
Remove-Item -LiteralPath $back -Force
if (-not $same) { Stop-Release "GitHub holds a different file than the one built. $send" $release.html_url }

if ($Choice -eq 'message') {
    $issue = @{ title = "A release draft from $branch is waiting: $Version"; body = "@$reviewer A release draft with a message for you is on GitHub, under Releases. The message is in the draft." } | ConvertTo-Json
    [void](Invoke-RestMethod -Method Post -Uri "$($gh.Api)/issues" -Headers $gh.Headers -Body ([Text.Encoding]::UTF8.GetBytes($issue)) -ContentType 'application/json')
}
if ($Choice -eq 'publish') {
    # Main always equals the latest release. This branch holds main already (Update joined it), so
    # main only moves forward and nothing on it is replaced.
    $move = @{ sha = $releaseCommit; force = $false } | ConvertTo-Json
    try {
        [void](Invoke-RestMethod -Method Patch -Uri "$($gh.Api)/git/refs/heads/$main" -Headers $gh.Headers -Body $move -ContentType 'application/json')
    } catch {
        Write-Host "The release is out. One last step on GitHub failed. $send"
        & (Join-Path $PSScriptRoot 'update.ps1') -Report 'A release did not move main' "$main was not moved to v$Version"
    }
}
} catch {
    Stop-Release "GitHub refused the release. $send" $_.Exception.Message
}

Write-Host ''
switch ($Choice) {
    'draft' { Write-Host "Done. Saved as a draft: $($release.html_url)" }
    'message' { Write-Host "Done. Saved as a draft, and Larsonix has your message: $($release.html_url)" }
    'publish' { Write-Host "Done. Published: $($release.html_url)" }
}
exit 0
