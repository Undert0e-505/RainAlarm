# Data sources

Rain Alarm has two selectable radar providers. MeteoGroup regional is the
default; RainViewer is the open worldwide choice and automatic session fallback.
They are independent composites with different inputs and processing, so their
precipitation footprints, intensities and timestamps can materially disagree.
Rain Alarm normalizes presentation thresholds and colours but does not dilate,
move or otherwise force either provider's echoes to match the other.

Provider selection is displayed immediately, then persisted after a 400 ms
quiet period. The selected-place Now analysis observes only that settled value;
rapid changes therefore cancel the pending choice and start at most one new
radar analysis after the user stops tapping. This coalescing does not delay app
startup, place changes, manual refresh, or the Radar screen's own refresh.

## MeteoGroup regional

- Manifest pattern: `https://cdn.meteogroup.de/images/mapengine/rain2.0/rad_{area}/images.xml`
- Supported areas: `uk`, `de`, `nl`, `fr`, and `ch`.
- The regional raster rectangles overlap several neighbouring countries. When
  more than one contains a place, the app chooses the feed with the nearest
  normalized footprint centre (stable ID tie-break), rather than the smallest
  rectangle. This keeps eastern UK places on `uk` while selecting the nearby
  continental feed for France, Benelux, Germany, and Switzerland. It is a
  deterministic coverage heuristic, **not** a precise coastline or national
  boundary; points close to borders or over the sea may be approximate.
- Use: country-sized grayscale radar rasters, two-channel velocity JPEGs, exact
  server timestamps and forecast flags, including forecast frames through +60.
- No credentials or secrets are included. Requests use HTTPS and validate the
  fixed host, relative paths, media types, sizes, and raster dimensions.
- This is an undocumented availability-dependent application feed. Rain Alarm
  treats coverage, parse, network, or image failures as a reason to use the
  open provider for that screen or worker session and explains the fallback.

The renderer is a clean-room implementation based on observable inputs and the
recovered mathematical behaviour only. For output pixel `p` and fraction `t`:

```text
v = (velocityRG * 2 - 1) * areaVelocityScale * pixelSize
from = frameA(p - v*t)
to = frameB(p + v*(1-t))
output = mix(from, to, t)
```

Regional JPEGs are decoded as ARGB_8888 so the full eight-bit red/intensity
channel reaches the one-channel GLES2 texture. No spatial blur is applied on
upload. GLES2 uses one GL_LINEAR fetch per radar frame, retaining the symmetric
velocity-compensated A/B interpolation and exact power-of-two backing UV map.
On devices reporting fragment `highp`, texture coordinates and intermediate
warp math use it; GLES2 `mediump` remains a compile/capability fallback and
may show more subpixel banding at close zoom.
The earlier four-fetch cubic reconstruction and radius-three upload blur were
removed: they cost time or changed precipitation coverage without adding source
detail. The retired app's documented 20 intermediate subframes describe
temporal interpolation, not a proven spatial enhancement algorithm.

The code-native palette retains the retired app's observed cyan, blue and dark
extreme colour bands, but intentionally adapts regional *low-end opacity* to
the dark basemap. Values through 52/255 are transparent; 52–83/255 ease from
clear to the original 83/255 alpha instead of jumping from clear to 38/255
alpha at raw 63. This softens source-cell outlines without blurring or creating
large low-value JPEG haze. The radar surface opacity is 0.9. RainViewer RGB is
decoded to this same shared intensity scale before upload; its PNG alpha is
source rendering transparency and is never treated as precipitation strength
or evidence of geographic radar coverage. Radar,
Now and alerts therefore share the regional 63/255 wet boundary after decode;
faint map traces below 63 are not classified as precipitation at the selected
point.

This is a clean-room visual emulation of observed output, not a claim to have
recovered the proprietary spatial algorithm or higher meteorological resolution.
The fixed UK raster is 583×767; close zoom still magnifies source pixels. The
static failure fallback uses the same LUT but a half-size emergency decode to
bound memory. Cold scrubbing into an uncached frame still requires JPEG decode
and upload; revisiting a resident frame does not. Velocity bytes and the
three-pair residency cap are unchanged.

