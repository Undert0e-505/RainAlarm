[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$privateDirectory = 'D:\dev\RainAlarmSigningPrivate'
$keystore = Join-Path $privateDirectory 'rain-alarm-release.p12'
$secretFile = Join-Path $privateDirectory 'signing-password.dpapi'
$keytool = 'C:\Program Files\Java\jdk-21\bin\keytool.exe'
$alias = 'rain-alarm-release'

if (-not [Environment]::OSVersion.Platform.ToString().StartsWith('Win')) {
    throw 'Local signing setup requires Windows DPAPI.'
}
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) {
    throw 'JDK 21 keytool was not found at the expected local path.'
}
if ([IO.Path]::GetFullPath($privateDirectory).StartsWith($projectRoot.TrimEnd('\') + '\',
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Private signing directory must stay outside the repository.'
}
if (Test-Path -LiteralPath $privateDirectory) {
    throw 'Private signing directory already exists. Refusing to overwrite or mix signing material.'
}
$signingDoc = Join-Path $projectRoot 'docs\SIGNING.md'
if (Test-Path -LiteralPath $signingDoc -PathType Leaf) {
    $documented = Get-Content -LiteralPath $signingDoc -Raw
    if ($documented -match 'Public signing certificate SHA-256:\s*`[0-9A-Fa-f]{64}`') {
        throw 'A release identity is already documented. Restore its private backup; do not generate a replacement key.'
    }
}
if (-not $PSCmdlet.ShouldProcess($privateDirectory, 'create private release keystore and DPAPI-protected password')) {
    return
}

New-Item -ItemType Directory -Path $privateDirectory | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$acl = Get-Acl -LiteralPath $privateDirectory
$acl.SetAccessRuleProtection($true, $false)
$inheritance = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
    [Security.AccessControl.InheritanceFlags]::ObjectInherit
$propagation = [Security.AccessControl.PropagationFlags]::None
$allow = [Security.AccessControl.AccessControlType]::Allow
$full = [Security.AccessControl.FileSystemRights]::FullControl
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
    $identity.User, $full, $inheritance, $propagation, $allow))
$system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
    $system, $full, $inheritance, $propagation, $allow))
Set-Acl -LiteralPath $privateDirectory -AclObject $acl

$random = New-Object byte[] 48
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($random) } finally { $rng.Dispose() }
$password = [Convert]::ToBase64String($random).TrimEnd('=').Replace('+', '-').Replace('/', '_')
[Array]::Clear($random, 0, $random.Length)
$securePassword = ConvertTo-SecureString -String $password -AsPlainText -Force
$ciphertext = ConvertFrom-SecureString -SecureString $securePassword
[IO.File]::WriteAllText($secretFile, $ciphertext,
    [Text.UTF8Encoding]::new($false))

$env:RAIN_ALARM_KEYTOOL_PASSWORD = $password
try {
    $generate = @('-genkeypair', '-noprompt', '-alias', $alias, '-keyalg', 'RSA',
        '-keysize', '4096', '-sigalg', 'SHA256withRSA', '-validity', '10958',
        '-dname', 'CN=Rain Alarm Release, O=Rain Alarm', '-storetype', 'PKCS12',
        '-keystore', $keystore, '-storepass:env', 'RAIN_ALARM_KEYTOOL_PASSWORD',
        '-keypass:env', 'RAIN_ALARM_KEYTOOL_PASSWORD')
    & $keytool @generate
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $keystore -PathType Leaf)) {
        throw 'JDK keytool did not create the release keystore.'
    }
    $verify = @('-list', '-keystore', $keystore, '-storetype', 'PKCS12',
        '-alias', $alias, '-storepass:env', 'RAIN_ALARM_KEYTOOL_PASSWORD')
    & $keytool @verify | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The generated keystore could not be reopened.' }
} finally {
    Remove-Item Env:RAIN_ALARM_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
    $securePassword.Dispose()
}

Write-Host "Private keystore: $keystore"
Write-Host "DPAPI-protected password: $secretFile"
Write-Host 'No password was printed. Back up BOTH the keystore and passphrase off-machine before publishing.'
