[CmdletBinding(SupportsShouldProcess = $true)]
param([switch]$VerifyOnly)

$ErrorActionPreference = 'Stop'
$privateDirectory = 'D:\dev\RainAlarmSigningPrivate'
$keystore = Join-Path $privateDirectory 'rain-alarm-release.p12'
$secretFile = Join-Path $privateDirectory 'signing-password.dpapi'
$alias = 'rain-alarm-release'
$keytool = 'C:\Program Files\Java\jdk-21\bin\keytool.exe'
$expectedCertificateSha256 = '183bf315f2dcec6a7706bbfd27e7496cce79ebd820945c9b9a8866515a26b824'
if ($VerifyOnly -and $WhatIfPreference) { throw 'Use either -VerifyOnly or -WhatIf, not both.' }
if (-not (Test-Path -LiteralPath $keystore -PathType Leaf) -or
    -not (Test-Path -LiteralPath $secretFile -PathType Leaf)) {
    throw 'Local signer is missing. Run scripts/initialize-local-signing.ps1 first.'
}

$names = @('RAIN_ALARM_SIGNING_STORE_FILE', 'RAIN_ALARM_SIGNING_STORE_PASSWORD',
    'RAIN_ALARM_SIGNING_KEY_ALIAS', 'RAIN_ALARM_SIGNING_KEY_PASSWORD',
    'ANDROID_HOME', 'JAVA_TOOL_OPTIONS')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$secret = ConvertTo-SecureString -String (Get-Content -LiteralPath $secretFile -Raw)
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $secret.Dispose()
}

try {
    $env:RAIN_ALARM_SIGNING_STORE_FILE = $keystore
    $env:RAIN_ALARM_SIGNING_STORE_PASSWORD = $password
    $env:RAIN_ALARM_SIGNING_KEY_ALIAS = $alias
    $env:RAIN_ALARM_SIGNING_KEY_PASSWORD = $password
    if (-not $env:ANDROID_HOME -and (Test-Path -LiteralPath 'D:\dev\android-sdk')) {
        $env:ANDROID_HOME = 'D:\dev\android-sdk'
    }
    if (-not $env:JAVA_TOOL_OPTIONS) {
        # Host-specific AF_UNIX workaround; no secret is placed in this logged option.
        $env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=Z:\rainalarm-no-socket-dir'
    }
    if ($WhatIfPreference) {
        & (Join-Path $PSScriptRoot 'build-release.ps1') -Mode Release -WhatIf
    } else {
        $projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
        $gradleFile = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle.kts') -Raw
        $versionMatch = [regex]::Match($gradleFile, 'versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?)"')
        if (-not $versionMatch.Success) { throw 'Could not read the Android version.' }
        $version = $versionMatch.Groups[1].Value
        $artifactName = "RainAlarm-v$version-release.apk"
        $outputDirectory = Join-Path $projectRoot 'dist'
        if (-not $VerifyOnly) {
            if (Test-Path -LiteralPath (Join-Path $outputDirectory $artifactName)) {
                $stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
                $outputDirectory = Join-Path $outputDirectory "local-rebuild-$stamp"
            }
            & (Join-Path $PSScriptRoot 'build-release.ps1') -Mode Release -OutputDirectory $outputDirectory
        }
        $artifact = Join-Path $outputDirectory $artifactName
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
            throw 'The signed release artifact was not created.'
        }
        if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) { throw 'JDK 21 keytool is missing.' }
        $keytoolArgs = @('-list', '-v', '-keystore', $keystore, '-storetype', 'PKCS12',
            '-alias', $alias, '-storepass:env', 'RAIN_ALARM_SIGNING_STORE_PASSWORD')
        $keytoolOutput = & $keytool @keytoolArgs
        if ($LASTEXITCODE -ne 0) { throw 'Could not verify the local signing certificate.' }
        $keyDigestMatch = [regex]::Match(($keytoolOutput -join "`n"), '(?m)^\s*SHA256:\s*([0-9A-Fa-f:]+)')
        if (-not $keyDigestMatch.Success) { throw 'Keytool certificate digest was missing.' }
        $keyDigest = $keyDigestMatch.Groups[1].Value.Replace(':', '').ToLowerInvariant()
        if ($keyDigest -ne $expectedCertificateSha256) {
            throw 'Private keystore does not match the documented release certificate; restore the original backup.'
        }
        $apksigner = Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME 'build-tools') -Filter apksigner.bat -Recurse -File |
            Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
        if (-not $apksigner) { throw 'Android apksigner is missing.' }
        $apkOutput = & $apksigner verify --print-certs $artifact
        if ($LASTEXITCODE -ne 0) { throw 'APK signer certificate inspection failed.' }
        $apkDigestMatch = [regex]::Match(($apkOutput -join "`n"),
            'Signer #1 certificate SHA-256 digest:\s*([0-9A-Fa-f]+)')
        if (-not $apkDigestMatch.Success -or
            $apkDigestMatch.Groups[1].Value.ToLowerInvariant() -ne $keyDigest) {
            throw 'Release APK certificate does not match the private keystore.'
        }
        Write-Host "Certificate SHA-256: $keyDigest"
    }
} finally {
    foreach ($name in $names) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
    $password = $null
}