Regional Now and notifications prefer a separate MeteoGroup **area rain chart**.
Selected coordinates resolve to an AREA_ID, then that area's provider
average/minimum/maximum profile is sampled at actual wall-clock time. This is
a location-selected area forecast, **not** an exact marker-pixel reading; its
min/max values are provider bands, not a raster neighbourhood. The source-age
badge uses the chart's `creationdtg`. UTC `startdtg` and the provider interval
define the available horizon. Data older than 10 minutes, missing current
coverage, or malformed arrays are rejected. The chart uses its original
25%-of-domain rain threshold and threshold-subtracted graph baseline. It may
differ from the map's raster at a pin because they are separate products.
The AREA_ID lookup is cached for exact selected coordinates (not rounded to a
neighbourhood), so crossing a warning-zone boundary cannot reuse a nearby
place's cached ID. The nominal chart domain is a display scale: small negative
raw provider samples are accepted and clamped when normalized, not rejected.

If area lookup or chart retrieval fails, Now and alerts fall back to the compact
raster point forecast. That fallback anchors at actual wall-clock time, not the
latest observation, and its source-age badge uses the latest observation. Its
selected-place average uses the exact projected marker with the map's bilinear
first/last-texel-centre layout and symmetric local-velocity frame warp. A small
source-space patch per frame (at most 27×27 pixels with current regional scales)
supports minute sampling without a full regional grid. Surrounding 5×5
minimum/maximum values form only the fallback chart neighbourhood envelope,
not evidence that the marker itself is wet.
Point-only Now/alert analysis downloads the latest observation velocity and
each advertised forecast-frame velocity, because each interval uses its left
frame's vector. It decodes only a tiny selected-place region and immediately
recycles the bitmap; unlike the map session it retains no compressed velocity
images or full-frame grids. A frame without a velocity file uses the renderer's
neutral-motion fallback for that interval.
The shared wet threshold is 63/255 normalized intensity, above the faint trace
portion of the display ramp. RainViewer point forecasts use the same decoded
intensity as its map; PNG alpha alone is not rain strength. If
the provider forecast ends before
now+60, the minute series ends at the last covered minute and is explicitly
partial; missing minutes are never padded as dry, and no-rain alerts are
unknown rather than clear. Stale observations or missing current coverage are
unavailable. These point estimates remain bounded by the regional source's
roughly 2 km pixels and should not be mistaken for a street-level gauge.
The raster-fallback Now chart uses a separate, monotonic presentation scale: the source-specific
wet boundary sits on its baseline, pale regional cyan and roughly 15 dBZ open
radar sit low in Light, and progressively stronger returns span Medium and
Severe. The chart line and uncertainty envelope share this mapping; rain
classification, ETA, alert decisions, the map shader, and compass colours keep
the original provider values. This is a visual severity aid, not rainfall rate.
Radar opens paused at wall-clock time when its cached forecast covers that
time; otherwise it opens at the latest observation and labels that time.

Radar JPEGs are 24-bit grayscale. Live velocity samples contain motion in red
and green while blue is effectively zero. R/G are therefore uploaded as an
OpenGL ES luminance/alpha texture, making `.xw` sampling yield the two decoded
channels. This is an explicit format inference, protected by codec and live
dimension tests; unexpected files fail closed to the open provider.

Each area's pixel size, offset, dimensions, scale and projection parameters are
expressed as new Kotlin configuration. Proj4J transforms a cached 25×25 radar
mesh into WGS84; the map projects those vertices at camera updates. An isolated
bounds fallback exists for projection failures, though all five production
configurations pass the native-projection mesh tests.

## Place search

- Ordinary town/place queries continue to use Open-Meteo's GeoNames-backed
  geocoding endpoint. A query which matches a complete UK postcode shape
  (case-insensitive, with or without its internal space) instead uses the exact
  `https://api.postcodes.io/postcodes/{postcode}` lookup. A valid unknown
  postcode returns no match; connectivity, server and malformed-response errors
  remain visible failures rather than being presented as an empty result.
- postcodes.io is a free/open API and requires no authentication. The saved name
  is its canonical formatted postcode; available parish, admin district, admin
  county and country values form the display detail without inventing a postal
  town. Some territories legitimately have no WGS84 coordinate and therefore
  cannot become a map place.
