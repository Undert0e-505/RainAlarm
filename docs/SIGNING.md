# Rain Alarm release signing

The 0.1.0 release is signed with a self-managed Android key. This document contains no password or private key. The owner confirmed off-machine backup of both the keystore and passphrase before the initial public GitHub release. Future releases must preserve that same key identity.

## Local identity

- Private keystore: `D:\dev\RainAlarmSigningPrivate\rain-alarm-release.p12` (PKCS#12, alias `rain-alarm-release`).
- Local passphrase recovery: `D:\dev\RainAlarmSigningPrivate\signing-password.dpapi`, encrypted for the current Windows user profile. This file is not itself a portable backup.
- Key/certificate: RSA 4096, SHA256withRSA, valid 17 September 2026–17 September 2056.
- Public signing certificate SHA-256: `183BF315F2DCEC6A7706BBFD27E7496CCE79EBD820945C9B9A8866515A26B824`.
- Both private files live **outside** the repository and shared drive. The private directory's ACL grants only the current Windows user and SYSTEM.

The certificate fingerprint is public and can be compared with `apksigner verify --print-certs` on a downloaded release APK. A future update must use the same keystore identity to install over an existing direct-download installation.

## Build locally

From the repository root in PowerShell:

```powershell
.\scripts\build-signed-local.ps1 -WhatIf
.\scripts\build-signed-local.ps1
.\scripts\build-signed-local.ps1 -VerifyOnly
```

The helper decrypts the local passphrase only in the current process, supplies Gradle's existing `RAIN_ALARM_SIGNING_*` environment variables, runs `directDebugUnitTest`, `lintDebug`, and signed `assembleRelease`, and clears/restores the process variables afterward. It checks the APK signature and compares its certificate SHA-256 with the keystore. It never sets `-Publish`. Each same-version rebuild uses a new ignored `dist/local-rebuild-*` directory instead of overwriting an existing release APK. The original explicit-environment path, `scripts/build-release.ps1 -Mode Release`, remains available for private CI secrets.

`-VerifyOnly` checks the existing canonical `dist/RainAlarm-v<version>-release.apk` and the pinned certificate without building or publishing. It does not reveal the passphrase.

`scripts/initialize-local-signing.ps1` created this host's key using JDK 21 `keytool` with `-storepass:env` and `-keypass:env`; no passphrase was placed in command arguments. It refuses to initialize over an existing private directory. **Do not run initialization again to replace a release key.**

## Backup before publication

1. Copy `rain-alarm-release.p12` to encrypted off-machine storage under your control. Do **not** put it in the repository, GitHub, or the Jimothy shared drive.
2. On the original Windows desktop, run `.\scripts\copy-signing-password.ps1` for a no-secret preflight. Then run `.\scripts\copy-signing-password.ps1 -ToClipboard` and paste directly into a trusted password manager. The helper does not print the passphrase and clears an unchanged current clipboard after 90 seconds, including on script interruption when possible. Treat the clipboard as sensitive while populated; Windows clipboard history or cross-device sync may retain earlier copies, so disable/clear those features before using it.
3. Confirm you can retrieve **both** the keystore and the passphrase from those independent off-machine backups. Record the alias and public certificate fingerprint above with the backup. Only then consider tagging or publishing.

DPAPI decryption is tied to this Windows user/profile and may fail after profile loss, migration or OS reinstallation. Backing up only `signing-password.dpapi` is insufficient. Losing either the keystore or its passphrase prevents future APKs from updating installations signed with this identity; a new key would require users to uninstall the old app first.
