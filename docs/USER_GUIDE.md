# Rain Alarm user guide

Rain Alarm answers a place-specific question: when will rain reach me? **Now** summarises the next hour, while **Radar** lets you inspect the available observations, forecasts or local motion estimates. This guide describes version 0.9.2 (version code 22).

## First launch and installation

Download the signed APK from the [latest GitHub release](https://github.com/Undert0e-505/RainAlarm/releases/latest). Android may ask you to allow APK installation from the browser or file manager that opened it. Rain Alarm supports Android 8.0 and later.

On first use, Rain Alarm asks for a language before either platform permission prompt. **Device language** is selected initially, or you can choose one of the supported languages directly.

A fresh install selects the virtual **Current location** place for immediate use. The bundled saved place remains a safe startup fallback until you pin another saved place. Rain Alarm asks once for foreground location permission; until it has a fresh fix, Now and Radar report location as unavailable instead of using placeholder coordinates or silently forecasting for London. An approximate fix remains usable and is honestly labelled provisional, but Travel requires Android's **Precise location** choice.

On Android 13 and later, a notification-permission request follows the location request when needed. Declining either permission does not create repeated dialogs. Retry Current from the in-place place switcher, or retry notification permission from Settings.

Upgrades preserve saved places and explicit settings. A legacy Current-location startup choice is migrated to a safe saved fallback without changing the active selection.

### Feature tour

After the fresh-install language and permission choices, a short contextual tour highlights four real controls on Now and three on Radar. Its cards use the measured bounds of those controls, can be skipped at any point and do not recur after completion or skip. **Show app tour** in Settings replays it.

During the tour, Now and Radar show a localized **Example** presentation generated entirely in memory. Light rain approaches from the southwest and an organic radar field moves northeast through a finite timeline. The example never replaces caches, saved places, current location, provider or layer choices, notification state, or the real Radar playhead. Dismissing the tour restores the retained live presentation without a data reload. See [Feature tour behavior and isolation](FEATURE_TOUR.md).

## Defaults

Unset settings use these defaults:

- Rain Notification is on when permission allows.
- Lightning activity notification is off.
- All five Now weather indicators are visible.
- Radar playback speed is 2×, but playback starts paused.
- MeteoGroup is the pinned preferred radar provider.
- EUMETSAT individual flashes (experimental) is the preferred Radar Lightning visual; accumulated
  flash areas remain selectable.
- App and map appearance are Dark; automatic day/night mode is off.
- Coverage mask darkness is 50%.
- Wind, Lightning and Clouds layers are all off.
- RainViewer **Show likely snow** is off and is shown only while RainViewer is explicitly preferred.

At the default mask setting, effective opacity is 45% on Dark maps, 42% on Slate and 40% on Light. At the maximum it is 90%, 84% and 80% respectively; **No mask** is fully transparent. The control changes only the unknown or outside-coverage scrim for every provider and retains map context below it.

Places keeps **Selected now** and **Opens on startup** as separate choices. Settings keeps the installed version and source-code link visible; the longer **About the data** sections stay collapsed until requested.

## Now

### Header and refresh

The selected place is centred above a compact, non-scrolling compass/status card. The place-switch icon opens an anchored list of Current location and saved places, without leaving Now. Choosing an item changes the active place but not the startup pin.

The card reports rain status or estimated arrival, provider/source age and forecast coverage. Refresh shows progress and then an updated or error result; when possible, a valid same-place forecast stays visible during the attempt. Returning from another tab makes one fresh forecast and point-weather attempt, even after a quick return. Startup and repeated taps do not double-load, and forecast updates during that visit do not restart the entry animation.

### Rain compass

The compass keeps geographic north up and uses 24sp N/E/S/W labels. Its precipitation direction is an outlined circular droplet path with a 90-degree outward tip and translucent halo. Rain uses the supplied translucent droplet texture on the pointer and centre disc. RainViewer likely snow uses a transparent snowflake or crystal texture over the matching icy severity colour. Dry forecasts use neither texture.

Pointer and centre disc share a colour based on the strongest available forecast-envelope severity, even when direction is unavailable and only the disc can be shown. Direction is never invented. The centre shows **Now**, minutes until arrival with `min`, or a fresh model temperature when no incoming precipitation is predicted.

The shared rain or motion description at the bottom of the card uses the enlarged text while keeping the card height fixed. Long localized text is measured and reduced only as far as necessary. The centre text is opaque white with a subtle dark edge for contrast on pale precipitation colours; the compact settled disc targets 48sp for its main label. Dry temperature uses a plain branded-blue disc.

On entry or a place change, pointer travel, centre text and disc shrink, and chart reveal share one one-second animation. A background refresh does not replay it. Disabling Android animations makes the effect immediate.

### Next-hour chart

The one-minute intensity series reaches **up to** the next 60 minutes and never extends beyond actual source coverage. Three full-height Light, Medium and Severe bands sit behind an average curve and a minimum/maximum envelope. The baseline is the wet threshold, not raw zero intensity.

The vertical Now axis aligns with the bands' left edge. Available forecast fills the plot through its real final minute, without an artificial `+60` label or blank tail. Time ticks mark local ten-minute clock boundaries within that domain.

Tap the plot, rather than its severity labels, to open Radar paused at the corresponding continuous chart time. If the retained Radar session cannot represent that time, Radar explains this and uses its normal entry time.

### Weather details

The lower corners show local 24-hour sunrise and sunset. A compact two-column readout can show selected-place Open-Meteo model **Temperature, Pressure, Humidity, UV and Wind**, where Wind contains speed and meteorological *from* direction. All five are initially visible and can be hidden independently in Settings. Stale or missing facts remain unavailable rather than being guessed.

## Radar

### Map, camera and coverage

Radar uses a north-up MapLibre/OpenFreeMap map centred initially around the selected place at roughly a 20-mile horizontal span. You can pan and zoom beyond the source radar's native pixel resolution, but this only overscales the existing data; it does not create new meteorological detail.

The map remembers camera pan and zoom across tabs and sessions. A genuine entry uses a restrained one-second focus: marker and title settle while a slightly wider view eases into the exact remembered camera. It does not replay because data refreshed or the UI recomposed, and it is immediate when Android animations are disabled. A new place is centred exactly while retaining the current radar zoom. A place confirmed after Radar long-press gives its new marker an oversized-to-normal emphasis without replaying the zoom or title transition.

Refresh and same-place session replacement preserve exact pan and zoom. The title matches Now's size and position. The center-on-selected control preserves zoom and selection, and the nearby place switcher changes the active place without leaving Radar.

A Radar session caches its complete regional imagery while retained. The regional tier stays installed at every zoom instead of being replaced by the selected-point analysis tile. Pan and zoom therefore do not discard sibling segments or download new regional crops.

Coverage masks distinguish known radar reach from unknown space. All five MeteoGroup feeds use versioned, offline-generated nominal range envelopes clipped to the native source footprint. OPERA uses the latest composite's no-data field inside its fixed European raster, and RainViewer uses its published coverage mask. The common Settings slider changes only the scrim; it does not reload weather, reset the camera or claim live availability. Masks fail open if they cannot be represented safely. Exact mask provenance and limitations are documented in [Data sources and constraints](DATA_SOURCES.md).

### Timeline and playback

The map blends neighbouring observation and provider-forecast frames continuously using symmetric velocity warping and hardware-linear sampling. The colour transfer is a translucent white/cyan/blue-to-deep-blue presentation designed for the map; it does not synthesize higher-resolution rain.

The continuous slider scrubs without snapping. Clock-aligned `:00`, `:15`, `:30` and `:45` ticks use the same anchor for mark and label; narrow layouts omit colliding alternate labels instead of shifting labels away from their ticks. Playback loops at 0.5×, 1×, 2× or 4× and starts paused. A same-place refresh preserves whether playback was running. Tapping the Now graph opens Radar at that selected time and holds the intent through loading or provider promotion until compatible coverage is ready. Direct scrubbing or opening a time selected on Now deliberately pauses playback; Travel is the explicit way to take current-time ownership back.

The regional GLES renderer uploads radar and velocity data lazily, keeping at most three frame pairs resident, and has a visible static fallback if the renderer fails.

### Travel mode

The Travel arrow is always available. From a saved place, pressing it atomically selects virtual Current location, obtains or reuses a precise foreground fix, recentres and starts Travel without changing the startup place. A label in the bottom information rail confirms **Travel mode** for two seconds. Pressing the arrow again reasserts Travel, recentres to the latest accepted fix and restores automatic current-time control.

One foreground location engine uses Google Play services fused high-accuracy fixes where available,
with Android GPS fallback. Ordinary Current uses a battery-conscious request profile; Travel asks
Android for more frequent precise foreground fixes. Actual delivery cadence and visible smoothness
are controlled by Android, the phone and its GNSS hardware, and can look roughly one update per
second on some devices. A tightly bounded presentation-only tracker may smooth movement between
trustworthy fixes, but it is best effort and freezes when motion becomes stale or uncertain.

A genuine finger pan or pinch suspends camera follow immediately while Travel time automation,
location acquisition and weather updates continue. Entry focus, recentering, accepted fixes,
map-style or provider refreshes, navigation and other programmatic camera movement do not count as
user gestures. A transient missing, stale or weak fix leaves the user's Travel intent active and
shows the existing locating state so it can resume automatically. Pressing Travel or recenter again
uses the latest accepted cached fix and resumes follow; it never jumps back to an older position.
Approximate or weak fixes never masquerade as navigation-quality data. Every accepted fix may move
the marker and camera, but weather analysis and reverse geocoding move only at a bounded anchor: at
least 1 km, or after two minutes with at least 250 m, plus an immediate first fix and regional-boundary
correction. This avoids weather requests for every location update.

Presentation-only movement never changes the selected place, radar/weather acquisition point,
saved places, notifications or alert radius. Those continue to use accepted Android location fixes
only.

Every Travel entry starts paused in **AUTO** time: **Forecast · HH:mm** exactly matches the device
clock in its current local time zone, the thumb represents that same instant, and an inline
**AUTO** label travels with its filled timeline position. The underlying timeline and tick anchors
stay fixed; only the selected current-time instant advances, and colliding ordinary marks are
suppressed rather than shifted. Radar renders the provider forecast/estimate valid for that instant.
Provider frames may bracket or interpolate that exact time internally; their timestamps never
replace the displayed clock time. A provider publication replaces the session without drift or a
jump back to the latest observation. Scrubbing takes manual timeline control. Pressing Play first
hands off from the exact AUTO instant and then advances normally, so playback does not jump to an
older retained cursor. Neither action turns off location-follow Travel; pressing Travel again
recentres and restores AUTO. If a provider cannot currently cover wall-clock now, Radar clamps only
to its nearest honest usable data and reports that current Radar time is unavailable rather than
labelling a stale observation as current.

Travel is not persisted. It keeps the screen on only while Radar is visible, Current is selected, Travel is active and the activity is foreground. It adds no background-location request, foreground service, wake lock or ongoing location notification. Pending or failed fixes remain on Radar in the compact shared status queue.

### Automatic updates in Travel

While visible Travel is active, a cadence-aware coordinator checks each source near its expected next publication instead of using unrelated fixed timers. Radar cadence is inferred from recent observations with provider-specific fallback. EUMETSAT Clouds and the chosen Lightning visual use their source cadence; Wind and point weather align to 15-minute model steps.

Checks wait a 45-second publication grace. Unchanged or failed results retry after one minute, two minutes and then at most five minutes, with one request in flight per stream. Only enabled map layers poll. A same-place automatic radar refresh keeps active playback running through the handoff.

Manual Refresh uses the same playback-preserving handoff while force-checking Radar/Now, point weather and enabled layers, and it rebases the schedule. Leaving Radar, stopping Travel, selecting a saved place or backgrounding the app pauses automatic checks while preserving visible data and caches.

### Loading, refresh and messages

On initial loading, the real interactive basemap appears as soon as a selected coordinate exists, with a stable timeline footprint and compact bottom-right `Radar loading` progress. Imagery from a previous provider is not exposed. During a same-place replacement, an existing coherent map and radar session remain visible.

Loads have finite provider-aware limits. A map session has a 110-second total ceiling and point analysis an 80-second ceiling. Within that budget, MeteoGroup, OPERA and RainViewer receive bounded attempts rather than multiplied unlimited timeouts. Explicit OPERA receives a real 70-second map and 45-second analysis attempt; OPERA after failed MeteoGroup receives the bounded time remaining. Now has a 95-second overall ceiling.

A timeout is shown as `Radar unavailable`, never as a dry forecast. Refresh retries it. Radar Refresh creates fresh generations for the active radar session and every enabled ancillary layer while retaining the camera. Only the latest generation may publish a result; late cancellation or failure from a replaced request cannot overwrite a newer success.

One bottom-right status queue is authoritative, ordered Location, Radar, Wind, Clouds and Lightning. Public wording is limited to `<Data> loading`, with `n/N` during transfer where useful, `<Data> preparing` while a complete transfer is decoded and committed, or `<Data> unavailable`. Internal validation, rendering, source and freshness diagnostics are not shown. A complete semantically usable old session stays visible during replacement and through a failed retry; unavailable is reserved for having no usable session. The bottom-left information rail is reserved for map, style, renderer and chart notices. The current-location control immediately changes a location failure back to loading while retrying.

### Wind, Lightning and Clouds

Three connected 48dp segments on a second toolbar row toggle **Wind**, **Lightning** and **Clouds** independently. Any combination, including all three, can be active and each choice persists.

#### Wind

After the camera settles, Wind requests one bounded Open-Meteo 15-minute model series for a 5×5 grid over the visible viewport. The data follows the Radar cursor without another network request per frame. The visible nine arrows form a 3×3 grid at the quarter, centre and three-quarter positions; one off-screen sample row and column on each side provide coverage. Fifteen-minute wind is native where supported and interpolated from hourly model values elsewhere.

The queue remains at `Wind loading` while MapLibre establishes the viewport and during weak-network recovery. Retryable quick failures retry at roughly 15, 30 and 45 seconds under a 55-second ceiling. An early non-retryable error is recorded internally but does not shorten that presentation grace. Only the latest stable-viewport generation can publish, and a safe same-viewport grid remains visible during replacement.

Settings includes a persistent Wind-only arrow-size slider with live preview whenever Wind is enabled. Arrows are intentionally unlabeled. The separately fetched selected-place wind speed and meteorological *from* direction stay right-aligned beneath the segments, including while the grid loads.

#### Lightning and Clouds

**Preferred Lightning Provider** in Settings changes only the Radar visual. The experimental default
shows EUMETSAT satellite-observed individual total-lightning flash centroids. Fresh flashes have a
bright white centre and yellow glow; as they age, they soften and deepen through amber, orange and
red before disappearing at 20 minutes. They enter the view in UTC-aligned 2.5-minute cohorts, then
age continuously. A centroid stays at its observed coordinate: it is not advected with rain and is
not guaranteed to be a ground strike.

The alternative accumulated-flash-area view shows EUMETSAT optical flash **areas** on its
advertised five-minute WMS frames. App and widget Lightning detection always uses these accumulated
areas, regardless of the Radar visual selected here.

Clouds chooses MTG Cloud Type RGB during daylight and Fog / Low Clouds RGB at night, using a
deterministic coordinate/time fallback when daylight facts are stale or missing. The satellite
image reveals cloud structures or fog/low cloud; it does not prove fog at the surface.

Lightning normally follows its persistent Radar toggle. Opening a rain/lightning notification or a
widget that currently reports nearby activity gives the control a distinct temporary clock state for
ten minutes at that exact place. The bottom information rail says **Lightning shown temporarily ·
tap to keep on** for two seconds. Navigation and backgrounding do not cancel the timer. Tapping the
temporary control makes Lightning persistently on; tapping a persistent on state turns it off.

Within the observed Radar window, accumulated areas advance on their advertised five-minute cadence
and Clouds on ten minutes. Individual flashes reconstruct the history from exact observation times;
after the latest completed feed frame, known flashes continue to age and disappear, but no future
flash is invented. Clouds and accumulated areas hold their latest real observation through forecast
time rather than inventing satellite forecasts.

The individual-flash feed contains 18 five-minute transport frames covering 90 minutes and is
requested for the selected place's fixed `uk`, `de`, `nl`, `ch` or `fr` radar region. Panning and
zooming do not change or reload that region. Valid-empty data is ready with no points; partial,
unknown, stale or malformed data is never presented as clear. Conditional requests and a verified
last-good generation avoid needless downloads and blank replacement frames.

A WMS satellite layer downloads and validates its complete finite set of unique observed regional
PNG frames before displaying that set. The `n/N` counter counts provider-independent unique
satellite frames. `N` may differ by radar provider because observed Radar windows have different
start times and lengths, not because Clouds fetches a duplicate provider-specific set. Clouds and
accumulated Lightning complete independently, with two requests shared concurrently and a hard
maximum of three. A complete old set remains visible while replacement loads.

Exact WMS product/time/region images reuse an atomic 128 MiB compressed-image LRU cache. MapLibre
has a separate 64 MiB base-map ambient cache, for an intended total disk budget of about 192 MiB.
Only an active bitmap, pending replacement and brief retiring source are decoded at once. Frame
changes overlap until MapLibre confirms the new image was composed. Changing Clouds does not rebuild
Lightning or vice versa, and the alternate cloud product is tried if the preferred product fails.

WMS satellite frames use a place-owned operating region: British Isles first, then Europe,
overlapping North America East/West regions, then a bounded local fallback. Each adds about 200 km
around its inner selection footprint and stays fixed as the map pans. EUMETSAT bounds are
intersected with that outer footprint. Frames are transparent, aspect-preserving regional WMS
images no larger than 1024px, georeferenced consistently at every map zoom. A world view never
requests a global satellite disk; close zoom deliberately overscales the fixed regional image.
Panning outside the region reveals no satellite pixels rather than fetching a new region.

Cloud Refresh bypasses the five-minute EUMETSAT metadata/probe cache and discovers both daytime and nighttime products again, but it does not purge valid downloaded frames. Each missing, corrupt or evicted image gets up to three attempts with a five-second connection and twenty-second read timeout. Clouds accepts provider metadata up to one hour old and performs no acquisition while disabled.

Expired Wind, Clouds or Lightning data starts one automatic replacement per source identity. The queue uses loading/preparing while the active replacement progresses. A usable last-good layer stays rendered after a replacement failure; unavailable appears only when no usable layer exists. It never exposes provider or freshness jargon.

## Places

Places owns location management. Search is followed by matching full-width **Choose on map** and **Use current location** controls. When Current has an available fix that is not already saved, a separate **Save current location** action appears.

The manually ordered saved list keeps **Selected now**, which drives Now and Radar immediately, separate from **Opens on startup**, which controls cold start. Each row has a reorder handle followed by direct startup-pin, Edit and Delete actions. Deletion happens immediately; if persistence fails the row is restored with a concise error. Current location is virtual and cannot be pinned for startup. Deleting the startup place chooses a safe saved fallback without changing the active selection.

**Choose on map** opens a temporary full-screen attributed base-map picker with a centred crosshair, editable suggested name and safe Cancel/Confirm actions. Radar long-press uses the same naming and save rules. Near-identical saved coordinates are rejected. Current uses the platform locality name when one is available and otherwise a one-decimal latitude/longitude label. Live coordinates remain in process memory unless you save them explicitly.

Now and Radar's header switcher opens an anchored list of Current and saved places. Selecting there changes only the active place, not the startup choice.

### Search

One explicit search sends ordinary text to Open-Meteo/GeoNames for settlements and Photon for OpenStreetMap named places and points of interest, then ranks and deduplicates their independent results. If neither service returns a strong name match, one bounded English Wikipedia/Wikimedia coordinates search can supplement notable places. Straight and curly possessives use the same normalized query. A complete UK postcode uses postcodes.io instead. Failure of one service does not discard useful results from another.

Search results include locality, region, country and postcode context supplied by the source. More network and licensing detail is in [Privacy](PRIVACY.md#place-search) and [Data sources](DATA_SOURCES.md#place-search).

## Radar providers

Settings uses a filled pin to mark the global preferred provider. The pin is a preference, not a promise that the provider covers every saved or live location. Each session tries the preferred provider first when geographically eligible and then other eligible providers in regional MeteoGroup, European OPERA and worldwide RainViewer quality order.

Fallback never moves the pin. Manual Refresh retries the preference. A concise temporary message beside Radar's information icon reports a genuine location/provider fallback or no-coverage result once, without adding a permanent provider badge. Temporary network, publication or decode failure remains distinct from geographic capability, and an OPERA no-data gap does not shrink its fixed domain or make provider selection flap. Rapid pin changes are coalesced for 400 ms before persistence and selected-place analysis so the last deliberate choice wins.

### MeteoGroup/DTN

MeteoGroup is initially preferred where one of its five regional feeds covers the selected coordinate. It supplies observations, velocity textures and provider forecast frames. For regional Now and alerts, a separate location-selected **area profile** provides average/minimum/maximum intensity when fresh; it is not exact pin-pixel truth. The raster point series is the fallback.

### Open European / OPERA

OPERA is a credential-free EUMETNET composite over a fixed 3,800×4,400 km CIRRUS/LAEA domain, independent of MeteoGroup's rectangles. Rain Alarm reads only required Cloud Optimized GeoTIFF blocks from the public 24-hour cache, preserves the source's ellipsoidal LAEA geometry and native 1 km data, and derives regional map and selected-place detail from the same observations.

OPERA has observations but no provider forecast. Future `Estimate` frames, Now and alerts use confidence-gated local motion and stay unavailable if evidence is insufficient.

### RainViewer

RainViewer RGB is decoded from its Universal Blue reflectivity colours into Rain Alarm's common intensity palette. PNG alpha means transparency, not rain strength or proof of coverage. The regional tier aligns with a MeteoGroup area footprint where available and otherwise remains centred on the selected place; the detail tier supports Now and alerts without replacing the regional map when zoomed in.

RainViewer's published coverage mask is requested once per session using the same screen raster plan, or the detail plan for point analysis. A fresh tile with no above-threshold echoes can be reported as a clear next hour without inventing direction only when the mask confirms both the selected point and substantially the retained tile are covered. Missing, stale or invalid coverage stays unknown and fails open visually.

When RainViewer is explicitly preferred, **Show likely snow** requests model-assisted snow colours and uses an icy lavender-to-indigo palette across Radar, Now, the graph and alert wording. It remains off when RainViewer is only a fallback and is never claimed for OPERA.

RainViewer's future path prefers a confidence-gated local motion field and may use a separately confidence-gated broad translation when local motion is unavailable. Both are estimates. They stop at the downloaded coverage edge rather than treating unseen space as dry.

Providers are independent composites and can disagree materially about footprint, intensity and timing. Rain Alarm calibrates presentation but never expands, shrinks or moves one provider's echoes to imitate another. See [Data sources and constraints](DATA_SOURCES.md) for the canonical provider, coverage, attribution and processing details.

## Rain notifications

Rain Notification applies to the app's **active selected place**, whether saved or live, and to any
configured widget subscriptions. The app-selected-place alert keeps its established immediate
approaching-rain behaviour and cooldown/event deduplication; it does not require a prior dry poll.
Each widget subscription is stricter: a successful complete, dry 0–60 minute evaluation arms it,
the first later approaching episode sends one notification and disarms it, and an already-wet
result consumes that widget eligibility without sending a new approaching alert. Partial, stale or
unavailable data is never treated as dry and cannot arm or re-arm a widget alert.

The title contains the place and the expected **Light**, **Medium** or **Severe** rain (or likely
snow) classification. It uses the strongest normalized Now-graph sample inside that one continuous
event; a separate later event after a dry gap cannot inflate the title. The body keeps the localized
arrival estimate. Duration and peak are included only when a complete episode ends within known
coverage. WorkManager checks approximately every 15 minutes; Android may defer it under Doze,
battery restrictions or force-stop. Saved-place alerts do not need location permission. A
Current-location background check is skipped unless Rain Alarm already has a sufficiently fresh
lawful foreground fix.

App and widget subscriptions share one event model. A successful app rain or Lightning delivery
suppresses the matching component from widgets at the same normalized coordinate, and a successful
widget delivery suppresses that component from the app. Sibling widgets remain independent, and a
rain claim never suppresses a later eligible Lightning component or vice versa.

### Lightning activity notifications

**Lightning activity notification**, directly below Rain Notification in Settings, is independent
of the persistent Radar Lightning layer and is off by default. When enabled, Rain Alarm checks new
EUMETSAT five-minute accumulated optical flash-area observations inside a true 15 km circle around
the same frozen target used by that monitoring pass. This is observed activity, not a strike
forecast, and the source cannot distinguish intracloud from cloud-to-ground lightning.

A first valid nearby detection after enabling the switch is immediately eligible; no earlier clear
poll is required. Continuing detections in that episode do not repeat, unavailable data does not
prove clear or consume eligibility, and a later successful no-detection result re-arms the next
episode. Rain and lightning that newly trigger together share one notification. Following a
lightning notification opens the exact monitored place in Radar with the
ten-minute temporary Lightning state described above, without changing the startup pin, widget
location or persistent Radar layer choice.

## Home-screen widgets

Add **Rain Alarm** from the Android launcher's widget picker. The same resizable widget supports a
compact 1×1 view, intermediate widths and a canonical 4×1 view. It is a self-updating static
snapshot, not an animated miniature Radar screen, and Android schedules background updates
inexactly at approximately 15-minute intervals.

Every widget has its own configuration:

- choose one saved place;
- adjust only the card background opacity from transparent to opaque;
- leave **Widget notifications** on by default or disable notifications for that widget;
- optionally enable **Include lightning** (off by default); and
- optionally set per-widget Do Not Disturb start/end times.

These controls belong only to that widget. They do not change the app's Rain notification or
Lightning activity notification switches, another widget, or whether wider widgets may display
fresh Lightning context. Widgets are always rain-first; old primary-content choices are retained
only for migration and are no longer configuration options.

After configuration, the widget saves its target and queues its own initial update before the
configuration screen closes. It shows **Updating** until that target produces usable data, and a
transiently failed first attempt is retried automatically; adding another
widget, resizing it or reopening the app is not required.

A widget stores a self-contained snapshot of its chosen saved place and remains independent of
in-app navigation, Current location, Travel and the startup pin. It needs no device-location
permission and continues polling whether the app is open, closed or process-recreated. A rename may
update its label; deleting the source row retains the frozen widget place until you reconfigure it.
If no saved place exists, configuration links to Places and cannot finish until one is created.

The mini Compass uses the same wet threshold, peak colour, rain/likely-snow texture and centre text
as Now. It shows **Now** while wet, an integer arrival with **min** while rain approaches, and—in a
compact temperature-capable mode—a fresh temperature when available samples are dry. Wider layouts
keep a neutral dash in the dry Compass and place temperature in the status column. They add
place, maximum qualitative intensity, a stop time only when confirmed inside known coverage,
nearby-lightning state, last successful update and a compact next-hour graph. From 2×1 upward, the
same compact text facts and same-size mini Compass remain present; 4×1 adds a graph that fills the
remaining row width. Its crisp axis-free profile fills the usable plot from first sample to last
without tick labels. A partial but usable dry horizon can show the current temperature or **Dry
now**, but it is never claimed as a complete clear hour and cannot arm the widget's prior-clear
alert rule. The square-filling 1×1 Compass remains its own compact presentation. Missing direction,
provider snow classification and unknown future minutes are never invented.

Refreshing, launcher resize and one failed optional stream preserve
the last semantically current target-matching snapshot and its real **Updated** time. Countdown,
stop and intensity are re-derived from timestamped samples at display time, so a stored series
cannot freeze an old ETA after its coverage ends. **Update unavailable** appears only when no
current target-matching rain, temperature or Lightning fact remains (or no successful snapshot ever
existed); bounded storage retention is not treated as current weather.

Weather acquisition remains approximately every 15 minutes, but relative widget presentation is
updated separately. While the device screen is interactive, one shared local minute ticker
repaints affected widgets from their cached timestamped series, so **Rain in N min** counts down and
changes to **Rain now** without another network or location request. **Updated HH:mm** remains the
real acquisition time. The ticker is non-wakeup: Android may batch it while the screen is off, and
the next awake repaint derives directly from the current clock rather than replaying missed ticks.

Widget quiet hours use the device's current local time, support same-day and overnight ranges, and
suppress notification delivery only. Weather polling, cached state, widget rendering and episode
tracking continue. An event first detected during quiet hours is consumed rather than queued for a
late alert; a later successful clear/no-detection state must re-arm it. This does not change
Android's system Do Not Disturb mode.

Tapping a valid widget opens Radar for that widget's exact saved place without changing the
widget or startup pin. If nearby lightning is part of the displayed state, Radar receives the
temporary Lightning hand-off. Widget polling remains independent of app visibility; matching app
and widget work may share safe repository/cache results without making the widget depend on an app
callback.

## Appearance

App appearance offers Dark, Light and Follow system. Map appearance offers Dark, Light, Follow system and Slate. Compass and Graph cards independently offer Dark, Light, Follow app and Slate. Card Slate uses the same `#45516E` mid-dark base independently on both cards. Light map uses OpenFreeMap Liberty, Dark uses OpenFreeMap Dark and Slate uses OpenFreeMap Fiord.

Optional **Automatic day/night** mode exposes separate editable Day and Night profiles spanning app, map, Compass and Graph. Defaults are a fully light Day and fully dark Night, but each profile can mix surfaces. Rain Alarm reuses the selected place's cached sunrise and sunset facts, without another solar endpoint or request. If those facts are genuinely unavailable it falls back deterministically to system night mode.

At a solar boundary, the complete profile changes together, updates system-bar contrast and schedules the next boundary without polling. Automatic mode stays off on upgrade so existing manual choices remain unchanged.

Appearance and in-place place-focus changes retain the resolved palette continuously. MapLibre state, Radar session, camera, playback, timeline position, layers, Travel state and coverage-mask choice remain intact.

Widgets and their configuration screen use the app's currently resolved manual or automatic
day/night palette, card shapes, controls, icons, spacing and system-sans typography. There is no
separate widget theme. A widget's opacity control changes only its card fills; text, icons and
weather colours stay opaque. Appearance changes republish widgets without refetching weather.

## Navigation and accessibility

On phones, Now → Radar → Places → Settings share one retained horizontal pager. Bottom navigation and swipes update the same state without wrapping or reloading retained destination content.

Radar keeps normal one-finger pan, pinch and rotation. Only a deliberate inward drag from a narrow map-edge gutter pages away. The left gutter starts below the forecast label, the right below map controls/layers/status, and both end above the bottom information rail. These gestures move the adjacent page continuously with the finger and settle or return with the same pager motion; destination entry effects begin only after settlement. Map controls remain above gesture targets.

Timeline and chart interaction, Settings sliders, place reordering and dialogs are gesture islands. Bottom-bar taps and TalkBack previous/next actions remain available. Wider navigation-rail layouts are tap-driven.

## Languages

Rain Alarm supports:

- Device language
- English
- Nederlands
- Nederlands (België)
- Deutsch
- Français
- Cymraeg
- Gaeilge

The AndroidX-backed choice remains near the bottom of Settings and applies immediately. Once answered, the first-use language question does not recur after restart or upgrade. English is the fallback. Provider and product names, legal attribution, URLs and raw place names remain unchanged. Locale-aware plurals, clock formatting and numbers preserve the device's existing 12/24-hour choice. See [Localization](LOCALIZATION.md) for the glossary and quality gates.

## Data limits and safety

Radar and forecasts depend on third-party publication, coverage, model output and network availability. A clear presentation is reported only when coverage supports that conclusion; missing, stale or uncertain data remains unavailable where the source cannot support a dry claim. Intensity percentages are normalized reflectivity-derived display severity, not rainfall or snowfall rates in millimetres per hour.

Rain Alarm is an experimental information aid, not a safety-critical warning service. Do not use it as your only source for weather-safety decisions. Read [Data sources and constraints](DATA_SOURCES.md) and [Privacy](PRIVACY.md) for the exact provider and request behavior.