- Great Britain postcode data is available under OS OpenData terms. The provider
  notice includes Ordnance Survey data © Crown copyright and database right,
  Royal Mail copyright and database right, and National Statistics data © Crown
  copyright and database right. Northern Ireland data has separate ONSPD/LPS
  terms; consult the current provider notice before redistribution.

References:

- https://postcodes.io/docs/overview/
- https://postcodes.io/docs/postcode/lookup/
- https://postcodes.io/docs/licences/

## Foreground current location and Follow

- Current location is one process-wide accepted fix stream shared by Now,
  Radar, Places and foreground alert evaluation. Google Play services fused
  high-accuracy location is the primary path; Android's GPS provider is the
  precise fallback when Play services is unavailable or a fused request fails.
  Rain Alarm does not call a separate location web endpoint; the device and
  Google Play services may use their configured system location sources under
  the platform's own settings and privacy controls.
  A coarse network fix is only an explicitly provisional last resort for
  ordinary Current, never Follow navigation truth, and it does not race a good
  GPS fix merely because it is newer.
- Ordinary foreground Current requests target a 5-second interval (2-second
  minimum). Visible Follow requests target 1 second (500 ms minimum), zero
  batching and a briefly awaited accurate first fix. Follow requires the Fine /
  Precise permission choice, is opt-in, is not persisted, and stops when Radar
  leaves composition, Current is deselected or the app backgrounds. The screen
  is kept on only for that same visible-Follow interval. Follow adds no
  background location, foreground service, wake lock or ongoing notification;
  it uses the visible activity view's screen-on flag. Existing WorkManager
  scheduling remains separate for optional periodic rain alerts.
- Fix arbitration rejects stale, out-of-order, invalid and physically
  implausible jumps using monotonic time, both fixes' reported accuracy radii,
  displacement and elapsed time. A recent accurate fix is not displaced by a
  much coarser callback; a 15-second weak-signal grace prevents availability
  flicker, while a stale last fix can be replaced so recovery remains possible.
  No precise coordinates are written to logs.
- Every accepted navigation fix moves the Radar marker and, while Follow is
  active, smoothly supersedes the prior north-up camera transition without
  changing zoom. Weather and reverse geocoding use a separate analysis anchor:
  the first fix and a provider-region change are immediate; in Follow the
  anchor otherwise advances at 1 km, or after two minutes plus at least 250 m.
  This prevents one-second network or geocoder requests. Outside Follow the
  established 250 m Current threshold remains.
- While visible Follow is active, one coordinator derives the next expected
  publication from actual provider timestamps: recent radar gaps (5/10-minute
  source fallback), advertised 10-minute Clouds and 5-minute Lightning
  cadence, and 15-minute Wind/point-model steps. A 45-second publication grace
  precedes each check. Unchanged or failed checks back off for 1, then 2, then
  at most 5 minutes. Each stream permits one in-flight generation, only enabled
  ancillary layers poll, and stale completions cannot publish. Manual Refresh
  force-checks and rebases the clocks. Stopping Follow, leaving Radar,
  deselecting Current or backgrounding immediately pauses the coordinator;
  visible data and caches are retained.

## Open-Meteo

- Endpoint: `https://api.open-meteo.com/v1/forecast`
- Use: one selected-coordinate `current` response for temperature (°C), sea-level
  pressure (hPa), relative humidity (%), UV index, daylight, and 10 m wind,
  plus `daily=sunrise,sunset&timezone=auto&timeformat=unixtime`. Solar event
  instants are formatted in the returned IANA timezone for the selected
  place's **current local date**, including DST changes. Missing events show
  unavailable; a local-day change refreshes the point response. These facts
  support Now indicators and help select the preferred daytime or nighttime
  Clouds product; they do not alter radar prediction or alerts. Stale or
  missing daylight data falls back to today's solar events or a deterministic
  coordinate/time solar calculation rather than disabling Clouds. A separate
  request only while Wind is enabled carries
  25 distinct coordinates spanning the settled **visible map viewport** with
  offscreen 17% margin rows/columns plus an exact, bounded
  `start_minutely_15` / `end_minutely_15` window covering the active radar
  session. The response supplies 15-minute 10 m wind speed/direction frames;
  the map selects the latest model step at or before the radar cursor, clamping
  at the first/last returned step. The visible nine use exact quarter,
  midpoint and three-quarter positions on each axis. Arrows are georeferenced
  and point downwind; one compact
  status beneath the connected layer segments reports selected-place speed
  and meteorological *from* direction when the layer is available. Map
  arrows do not repeat numeric speed labels. Provider and licence details are
  kept in Settings > About the data rather than repeated over the map.
