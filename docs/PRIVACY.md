# Privacy

Rain Alarm is designed without user accounts, advertising, analytics, billing or embedded provider credentials. It does not operate an application server: the app talks directly to the weather, map and search services needed for the feature the user requests.

This disclosure describes version 0.4.1. Provider services necessarily receive ordinary network metadata such as the connecting IP address; consult each provider's current terms and privacy policy for how it handles that traffic.

## Android permissions

### Internet

`INTERNET` is used to load maps, radar, submitted place searches, model weather and optional satellite imagery. Rain Alarm does not upload an address book, advertising identifier, account identifier or analytics events.

### Foreground location

Foreground location supports virtual **Current location**, Travel, saving a current fix and evaluating the active live place while the app is open. Google Play services fused high-accuracy location is preferred, with Android GPS as the precise fallback. If neither precise path succeeds, ordinary Current may use an explicitly provisional coarse network fix; Travel never presents that as navigation-quality location.

Rain Alarm does not call a web location endpoint to obtain the device position. The device and Google Play services may use location sources configured under Android's own settings and privacy controls.

A fresh install requests foreground location once. Denial leaves Current unavailable and allows a saved place to be selected instead. Approximate permission remains useful for provisional Current, but Travel requires Android's Precise location choice.

Ordinary Current targets a five-second update interval. Travel requests precise fixes on a 200 ms raster, with actual delivery governed by Android and the GNSS hardware. Travel is opt-in, is not persisted and stops when Radar leaves composition, Current is deselected or the app backgrounds. It keeps the screen on only during that same visible active interval.

Rain Alarm requests no background-location permission, starts no location foreground service, holds no wake lock and shows no ongoing location notification. Live fixes stay in process memory unless the user explicitly saves the current location as an ordinary saved place.

### Notifications

On Android 13 and later, Rain Alarm requests notification permission once after the location prompt if needed. Denial leaves Rain Notification off with a permission-needed message, and Settings can retry. It does not send a test notification merely to obtain permission.

