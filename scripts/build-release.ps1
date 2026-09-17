[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [ValidateSet('Debug', 'Release')][string]$Mode = 'Debug',
    [switch]$Publish,
    [string]$Remote = 'origin',
    [string]$NotesFile,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$gradleFile = Join-Path $projectRoot 'app\build.gradle.kts'
$versionMatch = [regex]::Match((Get-Content -LiteralPath $gradleFile -Raw), 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?)"')
if (-not $versionMatch.Success) { throw 'Could not read versionName from app/build.gradle.kts.' }
$version = $versionMatch.Groups[1].Value
$tag = "v$version"
$variant = $Mode.ToLowerInvariant()
$apkSource = Join-Path $projectRoot "app\build\outputs\apk\$variant\app-$variant.apk"
$artifactName = "RainAlarm-$tag-$variant.apk"
$outputDirectory = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    Join-Path $projectRoot 'dist'
} elseif ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputDirectory))
}
$artifact = Join-Path $outputDirectory $artifactName
$gradle = Join-Path $projectRoot 'gradlew.bat'

if ($Publish -and $Mode -ne 'Release') { throw 'Publication requires -Mode Release and a signed APK.' }
if ($Mode -eq 'Release') {
    $signer = @{
        RAIN_ALARM_SIGNING_STORE_FILE = $env:RAIN_ALARM_SIGNING_STORE_FILE
        RAIN_ALARM_SIGNING_STORE_PASSWORD = $env:RAIN_ALARM_SIGNING_STORE_PASSWORD
        RAIN_ALARM_SIGNING_KEY_ALIAS = $env:RAIN_ALARM_SIGNING_KEY_ALIAS
        RAIN_ALARM_SIGNING_KEY_PASSWORD = $env:RAIN_ALARM_SIGNING_KEY_PASSWORD
    }
    $missing = @($signer.Keys | Where-Object { [string]::IsNullOrWhiteSpace($signer[$_]) })
    if ($missing.Count -gt 0) {
        throw "Release signing is not configured. Set these environment variables: $($missing -join ', '). No key material belongs in the repository."
    }
    if (-not (Test-Path -LiteralPath $env:RAIN_ALARM_SIGNING_STORE_FILE -PathType Leaf)) {
        throw 'RAIN_ALARM_SIGNING_STORE_FILE does not point to an existing keystore file.'
    }
}

if ($PSCmdlet.ShouldProcess($projectRoot, "validate and assemble $Mode $tag")) {
    Push-Location $projectRoot
    try {
        & $gradle ':app:directDebugUnitTest' ':app:lintDebug' ":app:assemble$Mode" '--no-daemon'
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
    } finally { Pop-Location }
} else {
    if ($Publish) {
        Write-Host "WhatIf: after a signed build, verify clean Git/GitHub state, create annotated tag $tag, push branch and tag to $Remote without force, and publish the signed APK as a GitHub Release."
    }
    return
}

if (-not (Test-Path -LiteralPath $apkSource -PathType Leaf)) { throw "Expected APK was not produced: $apkSource" }
$apksigner = if ($env:ANDROID_HOME) {
    Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools') -Filter apksigner.bat -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object -Property FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
} else { $null }
if (-not $apksigner) { throw 'Set ANDROID_HOME to an SDK with apksigner.bat before distributing an APK.' }
& $apksigner verify '--verbose' $apkSource
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
if (Test-Path -LiteralPath $artifact) { throw "Output already exists; refusing to overwrite: $artifact" }
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
Copy-Item -LiteralPath $apkSource -Destination $artifact
$digest = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
$size = (Get-Item -LiteralPath $artifact).Length
Write-Host "Version: $version"
Write-Host "APK: $artifact"
Write-Host "Size: $size bytes"
Write-Host "SHA-256: $digest"

if (-not $Publish) {
    Write-Host 'Local build only. No git tag, push, or GitHub release was created.'
    return
}

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'git is required for -Publish.' }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'GitHub CLI (gh) is required for -Publish.' }
Push-Location $projectRoot
try {
    $gitRoot = (& git rev-parse --show-toplevel 2>$null)
    if ($LASTEXITCODE -ne 0 -or -not $gitRoot -or
        ([IO.Path]::GetFullPath($gitRoot).TrimEnd('\') -ine [IO.Path]::GetFullPath($projectRoot).TrimEnd('\'))) {
        throw 'Publication requires this project to be the root of a Git repository.'
    }
    if (& git status --porcelain) { throw 'Commit or remove working-tree changes before publication.' }
    $remoteUrl = (& git remote get-url $Remote 2>$null)
    if ($LASTEXITCODE -ne 0 -or -not $remoteUrl) { throw "Git remote '$Remote' is not configured." }
    if ($remoteUrl -notmatch 'github\.com[:/]') { throw 'The selected remote is not a GitHub repository.' }
    & gh auth status '--active' '--hostname' 'github.com' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'GitHub CLI is not authenticated for github.com.' }
    & git rev-parse '--verify' "refs/tags/$tag" *> $null
    if ($LASTEXITCODE -eq 0) { throw "Local tag $tag already exists." }
    $remoteTags = & git ls-remote '--tags' $Remote "refs/tags/$tag"
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify remote tag availability.' }
    if ($remoteTags) { throw "Remote tag $tag already exists." }
    & gh release view $tag '--json' 'tagName' *> $null
    if ($LASTEXITCODE -eq 0) { throw "GitHub release $tag already exists." }
    $branch = (& git branch --show-current).Trim()
    if (-not $branch) { throw 'Publication requires a named branch, not detached HEAD.' }
    if ($NotesFile) {
        $resolvedNotes = (Resolve-Path -LiteralPath $NotesFile -ErrorAction Stop).Path
    }
    if ($PSCmdlet.ShouldProcess("$Remote/$branch, $tag", 'push branch and annotated tag; publish GitHub release')) {
        & git tag '-a' $tag '-m' "Rain Alarm $version"
        if ($LASTEXITCODE -ne 0) { throw 'Could not create annotated tag.' }
        & git push $Remote "HEAD:refs/heads/$branch"
        if ($LASTEXITCODE -ne 0) { throw 'Branch push failed; the local tag remains for inspection.' }
        & git push $Remote "refs/tags/$tag"
        if ($LASTEXITCODE -ne 0) { throw 'Tag push failed; the local tag remains for inspection.' }
        $releaseArgs = @('release', 'create', $tag, $artifact, '--verify-tag', '--title', "Rain Alarm $version")
        if ($resolvedNotes) { $releaseArgs += @('--notes-file', $resolvedNotes) }
        else { $releaseArgs += '--generate-notes' }
        & gh @releaseArgs
        if ($LASTEXITCODE -ne 0) { throw 'GitHub release creation failed; inspect the pushed tag before retrying.' }
    }
} finally { Pop-Location }