- The point request is place-aware, cached for 15 minutes and rejected when
  its model valid time is older than 60 minutes. Day/night status expires after
  30 minutes. Wind series are cached by quantized viewport and session window
  for 15 minutes,
  with at most eight process-scoped grid entries. A request occurs only after
  a meaningful settled camera change or explicit refresh, never per gesture
  frame, radar animation frame or 15-minute cursor step. Until MapLibre has
  supplied stable viewport bounds the Wind grid remains loading rather than
  being treated as unavailable. The larger 25-coordinate response uses a
  bounded weak-network policy: 15-second connect and 30-second read limits,
  attempts scheduled at approximately 0, 15, 30 and 45 seconds for transient
  network/408/425/429/5xx failures, and a 55-second overall deadline. Missed
  offsets after a slow request still retain a minimum 1.5-second retry spacing.
  It remains `Wind loading` throughout that policy. An early nonretryable HTTP
  or response-validation error is recorded internally without generating
  further unsuitable requests, but the UI still waits for the active
  generation's complete 55-second monotonic acquisition window. `Wind
  unavailable` can appear only once that window ends without a usable grid.
  A fresh same-viewport grid remains visible during replacement; request
  identities cancel and prevent stale pan/zoom generations from publishing.
  Network and parsing work runs outside the small cache critical section. A
  newly fetched grid's timestamp is reconciled with the slower UI freshness
  clock so it can reach the map immediately, and loading clears only after the
  current generation positively reports a rendered arrow field. No
  current-location coordinates are persisted.
- `current` and the wind series are weather-model output, **not** measured
  station observations. Fifteen-minute wind is native for supported regional
  models (currently Central Europe and North America) and interpolated from
  hourly model values elsewhere; the UI does not claim finer underlying model
  precision. The existing Open-Meteo precipitation
  fallback remains separate. Public API use is subject to Open-Meteo's
  noncommercial and attribution terms; review them before distribution.

References:

- https://open-meteo.com/en/docs
- https://open-meteo.com/en/terms

## Optional EUMETSAT satellite layers

- Public WMS: `https://view.eumetsat.int/geoserver/wms`.
- **Lightning** uses `mtg_fd:li_afa`: accumulated satellite optical **flash
  areas**, not individual lightning locations or verified cloud-to-ground
  strikes. Its advertised start, latest time and 5-minute cadence are parsed
  from GetCapabilities.
- **Clouds** is one persistent control with two exact WMS products. Daylight
  prefers MTG Cloud Type RGB (`mtg_fd:rgb_cloudtype`); night prefers MTG Fog /
  Low Clouds RGB (`mtg_fd:rgb_fog`). A fresh selected-place Open-Meteo `is_day`
  value is preferred, then today's valid sunrise/sunset, then a deterministic
  coordinate/time solar calculation, so unknown daylight never disables the
  layer. If the preferred product cannot be verified, the other product is
  tried. The imagery shows cloud structures or fog **or low cloud** and is not
  a confirmed surface-fog diagnosis.
- Lightning and Clouds are independent persistent toggles and may be rendered
  together; Clouds is added below Lightning so flash areas remain legible. A
  previously verified, fresh Clouds product remains rendered while a day/night
  replacement is checked, then swaps in place. Activation feedback stays
  visible through loading, holds its terminal state for one second and then
  fades independently; selected-place Wind remains visible while enabled.
- The radar cursor's absolute epoch selects the most recent advertised
  satellite observation at or before it: Lightning advances on its 5-minute
  WMS cadence and Clouds on their 10-minute cadence. Cursor positions after the
  latest satellite time hold the latest observation; the app does not
  extrapolate clouds or claim an EUMETSAT forecast. Cursor positions before the
  advertised product range show no future observation. Cloud day/night product
  choice uses the effective satellite-frame time, so a timeline crossing
  dawn/dusk can change products when both catalogues cover it.
- Capabilities XML is bounded to 1 MiB and decoded as strict UTF-8. Parsing uses
  Android-compatible DOM configuration while explicitly rejecting document-type
  and entity declarations, disabling entity expansion and installing a resolver
  that rejects every external resource. Optional parser hardening is applied
  only when the platform factory supports it.
