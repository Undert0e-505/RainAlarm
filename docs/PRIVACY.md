# Privacy

Rain Alarm is designed without user accounts, advertising, analytics, billing or embedded provider
credentials. Most requests go directly to the weather, map and search service needed for the
feature; the experimental individual-lightning Radar visual uses the read-only relay described
below.

This disclosure describes version 0.9.2. Provider services necessarily receive ordinary network metadata such as the connecting IP address; consult each provider's current terms and privacy policy for how it handles that traffic.

## Android permissions

### Internet

`INTERNET` is used to load maps, radar, submitted place searches, model weather and optional satellite imagery. Rain Alarm does not upload an address book, advertising identifier, account identifier or analytics events.

### Foreground location

Foreground location supports virtual **Current location**, Travel, saving a current fix and evaluating the active live place while the app is open. Google Play services fused high-accuracy location is preferred, with Android GPS as the precise fallback. If neither precise path succeeds, ordinary Current may use an explicitly provisional coarse network fix; Travel never presents that as navigation-quality location.

Rain Alarm does not call a web location endpoint to obtain the device position. The device and Google Play services may use location sources configured under Android's own settings and privacy controls.

A fresh install requests foreground location once. Denial leaves Current unavailable and allows a saved place to be selected instead. Approximate permission remains useful for provisional Current, but Travel requires Android's Precise location choice.

Ordinary Current uses a battery-conscious foreground request profile. Travel asks Android for more
frequent precise foreground fixes, but actual delivery cadence and visible smoothness are governed
by Android, the phone and its GNSS hardware. A short-lived on-device display tracker may smooth the
marker/camera between accepted fixes for at most 1.2 seconds; it is best effort, and its projected
points are never stored or used for weather, radar, places or alerts. Travel is opt-in, is not
persisted and stops when Radar leaves composition, Current is deselected or the app backgrounds. It
keeps the screen on only during that same visible active interval.

Rain Alarm requests no background-location permission, starts no location foreground service, holds no wake lock and shows no ongoing location notification. Live fixes stay in process memory unless the user explicitly saves the current location as an ordinary saved place.

### Notifications

On Android 13 and later, Rain Alarm requests notification permission once after the location prompt if needed. Denial leaves Rain Notification and Lightning activity notification unable to deliver, with a permission-needed message, and Settings can retry. Lightning activity notification defaults off. Rain Alarm does not send a test notification merely to obtain permission.

Optional alert work uses Android WorkManager. It requests no exact-alarm permission and no foreground service. See [Notification and widget behavior](#notification-and-widget-behavior) for the data it uses.

## Network requests

Requests are feature-driven. Leaving Wind and Clouds off makes no request for those Radar layers.
The selected Radar Lightning visual is requested only while Lightning is enabled. Background
Lightning observations are separate and are requested only when Lightning activity notification is
enabled or a configured widget presentation needs its state. If neither applies and the Radar layer
is off, no Lightning request is made. Search runs only after explicit submit, and disabled services
or layers remain idle.

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

The default experimental individual-flash Radar visual sends an ordinary HTTPS `GET` or conditional
request to `rain-alarm-lfl-feed.aaronjoakley55.workers.dev`. The URL contains only one fixed regional
path (`uk`, `de`, `nl`, `ch` or `fr`) chosen from the selected place's code-owned radar region. It
does not contain an account credential, place name or selected latitude/longitude, and panning the
map does not change it. Cloudflare receives normal network metadata such as the connecting IP. The
response is gzip JSON containing recent EUMETSAT satellite-observed flash-centroid coordinates,
exact observation times, status/provenance and attribution for that whole region. Rain Alarm
validates it and keeps only a bounded process-scoped verified response/cache state.

Selecting accumulated flash areas for Radar, enabling Clouds, or running background Lightning
detection accesses EUMETSAT's public HTTPS WMS at `view.eumetsat.int`. For Radar imagery Rain Alarm
first reads bounded capability metadata and probes the selected product, then downloads the finite
set of regional PNG images needed for the observed Radar window.

The WMS request contains the chosen product, advertised observation time, image dimensions and a
bounded regional map extent. The selected place chooses a code-owned British Isles, Europe, North
America East/West or local fallback region with roughly 200 km of margin; panning does not turn this
into a new request. Leaving both satellite controls off sends no visual-layer request.

Verified satellite PNGs are stored in an app-owned atomic least-recently-used cache capped at 128 MiB. MapLibre separately keeps up to 64 MiB of basemap cache, for an intended map/satellite disk budget of about 192 MiB. Files are keyed by product, time, bounds and region rather than a user identity.

When a Lightning alert or widget needs nearby activity, Rain Alarm uses the same public EUMETSAT
WMS but requests a separate bounded target-centred image covering a 15 km circle plus sampling
margin. It checks every newly advertised five-minute observation since the last contiguous
successful checkpoint, up to a bounded catch-up, and retains only compact frame identities,
detection evidence and episode state. The background detector does not persist the downloaded
images and does not claim that missing data is clear.

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
- preferred radar and Radar Lightning visual providers, plus provider-specific options;
- app, map, Compass and Graph appearance choices, including day/night profiles;
- playback speed, visible Now metrics, layer toggles, wind-arrow size and coverage-mask darkness;
- rain and lightning notification choices, per-target observation checkpoints and per-subscription
  episode/delivery state;
- each widget's self-contained saved-place snapshot, compact content choice, opacity and quiet
  hours, plus a bounded normalized weather snapshot for non-blank refreshes;
- a target-bound ten-minute temporary-Lightning hand-off lease when a notification or widget is
  opened;
- first-launch and tour completion state; and
- bounded provider, search, map and satellite caches described above.

Current location is a virtual selection. Live fixes and coordinates stay in memory unless **Save current location** is used. A legacy persisted current-location record is migrated to the virtual live selection without retaining its old coordinates; ordinary saved places remain intact. Existing selections and explicit settings survive upgrades.

Rain Alarm has no account sync or cloud backup service of its own. Android or the device manufacturer may apply its normal app-backup policy outside Rain Alarm's control.

## Notification and widget behavior

Rain Notification follows the active selected saved or virtual live place and configured widget
monitoring subscriptions. Lightning activity notification is a separate default-off choice. One
network-constrained WorkManager job checks approximately every 15 minutes, Android's periodic
minimum; execution is inexact and may be deferred by Doze, force-stop, battery restrictions or the
device manufacturer. Widgets therefore self-update on a best-effort schedule, not a guaranteed
clock.

The app's selected-place rain alert retains its immediate approaching-rain behaviour and established
cooldown. Widget rain subscriptions require an earlier complete dry 0–60 minute evaluation before a
later approaching episode may notify; partial, stale or unavailable data cannot arm them. Lightning
uses observed five-minute flash-area frames inside 15 km: the first valid detection episode after
enabling is eligible immediately, continuing detection does not repeat, a successful no-detection
re-arms, and unavailable data changes neither state. Rain and lightning that newly trigger together
share one notification.

Every widget may define quiet hours. During them acquisition, widget display and episode state
continue, but delivery is consumed rather than queued for later. Widgets sharing a target/provider
reuse acquisition. Widget work remains eligible whether the app activity is visible, backgrounded
or process-recreated; identical app/widget requests may safely share repository/cache work.

Every widget is bound to one saved-place snapshot. It needs no location permission and never follows
virtual Current location. App-only Current monitoring still uses only a sufficiently fresh lawful
foreground fix. No background location is acquired for an alert or widget.

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
