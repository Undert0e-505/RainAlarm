# Rain Alarm project workflow

## Engineering review loop

- Inspect the relevant code and project documentation, turn the request into a focused specification, and delegate repository implementation to an implementer. Preserve working behavior and unrelated worktree edits.
- Have the implementer validate the change with relevant tests/build checks. For UI or device-dependent behavior, provide a same-signed local preview for physical-device acceptance, then iterate from the user's feedback before treating it as release-ready.
- Do not commit, push, tag, or publish a release without explicit authorization for the current task. Keep the user-facing handoff concise: outcome, evidence, preview/checksum when applicable, and the exact manual check.

## Phone-installable previews

- Build every local APK intended to install over the published app with `scripts/build-signed-local.ps1` and the **existing** private release signer described in [docs/SIGNING.md](docs/SIGNING.md). A debug-signed APK is useful for internal compile/test work, but must not be delivered as an update to a release-signed installation. Never create or substitute a new signer if the existing signer is unavailable; report the blocker.
- Preserve `applicationId` and a nondecreasing `versionCode` for in-place installation. Do not bump the version for each preview; advance it deliberately for an official release. Verify the preview APK's version, v2 signature and pinned certificate fingerprint, SHA-256, and the required unit tests/lint before handoff.
- Keep generated previews in ignored `dist/`; copy a uniquely named preview APK to `G:\My Drive` without overwriting another file, then verify size and SHA-256 match. Never copy the keystore, passphrase, or encrypted signing material into the repository, shared drive, logs, or response.
- Previews are local-only: do not commit, push, tag, or publish a GitHub release unless the user explicitly authorizes that action. Request physical-device visual acceptance before preparing an official release.

See [docs/SIGNING.md](docs/SIGNING.md) for the signer identity, build helper, and backup procedure. Preserve unrelated worktree edits while implementing a preview.
