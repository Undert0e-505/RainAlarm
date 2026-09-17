# Rain Alarm 0.1.0

The first public, signed Rain Alarm release for Android 8.0 and later. [Download the APK from the GitHub Release](https://github.com/Undert0e-505/RainAlarm/releases/tag/v0.1.0); the repository source archive does not include the APK or private signing material.

## Highlights

- **Now:** Selected-place rain status, arrival estimate when available, a north-up rain-direction compass, and a next-hour intensity graph limited to the provider's actual forecast horizon. Tap the graph plot to open Radar paused at that time. Optional model-derived temperature, pressure, humidity, UV, wind and local sunrise/sunset readouts.
- **Radar:** North-up map with continuous time scrubbing, velocity-aware frame interpolation, looping playback, retained camera framing and dark/light map themes. MeteoGroup/DTN regional imagery and provider forecast are the default where covered; RainViewer observations with a confidence-gated, locally estimated future path are available as an open option or fallback.
- **Places and alerts:** Search and save multiple places, choose a startup place or virtual live Current location, and receive a rain-approach notification for the active selected place when it is dry now and rain is predicted within available coverage of the next hour. Notifications require permission and WorkManager timing is inexact.
- **Optional layers:** Open-Meteo model wind arrows over the visible map; EUMETSAT satellite flash **areas** and nighttime fog/low-cloud imagery. These are not individual ground lightning strikes or confirmed surface fog.

## Install and upgrade

Download `RainAlarm-v0.1.0-release.apk` below and open it on an Android 8.0+ device. Android may require you to allow installation from the app used to open the APK. This release is signed with the project's permanent release certificate, enabling future release-signed updates when their version code increases.

**A previous debug-signed Rain Alarm APK cannot be upgraded in place to this release-signed APK.** Android rejects the signature change. Uninstall the debug build before installing this release; uninstalling can remove its locally saved places and settings, and the app has no built-in export. Future APKs signed with this same release key can update this release normally.

APK SHA-256: `C2C0C404908D88D4544AC4CF360E05308097E92BA6936C0D5186A20F75C58389`

Signing certificate SHA-256: `183BF315F2DCEC6A7706BBFD27E7496CCE79EBD820945C9B9A8866515A26B824`

## Sources, privacy and limitations

Radar uses MeteoGroup/DTN regional data or RainViewer. Regional Now/alerts can use a separate location-selected **area** rain profile, not exact pin-pixel measurements. Open-radar future positions are estimates and can be unavailable when confidence is low. Radar pixels have fixed physical resolution; zooming does not reveal new rain detail. All timing, notifications and optional layers depend on service freshness, coverage and network availability. Do not use this app as the only source for weather-safety decisions.

Open-Meteo supplies weather-model facts and wind, not live station observations. EUMETSAT supplies satellite flash-area and fog/low-cloud imagery. Maps use OpenFreeMap/OpenStreetMap contributors. The regional area-chart service currently uses a narrowly permitted HTTP host because its HTTPS certificate does not verify; the coordinate lookup uses HTTPS. Rain Alarm has no account, ads, analytics or bundled provider credentials. Current location needs foreground permission and a fresh fix; background live-location alerts may skip when no lawful fresh fix exists. See the [README](README.md) and [data-source notes](docs/DATA_SOURCES.md) for details and attribution.