- Each WMS frame is pinned to one advertised valid time. The app downloads it
  explicitly from the allowlisted EUMET HTTPS WMS host, validates the HTTP
  status/content type, PNG structure/checksums and exact dimensions, and only
  then supplies its bitmap to a transparent MapLibre `ImageSource`. Its
  EPSG:3857 BBOX is exactly the provider/region intersection and
  its projected aspect ratio is preserved within a 128–1024px bound. The same
  fixed regional image is georeferenced at every camera zoom: it neither
  requests the full satellite disk nor disappears at world zoom, and a close
  view deliberately overscales that source rather than starting a hidden tile
  download. The selected place chooses the smallest code-owned operational
  region: British Isles first, then Europe, overlapping North America East/West
  regions, then a bounded local fallback. Camera panning and zooming never
  change that region or the satellite resource identity. Each definition has
  an inner selection rectangle and an outer request rectangle with approximately
  200 km of surrounding margin. The EUMETSAT-advertised bounds are intersected
  with the outer rectangle before the WMS URL is built; outside that exact
  georeferenced rectangle the source contains no satellite pixels.
- A small selected-place image probe checks the latest product's content type
  and PNG signature before its advertised time range is accepted;
  cadence-derived historical frames do not add a blocking HTTP probe. For each
  enabled overlay, the app derives all unique observed product/time frames that
  intersect the radar observation window and deduplicates the latest observation
  held through forecast time. Two explicit PNG downloads are allowed at once
  across Clouds and Lightning (the tested hard configuration maximum is three),
  and the overlay remains hidden until every identity in that finite set is a
  complete, verified, atomically committed cache file. Bottom-right
  `<Data> loading n/N` feedback reports verified-file progress without moving
  the layout. Temporary, partial, malformed, wrong-content and wrong-dimension files
  never count as ready.
- Satellite PNGs use an app-owned, atomic, least-recently-used disk cache capped
  at 128 MiB. MapLibre's separate supported ambient database is capped at
  64 MiB for the base map, for an intended total map/satellite disk budget of
  about 192 MiB. Historical regional product/time URLs are immutable within a session; exact
  product/time/clipped-bounds/region identities prevent repeat fetches on loops
  and allow different selected places inside one operating region to reuse the
  same resources. Pan/zoom does not rebuild the plan or restart preparation. A
  refreshed plan reuses every overlapping verified file and fetches only new
  frames while retaining the last complete visible set. Network reads have a
  20-second timeout and at most three attempts. Missing/corrupt/evicted files are
  invalidated and acquired again rather than becoming permanently false-ready;
  exhausted failure retains the old complete set where possible and reports only
  the canonical layer-unavailable state.
- Radar Refresh creates a new ancillary-layer generation. For enabled Clouds it
  forces fresh EUMETSAT capability discovery and a current-frame probe for both
  Cloud Type and Fog / Low Clouds, bypassing their five-minute metadata/probe
  caches. It does not purge immutable product/time/region PNGs: valid cache files
  are reused and only new, missing, corrupt or evicted identities download. Rapid
  repeat taps cancel/supersede older UI work so only the latest generation may
  publish. Disabled Clouds causes no cloud metadata, probe or frame acquisition.
  Frame downloads share a global two-request semaphore (the tested hard maximum
  is three), allow up to three attempts, and use five-second connect and
  twenty-second read timeouts. Cloud metadata is accepted for at most one hour.
- Satellite UI status follows the real pipeline but deliberately hides its
  implementation phases. Download/validation reports `Clouds loading n/N` or
  `Lightning loading n/N`; bitmap handoff reports the same loading state without
  a count; and a terminal acquisition/render problem reports only `<Data>
  unavailable`. A verified previous complete set remains visible during
  replacement where one exists. Metadata availability alone is never treated as
  rendered readiness. When Wind or satellite metadata expires, one replacement
  generation is started for that exact stale identity; a failed generation does
  not create a recomposition retry loop. Manual Refresh force-retries Radar and
  every enabled layer, while disabled layers make no request.
