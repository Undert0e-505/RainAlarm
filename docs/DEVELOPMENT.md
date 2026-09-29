# Development and build

Rain Alarm 0.4.1 has version code 19, supports Android 8.0 and later (minimum SDK 26), and compiles and targets SDK 37. The project uses the checked-in Gradle wrapper and can be opened in Android Studio or built from PowerShell.

## Prerequisites

- JDK 21
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0
- Android SDK command-line tools
- Network access for the first dependency resolution

Point `ANDROID_HOME` at the installed SDK. Do not add local SDK paths, credentials or signing material to the repository.

## Debug build and tests

From the repository root on Windows:

```powershell
$env:ANDROID_HOME='D:\path\to\android-sdk'
.\gradlew.bat `
  :app:directDebugUnitTest `
  :app:lintDebug `
  :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Standard CI can run the normal Android unit-test task:

```powershell
.\gradlew.bat `
  :app:testDebugUnitTest `
  :app:lintDebug `
  :app:assembleDebug
```

On this project's current Windows development host, Java's AF_UNIX selector cannot start the Gradle test worker. The checked-in `:app:directDebugUnitTest` task runs the same pure JVM tests through JUnitCore. If that host still needs the selector workaround, set this non-secret process option before Gradle:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=Z:\rainalarm-no-socket-dir'
```

This is a host constraint, not an Android runtime requirement. A feature is not validated merely because compilation succeeded: run the focused tests, full unit suite and lint appropriate to the change, then exercise device-dependent behavior on an emulator and, where relevant, a physical phone.

## Build helper

The Windows release helper runs tests, lint, assembly and APK signature verification, copies a versioned artifact to ignored `dist/`, and prints its byte size and SHA-256:

```powershell
.\scripts\build-release.ps1 -Mode Debug
```

Debug mode is useful for internal compilation and emulator work. A debug-signed APK cannot update an installed public release.

## Locally signed builds and previews

Android release APKs require a private signing key. This development host keeps one long-lived release key outside the repository and protects its passphrase with the current Windows user's DPAPI profile. Repeat a local signed build with:

```powershell
.\scripts\build-signed-local.ps1 -WhatIf
.\scripts\build-signed-local.ps1
.\scripts\build-signed-local.ps1 -VerifyOnly
```

The helper decrypts the passphrase only in the current process, supplies the existing `RAIN_ALARM_SIGNING_*` variables, runs `directDebugUnitTest`, `lintDebug` and a minified signed `assembleRelease`, and restores the process environment afterwards. It verifies the APK signature and compares its certificate with the established key. It never publishes.

Repeated same-version builds use a fresh ignored `dist/local-rebuild-*` directory instead of overwriting an earlier APK. `-VerifyOnly` checks the canonical `dist/RainAlarm-v<version>-release.apk` and pinned certificate without building or printing the passphrase.

Every phone-installable preview for an existing installation must use this signed-local helper. Preserve the application ID and a nondecreasing version code. Do not increment the public version for every preview; advance it deliberately for a release. For each handoff:

1. Verify version name and code.
2. Verify APK Signature Scheme v2 and the certificate fingerprint.
3. Record size and SHA-256.
4. Copy a uniquely named APK to the shared drive and verify the copy's size and hash.
5. Keep the preview local; do not tag or publish it.

The original private-CI route remains available through `scripts/build-release.ps1 -Mode Release` with all four `RAIN_ALARM_SIGNING_*` values supplied by a private shell or secret store. Never put a password in command arguments, shell history, a commit, log, issue, screenshot or shared drive. An unsigned `assembleRelease` result is not distributable.

See [Release signing](SIGNING.md) for the private host paths, public certificate identity, backup procedure and recovery consequences. Do not initialize or substitute another signing key if the established key is unavailable.

## Signing-key safety

The project owner confirmed an off-machine backup of both keystore and passphrase before v0.1.0. DPAPI-protected local data is not a portable backup. Losing either the keystore or its passphrase prevents future APKs from updating existing direct-download installations; a replacement key would require users to uninstall the old app.

The signing guide documents a no-secret preflight and a clipboard-based passphrase copy for a trusted password manager. Disable or clear Windows clipboard history and cross-device clipboard before using it. The repository, GitHub and the shared drive must never contain the keystore, passphrase or DPAPI blob.

Version name and code live in `app/build.gradle.kts` and must be changed deliberately before publication.

## Optional publication