Optional alert work uses Android WorkManager. It requests no exact-alarm permission and no foreground service. See [Notification behavior](#notification-behavior) for the data it uses.

## Network requests

Requests are feature-driven. Leaving Wind, Lightning and Clouds off makes no ancillary-layer request. Search runs only after explicit submit, and disabled services or layers remain idle.

### Radar and precipitation

- **MeteoGroup/DTN regional radar** loads HTTPS manifests and radar imagery from the fixed `cdn.meteogroup.de` host. Manifest paths, media types, sizes and raster dimensions are validated. No credential is included.
- **MeteoGroup area profile** sends the selected latitude and longitude to an HTTPS area lookup, then sends only the returned area ID to the chart service. The legacy chart host's HTTPS certificate fails hostname verification, so Android allows plain HTTP only for `android.weatherpro.weatherservice.meteogroup.de`; TLS verification remains enabled everywhere else. Neither request includes a device or account identifier. Because this is a legacy unencrypted endpoint, avoid using it for a sensitive location and do not assume it will remain available.
- **EUMETNET OPERA** reads required metadata and byte ranges from the public HTTPS 24-hour Open Radar cache at `s3.waw3-1.cloudferro.com`. It does not list the bucket and uses no access key.
- **RainViewer** loads the public HTTPS weather-map manifest, radar images and one coverage mask per raster plan. No account or key is used.

The canonical endpoints, validation rules, caches, attribution and limitations are documented in [Data sources and constraints](DATA_SOURCES.md).

### Model weather and wind

Open-Meteo receives one selected coordinate for current model temperature, pressure, humidity, UV, daylight, wind, sunrise and sunset. When Wind is enabled, a separate bounded request sends 25 coordinates that span the settled visible Radar viewport, including its off-screen sampling margin, for the active session time window.

The point result is cached for 15 minutes. Wind grids are cached by quantized viewport and session window for 15 minutes, with at most eight process-scoped entries. A wind request happens after meaningful camera settlement or explicit Refresh, not for every gesture or animation frame. No current-location coordinate is persisted by these caches.

### Satellite layers

Enabling Lightning or Clouds accesses EUMETSAT's public HTTPS WMS at `view.eumetsat.int`. Rain Alarm first reads bounded capability metadata and probes the selected product, then downloads the finite set of regional PNG images needed for the observed Radar window.

The request contains the chosen product, advertised observation time, image dimensions and a bounded regional map extent. The selected place chooses a code-owned British Isles, Europe, North America East/West or local fallback region with roughly 200 km of margin; panning does not turn this into a new request. Leaving both satellite controls off sends no EUMETSAT request.

Verified satellite PNGs are stored in an app-owned atomic least-recently-used cache capped at 128 MiB. MapLibre separately keeps up to 64 MiB of basemap cache, for an intended map/satellite disk budget of about 192 MiB. Files are keyed by product, time, bounds and region rather than a user identity.

### Maps

MapLibre loads OpenFreeMap/OpenMapTiles styles and tiles backed by OpenStreetMap data. Its compact native attribution control remains available on Radar. Changing camera position can request the normal basemap resources needed for that view; radar and satellite regions follow the bounded behavior described in the [user guide](USER_GUIDE.md#radar).

### Place search

Search occurs only when submitted. Ordinary non-postcode text is sent in parallel to:

- Open-Meteo's GeoNames-backed place endpoint for settlements; and
- `https://photon.komoot.io/api/` for OpenStreetMap named places and points of interest.

If neither source has a strong name match, one sequential bounded query may be sent to the English Wikipedia/Wikimedia API for geocoded notable pages. This third request is not made for every search. Failure of any source does not discard useful results from another.

Complete UK postcode-shaped queries are sent only to `https://api.postcodes.io/postcodes/{postcode}`. They are not also sent to the ordinary text services. postcodes.io requires no authentication.

Requests use bounded results and timeouts. Photon has a 12-query in-process LRU/single-flight cache; Wikipedia/Wikimedia has an eight-query cache. These caches disappear with the process and contain submitted search terms and returned results, not an account identity. Photon and Wikimedia receive an identifying Rain Alarm User-Agent but no API key.

### Reverse geocoding and live movement

A live Current selection uses the accepted on-device fused/GPS fix stream. The platform's locality name is used where available, with a one-decimal latitude/longitude fallback; Rain Alarm does not continuously submit every Travel fix to a reverse-geocoding service.

Travel can update marker and camera for each accepted fix. Weather analysis and reverse-geocoding work move only at a bounded anchor: immediately for the first fix, then after at least 1 km, or after two minutes with at least 250 m, plus a correction when crossing a regional boundary. This avoids network work at the location-update rate.

## Local storage

The following can be stored locally on the device:

- saved places and their manual order;
- the active selected place and saved startup place;
- preferred radar provider and provider-specific options;
- app, map, Compass and Graph appearance choices, including day/night profiles;
- playback speed, visible Now metrics, layer toggles, wind-arrow size and coverage-mask darkness;
- notification choice and per-place alert suppression state;
- first-launch and tour completion state; and
- bounded provider, search, map and satellite caches described above.

Current location is a virtual selection. Live fixes and coordinates stay in memory unless **Save current location** is used. A legacy persisted current-location record is migrated to the virtual live selection without retaining its old coordinates; ordinary saved places remain intact. Existing selections and explicit settings survive upgrades.

Rain Alarm has no account sync or cloud backup service of its own. Android or the device manufacturer may apply its normal app-backup policy outside Rain Alarm's control.

## Notification behavior

Rain Notification follows the active selected saved or virtual live place. WorkManager checks approximately every 15 minutes, Android's periodic minimum; execution is inexact and may be deferred by Doze.

An alert is sent only when available data says the place is dry now and rain is expected within 60 minutes. It contains the place, estimated arrival and local start time. Duration and peak are added only when a complete episode ends within known coverage. After an alert, that place is suppressed until the recorded wet-window end plus ten minutes.

Saved-place checks need no location permission. A virtual Current background check is skipped unless a lawful fresh foreground fix is already available. No background location is acquired for the alert.

## Provider and licensing notes

Map, radar, model, satellite and search providers operate independently and have their own current terms. Key notices include:

- Open-Meteo public API use is subject to its noncommercial and attribution terms.
- EUMETNET OPERA and EUMETSAT products used here are attributed under CC BY 4.0.
- Photon uses © OpenStreetMap contributors data; Wikimedia supplies geocoded English Wikipedia pages.
- postcodes.io includes OS OpenData, Ordnance Survey/Crown, Royal Mail, National Statistics and separate Northern Ireland notices described in its published licences.
- RainViewer's public API is best-effort and intended for personal, educational or community projects; terms should be rechecked before distribution or material traffic.

Full links and notices are kept in [Data sources and constraints](DATA_SOURCES.md). Attribution is also available in Settings under **About the data** and, for the map, through MapLibre's native control.

## What Rain Alarm does not collect

Rain Alarm does not provide or collect:

- user accounts or profiles;
- advertising or cross-app tracking identifiers;
- analytics, telemetry or crash-report uploads;
- billing or payment data;
- contact lists, photos or other personal files;
- background location; or
- embedded weather-provider credentials.

Operational exceptions and failures are shown locally in plain loading/unavailable states. Internal diagnostics are not an analytics channel.