- The compact operational queue orders Location, Radar, Wind, Clouds and
  Lightning and exposes only `<Data> loading` (optionally `n/N`) or `<Data>
  unavailable`. Raw provider, model, freshness and exception text remains in
  internal diagnostics. Location acquisition and failure use the same queue;
  map-style, renderer, compatibility and chart notices remain in the separate
  bottom-left system rail.
- After the full set unlocks, only the active image and one progressive
  replacement normally remain decoded/live. A retiring predecessor exists briefly
  during handoff; the full set remains compressed on disk, so decoded/GPU state
  stays bounded. Bitmap decoding runs off the UI thread and MapLibre mutation
  returns to the main thread. Readiness reveals a replacement
  while retaining its predecessor; only a later fully rendered frame for the
  expected active generation retires the old source. This post-reveal overlap
  avoids a frame in which both rasters are transparent. While playing, a slow
  forward intermediate may be promoted and loading then jumps to the latest
  conflated target. Paused scrubbing discards a ready stale pending frame and
  loads only the exact latest target. Loop wraps/backward seeks and old
  day/night products cannot snap back over the cursor. Independently changing
  Clouds never tears down Lightning and vice versa. Source loading is optional
  and independent of the radar GLES overlay. Satellite layers are withheld if the advertised time is
  older than 30 minutes for flash areas or 60 minutes for either Clouds
  product, or if the
  selected place is outside the reported coverage bounds. Empty transparent
  flash imagery is **not** evidence that no lightning occurred.
- EUMETSAT attribution is listed in Settings > About the data; core product data
  are subject to CC BY 4.0. Bitmap-backed ImageSource rendering on Android still
  needs a physical-device visual check on each supported MapLibre/API
  combination. This implementation does **not** ingest the separate per-flash
  NetCDF collection, which requires registered access/token and parsing.

References:

- https://user.eumetsat.int/resources/user-guides/eumet-view-user-guide
- https://user.eumetsat.int/resources/user-guides/eumetview-image-download-by-using-fixed-urls-guide
- https://user.eumetsat.int/resources/user-guides/data-registration-and-licensing
- https://user.eumetsat.int/catalogue/EO%3AEUM%3ADAT%3A0691
- https://user.eumetsat.int/catalogue/EO%3AEUM%3ADAT%3A1023
- https://maplibre.org/maplibre-style-spec/sources/

## RainViewer

- Endpoint: `https://api.rainviewer.com/public/weather-maps.json`
- Use: past radar frames only. Rain Alarm does not present these frames as a
  forecast.
- Images: coordinate-centred 512-pixel PNGs requested at provider zoom 5 for
  regional coverage and zoom 7 for local detail. Scheme ID `2` is Universal
  Blue with source smoothing enabled. Explicit Open-radar selection requests
  `1_0` by default (snow colours off); the persisted **Show likely snow** option
  requests `1_1`. Automatic fallback from a requested MeteoGroup session always
  remains snow-off.
- Point analysis also requests exactly one small, static RainViewer coverage
  mask at detail tier (`/v2/coverage/0/...`) per loaded analysis session, never
  one per history frame. In that documented mask, transparent pixels are
  covered and opaque black pixels are outside radar coverage. A failed or
  malformed mask is retained as unknown so it cannot turn transparent/no-data
  precipitation pixels into a false dry result.
- A centralized decoder projects every RGB value—including source-smoothed
  intermediate colours—onto RainViewer's published Universal Blue rain curve
  and, only for `1_1`, its snow curve. The result is pre-encoded as shared Rain
  Alarm intensity, likely-snow confidence and source alpha. Alpha remains a
  rendering/motion mask: it can already be fully opaque at light 15 dBZ and is
  never precipitation strength or proof of geographic coverage. Both map and selected-point series use the
  same decoded intensity and the regional Rain Alarm LUT, opacity threshold,
  hardware-linear sampling and temporal motion warp.
- With likely snow enabled, rain keeps the normal palette while snow-classified
  pixels use an icy lavender through violet/blue-violet to deep indigo LUT.
  Radar, Now, graph and alert text carry the type at the relevant current/onset
  minute. The provider classification is model-assisted, so the UI says
  **likely snow**; it is not confirmation of surface snowfall. With the option
  off, snow-coloured echoes are decoded and displayed as rain-style
  precipitation rather than removed. Intensity remains qualitative
  reflectivity-derived Light/Medium/Severe display severity, not measured rain
  or snowfall rate, and neither RGB smoothing nor reflectivity determines
  ground precipitation precisely.
