[CmdletBinding(SupportsShouldProcess = $true)]
param([switch]$ToClipboard)

$ErrorActionPreference = 'Stop'
$privateDirectory = 'D:\dev\RainAlarmSigningPrivate'
$keystore = Join-Path $privateDirectory 'rain-alarm-release.p12'
$secretFile = Join-Path $privateDirectory 'signing-password.dpapi'
if (-not (Test-Path -LiteralPath $keystore -PathType Leaf) -or
    -not (Test-Path -LiteralPath $secretFile -PathType Leaf)) {
    throw 'Local signing keystore or DPAPI secret is missing.'
}
if (-not $ToClipboard) {
    Write-Host 'Preflight passed. Run with -ToClipboard in your private desktop session to back up the passphrase.'
    return
}
if (-not $PSCmdlet.ShouldProcess('the local clipboard', 'copy the release passphrase for password-manager backup')) {
    return
}

$secret = ConvertTo-SecureString -String (Get-Content -LiteralPath $secretFile -Raw)
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $secret.Dispose()
}
try {
    Set-Clipboard -Value $password
    Write-Host 'Passphrase copied for 90 seconds. Paste it directly into your password manager; do not paste into a terminal or chat.'
    Start-Sleep -Seconds 90
} finally {
    try {
        if ((Get-Clipboard -Raw).TrimEnd("`r", "`n") -ceq $password) {
            Set-Clipboard -Value ''
            Write-Host 'Clipboard cleared.'
        }
    } catch {
        Write-Warning 'Could not confirm clipboard clearing; clear clipboard and clipboard history manually.'
    }
    $password = $null
}