The public repository is [Undert0e-505/RainAlarm](https://github.com/Undert0e-505/RainAlarm). Builds are not committed, tagged or published by default.

A maintainer with a clean tree, the established private signing key, a configured GitHub remote and authenticated GitHub CLI can preview or explicitly run signed publication:

```powershell
.\scripts\build-release.ps1 -Mode Release -Publish -Remote origin -NotesFile .\docs\releases\v0.4.1.md -WhatIf
.\scripts\build-release.ps1 -Mode Release -Publish -Remote origin -NotesFile .\docs\releases\v0.4.1.md
```

`-WhatIf` previews the operation without building or publishing; release signer variables are still checked. Actual publication verifies the Git root, clean working tree, remote, GitHub authentication and local/remote tag or release collisions. It then creates an annotated `v<version>` tag, pushes the branch and tag without force, and creates a GitHub Release with the signed APK. If no notes file is supplied, GitHub generates notes.

Use publication only after reviewing code, tests, device checks, permission disclosures and the exact signed artifact. A partial failure can leave a local or remote tag. Inspect existing refs and release state before retrying; never force-overwrite them merely to make a retry pass.

## Testing expectations

Small, focused changes are preferred. Preserve existing behavior unless the task requires a change, and add pure unit tests for decision, policy or maths changes. Run focused tests first, then the complete normal and direct unit suites where supported, relevant lint, and an assembly appropriate to the artifact.

Device or emulator checks should match the risk. UI, permission and GLES changes should record the Android/API level and test:

- MeteoGroup, OPERA and RainViewer paths where provider behavior is involved;
- rapid timeline scrubbing and playback handoff;
- Light, Dark and Slate app/map combinations;
- short-screen and wider layouts;
- location denial, approximate/precise recovery and Travel;
- saved-place and current-place notifications; and
- foreground/background transitions.

Forecast and alert behavior depends on live third-party data. Automated tests cannot guarantee future service availability, so tests should distinguish deterministic policy from external availability and use controlled fixtures where practical.

Never stage generated build output, downloaded provider data, signing material or `.emulator-data/` evidence. Run `git diff --check` and inspect the exact staged file list before committing. Commit, push, tag and publish only with explicit authorization for that task.

## Project layout

- `app/src/main/java/com/rainalarm/app/data` contains provider adapters, repositories, preferences and cache handling.
- `app/src/main/java/com/rainalarm/app/domain` contains precipitation, projection and decision logic.
- `app/src/main/java/com/rainalarm/app/ui` contains Compose screens, navigation, MapLibre and GLES integration.
- `app/src/main/java/com/rainalarm/app/alerts` contains optional scheduling and alert decisions.
- `app/src/test` contains pure JVM tests.
- `docs/` contains product, provider, privacy, localization, signing and release documentation.
- `scripts/` contains build, signing and publication helpers.
- `artwork/` contains owner-supplied source artwork intended for the repository.

## Artwork

The launcher artwork is the project owner's supplied Android icon pack. It is included as density-specific legacy and adaptive PNG resources under the project's MIT license. The Play Store image is `artwork/play_store_icon_512.png`.

Notifications deliberately use a separate white-on-transparent droplet/bell small icon rather than the launcher's coloured background. Android notification artwork must remain suitable for the platform's monochrome small-icon treatment.

## Clean-room boundary

Rain Alarm is an independent clean-room implementation. The retired RainToday application, its source, native libraries, artwork, credentials and packaged assets must not be copied or shipped. Research material under `Research/RainToday` is development-only and is never packaged into the app.

Contributions must not:

- copy proprietary RainToday code, binaries, native interfaces, assets or credentials;
- introduce service secrets or private keys;
- add undocumented provider endpoints without a documented legal and operational basis; or
- weaken attribution, permission or privacy disclosures to make a feature appear simpler.

Provider adapters are kept separate from map and analysis logic so an authorized self-hosted or regional source can replace a legacy dependency without changing the rain-status contract. Changes to providers must update [Data sources and constraints](DATA_SOURCES.md) and [Privacy](PRIVACY.md) where relevant.

## Related documentation

- [Data sources and constraints](DATA_SOURCES.md)
- [User guide](USER_GUIDE.md)
- [Privacy](PRIVACY.md)
- [Localization](LOCALIZATION.md)
- [Feature tour](FEATURE_TOUR.md)
- [Release signing](SIGNING.md)
- [Deferred roadmap](NEXT_PHASE.md)
- [MIT License](../LICENSE)