- Current public contract: approximately two hours of past data in ten-minute
  steps, maximum zoom 7, Universal Blue only, and a 100 requests/IP/minute rate
  limit. Radar fetches the manifest once per visible place session, normally
  downloads two images for each of roughly 13 returned observations with
  concurrency capped at three, and retains those decoded images only for that
  composable session. Map pan, zoom, and recentering do not fetch more radar
  data. The regional z5 tier spans four times the projected width and height of
  the z7 image. If local-detail loading fails, the required regional tier stays
  usable and the UI labels that degraded state. Background alerts fetch five
  recent frames at z5 for motion plus z7 for current-location sampling.
- Constraint: this public service is best-effort and is intended for personal,
  educational, or community projects. Reconfirm terms before distribution or
  material traffic.

References:

- https://www.rainviewer.com/api/weather-maps-api.html
- https://www.rainviewer.com/api/color-schemes.html
- https://www.rainviewer.com/files/rainviewer_api_colors_table.csv
- https://www.rainviewer.com/api/map-tiles.html

## Client-side motion estimate

RainViewer supplies observations only. Rain Alarm compares each adjacent pair
of downsampled regional and detail intensity masks using bounded local block
matching. Sparse/ambiguous blocks are rejected, vector outliers are removed,
and accepted neighbours are median-smoothed into a cached 8×8 velocity field.
Each interval field drives the same GLES symmetric A/B warp as the regional
provider; rejected pairs crossfade without a jump. A median aggregate of recent
reliable fields advects the latest fixed mosaic for up to 60 minutes. Analysis,
smoothing and texture encoding happen once on session load, never on an
animation tick. MapLibre remains only the basemap and interaction surface.
This interpolation and extrapolation are entirely client-side; RainViewer
supplies neither motion vectors nor future frames.

The radar surface uses a GLES 2 shader contract validated before compilation.
Session binding and `TextureView` surface delivery may occur in either order;
the renderer gates initialization until both exist, schedules an explicit first
frame, and recreates EGL resources after surface loss. Provider selection,
session creation, EGL/shader/texture initialization, and draw failures are
logged under `RainRadarProvider` and `RainRadarRenderer`. Display failures are
also shown in the radar UI instead of leaving a silent transparent surface.

Regional screen sessions retain bounded compressed JPEG data rather than a
decoded bitmap set (4 MiB per resource, 64 MiB per session). Transfers run two
at a time in current/forecast/history priority order while the timeline remains
chronological. From its first draw, the GL renderer keeps at most three
radar/velocity frame pairs (six textures), or two pairs after a prior interrupted
renderer stage. It uploads only the current frame for the first draw, decodes
adjacent pairs lazily during slider use, and evicts old textures before allocating
replacements. There is no full-residency warm-up path.

The GL thread decodes only one radar or velocity bitmap at a time and recycles it
in `finally`. Regional radar uses one-channel luminance and velocity uses
two-channel luminance/alpha, both converted one row at a time. Regional rasters
are conservatively stored in power-of-two backing textures with exact adjusted
UV coordinates for GLES 2 driver compatibility. EGL recreation rebuilds lazily
from the same compressed session bytes and never causes a network reload. A
local interrupted-stage marker enables the safer two-pair mode on the next
session. A caught renderer failure is shown in the UI and switches to a lazy,
downsampled static CPU display of the current regional frame rather than leaving
a blank radar. Background Now/alert analysis decodes only 5×5 selected-point
regions. It retains compact minimum/average/maximum intensity values for each
provider timestamp and one current local velocity sample for direction; it
retains no render bitmap, full intensity grid, or velocity image.

The Now screen and alert worker consume the same normalized minute-series
contract from Now through the available horizon, up to +60 minutes. The
MeteoGroup area chart is interpolated from its provider profile; if unavailable,
regional raster values are interpolated from exact forecast timestamps.
RainViewer values preferentially sample the confidence-gated dense-flow
advection field once per minute. If that local field cannot be established but
the separately confidence-gated whole-field physical translation is usable,
Now and alerts share a lower-confidence broad-motion path through the same
latest decoded grid. That fallback converts world displacement into the actual
sampling tier, includes observation age, and stops as partial at the downloaded
tile edge instead of padding unknown space as dry. If neither motion estimate
is reliable while echoes exist, the result remains unavailable. Motion is not
needed for a genuinely clear latest observation: when no decoded value anywhere
in the retained detail tile reaches the shared 63/255 wet threshold, the
observation is fresh, the coverage-mask centre 5×5 neighbourhood is covered,
and at least 99% of the retained tile is covered, Now and alerts share a 61-point
zero series with no bearing. The one-percent allowance only tolerates rasterised
mask-edge pixels at distant tile corners; it never permits a gap at the selected
point. Missing, stale, invalid or dimension-mismatched evidence remains
unavailable. The area chart uses its 0.25 normalized provider threshold;
regional and decoded RainViewer rasters share the 63/255 Rain Alarm threshold.
Rain ends at the first later minute below the active threshold, and unavailable local motion is
reported honestly rather than replaced with an invented bearing or arrival.
The compass shows the source bearing (the opposite of precipitation travel)
with geographic North fixed upward. Its chart carries the compact neighbourhood
minimum/average/maximum envelope.

This is a local steady-flow extrapolation, not a meteorological forecast. It
cannot model storm growth or decay, future wind changes, orographic effects, radar
coverage gaps, attenuation, or palette changes. Low-confidence, sparse,
blank, noisy, stationary, or implausibly fast estimates are rejected. When no
reliable regional motion exists, the radar timeline ends at Now rather than
showing a frozen future segment. When RainViewer is selected or used as
fallback, the Now screen reports that radar estimate and its confidence; if the
field is rejected, the direction and future timeline are unavailable.

## Alerts

On a fresh install the optional alert is enabled after notification permission
is granted; declining permission leaves it off until the user retries in
Settings. It follows the active selected saved or virtual live place.
WorkManager checks approximately
every 15 minutes (the Android periodic minimum); execution is inexact and may
be deferred by Doze. Regional checks prefer the same fresh, location-selected
area profile as Now, falling back to projected selected-point raster sampling.
Open checks use the
confidence-gated motion estimate and treat unavailable analysis as unknown.
Android 13 and newer also require the
user-granted notification runtime permission. No background-location
permission, exact alarm, foreground service, or test notification is used.

Android references:

- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- https://developer.android.com/develop/ui/views/notifications/notification-permission

## Map and basemap

- Map renderer: MapLibre Native for Android.
- Basemap styles: OpenFreeMap Dark (default), Liberty (Light), and Fiord
  (map Slate). Follow system resolves to Dark or Liberty from Android
  night mode; Fiord remains an explicit selection.
- Appearance choices use a fixed four-column matrix: App offers Dark, Light
  and Follow system; Map adds Slate; Compass and Graph independently offer
  Dark, Light, Follow app and Slate. Their default remains Follow app. Card
  Slate uses `#45516E` for both surfaces and a dark high-contrast palette.
- Dark and Slate apply style-specific text colours and dark halos to known
  text-bearing place, road, road-reference and water label layers. They do
  not change label layout, fonts, icons, radar/ancillary overlays or opacity.
  Liberty/Light label paint is left unchanged.
- Map data: OpenStreetMap contributors.
- MapLibre's compact native attribution control remains available over the
  radar map.

References:

- https://maplibre.org/maplibre-native/android/api/
- https://openfreemap.org/
- https://www.openstreetmap.org/copyright

## Operational posture

Radar imagery, area lookup, Open-Meteo, postcodes.io and RainViewer use HTTPS. Complete
UK postcode queries go to postcodes.io while other place-search text goes to
Open-Meteo. Live device coordinates remain process-memory-only unless the user
explicitly saves the current fix as an ordinary saved place. The legacy
area chart host's HTTPS certificate fails hostname verification, so only
`android.weatherpro.weatherservice.meteogroup.de` has a narrow Android
cleartext exception; its AREA_ID request uses HTTP without disabling TLS
verification elsewhere. Lookup sends selected latitude/longitude, while the
chart request sends only the returned AREA_ID. Neither includes a device or
account identifier. Avoid using these legacy endpoints for sensitive location
data; the chart's ongoing availability is not guaranteed. There are no embedded
service credentials. Provider adapters are separate from mapping
and analysis logic so a self-hosted or regional provider can replace them
without changing the rain-status contract.
