# Android home-screen widget requirements

> **Status: planned, not implemented.** This document defines a future Android home-screen widget
> and the supporting polling/alert behaviour. It does not describe the current released app.
> Lightning observation, notification and temporary Radar-layer behaviour are specified separately
> in [Lightning activity alert requirements](LIGHTNING_ALERT_REQUIREMENTS.md).

## Objective

The Rain Alarm widget will provide an answer-first view of the same selected-place Now data without
requiring the app UI to be open. Its central visual is a compact form of the app's Compass centre
disc, using the same classification, colour, rain/snow texture and centre text for the same weather
state. Wider layouts add concise timing, intensity, lightning and next-hour detail.

From the user's perspective the widget self-updates while the app is closed. Android schedules that
work inexactly; it is not a continuously running service or a promise of exact update times.

## Form factors and interaction

- Provide a compact **1×1** widget and one horizontally resizable **N×1** widget family. **4×1** is
  the canonical full presentation.
- Use responsive size classes from the launcher's reported widget bounds rather than assuming that
  every launcher cell has the same pixel dimensions.
- Intermediate widths progressively omit secondary information in a deterministic order. They must
  not calculate a different weather answer from 1×1 or 4×1.
- Widget views are static snapshots. They do not continuously animate Radar, sweep the Now graph or
  run a countdown every second.
- Tapping a valid widget opens **Radar** for that widget's exact resolved place. It does not change
  the widget configuration or startup-pinned place.
- When the displayed widget state includes nearby lightning activity, the tap uses the temporary
  Lightning deep link in
  [Lightning activity alert requirements](LIGHTNING_ALERT_REQUIREMENTS.md#notification-tap-and-temporary-radar-state).
- A widget whose fixed place has been deleted opens reconfiguration instead of Radar.

## Per-widget configuration

Every widget instance has independent persisted configuration:

| Setting | Choices/default |
| --- | --- |
| Location | One fixed saved place, or **Follow app selection** |
| 1×1 content | **Smart** (default), Rain, Lightning or Temperature |
| Background opacity | 0–100%, defaulting to the opacity of the app's existing cards |
| Do Not Disturb | **Off** by default; when on, distinct local start and end times |

The configuration screen must show the resolved place and a live miniature widget preview. It uses
the app's currently resolved palette, card shapes, controls, iconography, spacing hierarchy and
system-sans typography. It does not have a per-widget theme selector; background opacity is the only
widget-specific visual override.

Saving a location/content change triggers an immediate network-constrained refresh. Saving only
opacity or Do Not Disturb changes republishes control/appearance state without a weather fetch,
provider change or alert re-arm. The launcher and an in-app entry point must allow an existing
widget to be reconfigured.

There is no per-widget radar-provider choice in this version. Each resolved target uses the same
preferred-provider and geographic fallback rules as Now and Radar.

### Fixed saved place

- Store the saved-place ID, not an untracked copy that silently diverges from Places.
- Normal app navigation, changing **Selected now**, changing the startup pin or entering Travel does
  not alter the widget target.
- A rename updates the widget label. A coordinate edit invalidates the old target result and alert
  identity and immediately evaluates the new coordinate.
- If the place is deleted, retain the widget instance but show **Location removed · Tap to choose**.
  Stop location-dependent network work and alerts for it until the user reconfigures the widget. Do
  not silently substitute Current, the startup place or another saved place.

### Follow app selection

- Resolve the app's active selected place at the start of each transaction. A saved selection uses
  that saved place's current coordinates.
- A later app selection change invalidates the widget's target cache and schedules one refresh; an
  older in-flight result cannot overwrite the new target.
- The widget follows **Selected now**, not the startup pin.
- Multiple Follow widgets may use different content/opacity while sharing one weather acquisition
  for the same resolved target.

### Follow app selection with Current location

Virtual **Current location** never grants the widget a background location capability. It may use
only a lawful foreground-acquired fix within the app's existing five-minute freshness limit. It
must not request a fix from WorkManager, start a foreground location service, or persist live
coordinates as though they were a saved place.

When that fix is stale, missing after process death or unavailable because permission was revoked:

- show **Open Rain Alarm to update current location**;
- pause rain and lightning alerts for this target;
- retain the last successful timestamp only as history, not as current weather; and
- never reuse the stale coordinate or fall back to a different place.

Opening the app and obtaining a new foreground fix allows the next foreground publication or
scheduled transaction to resume the widget.

## Shared weather model

The widget must consume the same normalized `RainMinuteSeries`/analysis result as Now, not maintain
a second set of weather thresholds or provider interpretations. A usable series begins at the
evaluation time, contains minute offsets from 0 through at most 60, and explicitly distinguishes
`AVAILABLE`, `PARTIAL` and `UNAVAILABLE` coverage.

For each resolved target, use the app's ordinary provider resolver:

- **MeteoGroup regional** — prefer the location-selected area rain chart when it is fresh and
  valid; use the compact regional raster point series as its existing fallback.
- **Open European / OPERA** — use the same bounded observation load and confidence-gated local or
  broad motion estimate as Now/background analysis. It has no provider forecast and no snow
  classification.
- **Open radar / RainViewer** — use decoded intensity, published coverage and the same
  confidence-gated motion path. **Likely snow** is available only when RainViewer is explicitly
  preferred and its existing model-assisted snow option is enabled.

Provider fallback remains geographic and capability-aware. Partial, uncovered, stale,
low-confidence or unavailable data is never padded with zeroes and never called dry. A provider
change invalidates incompatible widget data but should retain the last coherent presentation with
an updating label until a replacement succeeds or becomes explicitly stale.

## Rain and Now presentation

All sizes derive the following facts once from the shared series and point-weather result:

- resolved monitored place name;
- wet now, first arrival minute, first episode end (when observed within coverage), and complete
  prediction horizon;
- maximum qualitative intensity in the known 0–60 minute window using the app's existing
  **Light / Medium / Severe** chart bands;
- precipitation type at the current/onset minute, including **likely snow** only when supported;
- reliable source bearing, if one exists;
- fresh model temperature; and
- last successful update time.

The widget must not convert normalized display severity into invented millimetres per hour.

### Text rules

- If wet at minute zero, show **Rain now** or **Likely snow now** and put **Now** in the disc.
- If dry now with a known first wet minute, show **Rain in N min** or **Likely snow in N min** and
  put the integer `N` plus `min` in the disc.
- Show the maximum known qualitative intensity, not a numeric percentage presented as rainfall
  rate.
- Show a predicted stop time only when the same episode has a confirmed first dry minute inside
  actual available coverage. If the episode runs to the coverage edge, omit the stop claim.
- Show temperature as the primary dry state only when the complete 0–60 minute series is known and
  contains no rain. A partial dry-looking window is **Unknown**, not a temperature/clear state.
- `Unavailable`, incomplete/no-conclusion and stale states use concise explicit wording and an em
  dash/neutral disc. They retain a last-successful time but make no dry claim.
- The 4×1 layout always shows the place and last successful update. Smaller layouts expose the
  equivalent information in their content description when it cannot fit visually.

While a refresh is in flight, keep the last successful coherent state visible with **Updating**.
After a failed refresh, keep it only with an explicit delayed/unavailable label and timestamp; do
not run an alert decision from stale cached data. A successful replacement swaps atomically so the
widget does not flash blank.

## Mini Compass disc

The mini Compass disc is a core widget component, not a new weather icon. Implementation must
extract or reuse a shared Compass render model so Now and the widget cannot drift.

For the same `RainMinuteSeries`, weather and evaluation time, the widget disc must use the same:

- provider-specific wet threshold and `RainMinuteSeriesAnalyzer` result;
- current minute when wet, otherwise the selected first-arrival minute;
- **Now**, integer arrival plus `min`, dry temperature, or em-dash centre-text semantics;
- `NowPeakRainColorPolicy`: the strongest upper forecast-envelope severity is mapped through the
  shared chart scale to the Rain Alarm rain or snow palette;
- rain-droplet texture and opacity treatment for rain;
- icy likely-snow palette and snowflake/crystal texture for supported likely snow;
- opaque white bold centre text and contrast treatment on precipitation colours; and
- plain branded-blue dry-temperature treatment only after a complete clear hour.

Do not infer snow from temperature or radar intensity. The snow treatment and accompanying visible
text/content description must say **likely snow** and must not rely on colour alone.

At wider sizes, a compact source-direction marker or cardinal cue may accompany the disc only when
the shared series has a reliable source bearing. Missing bearing remains unavailable; it must not
be invented. The outer full-size Now compass geometry need not be squeezed into 1×1, but any cue
that is retained must preserve north-up/source-direction semantics.

### Disc state examples

| Series state | Disc fill/texture | Disc text |
| --- | --- | --- |
| Wet rain now | Shared peak rain colour + droplet texture | `Now` |
| Rain approaching | Shared peak rain colour + droplet texture | integer arrival, `min` below |
| Likely snow now/approaching | Shared snow colour + snow texture | `Now` or integer + `min` |
| Complete clear hour | App branded-blue dry disc | fresh rounded temperature, or `—` |
| Partial/unknown/unavailable | Neutral unavailable treatment | `—` |

## Next-hour graph

The canonical 4×1 presentation includes a compact next-hour Now graph built from the same minute
series as the app:

- the plot ends at the real known horizon and never pads an incomplete tail;
- Light, Medium and Severe bands use the shared chart-severity thresholds;
- the average curve and available minimum/maximum envelope preserve their existing meanings;
- the baseline is the provider wet threshold after presentation normalization, not raw zero;
- rain and likely-snow segments use the app's existing palettes;
- local clock labels respect the device's 12/24-hour choice and locale; and
- unavailable coverage is visually distinct from a zero/dry curve.

The graph is a snapshot, not an animated Radar timeline. At narrower intermediate widths, omit
labels and then the graph before removing the place, disc or primary status.

## Lightning in the widget

When a widget mode/layout requires lightning, it is evaluated in the same polling transaction and
at the exact same frozen target coordinate as rain. Detection radius, five-minute frame catch-up,
freshness, episode deduplication, wording and error semantics are defined in
[Lightning activity alert requirements](LIGHTNING_ALERT_REQUIREMENTS.md).

- Nearby detected activity appears as a distinct lightning badge/status alongside rain. It never
  hides a known wet/approaching rain state.
- If rain is clear but lightning is detected, Lightning may become the primary Smart 1×1 state.
- A widget displaying nearby lightning opens Radar with the target-bound ten-minute temporary
  Lightning state.
- No-detection and unavailable are different. Do not show an unavailable source as safely clear.
- The Lightning notification setting controls alerts, not whether the widget may display the most
  recent successfully evaluated lightning state.
- Smart and Lightning 1×1 modes, plus responsive layouts that contain the Lightning badge, create
  Lightning display demand. Rain- or Temperature-only layouts that do not present Lightning create
  no such demand. If the alert switch is also off, omit Lightning acquisition for those widgets.

## Responsive content priority

### 1×1 primary modes

The user chooses one primary mode per widget:

| Mode | Primary rule | Fallback |
| --- | --- | --- |
| **Smart** | Wet/approaching precipitation, then nearby lightning, then complete-clear temperature | Neutral em dash with unavailable/update action |
| **Rain** | Mini Compass rain/snow/clear state | Neutral em dash when partial, unknown or unavailable |
| **Lightning** | Nearby activity, successful no-detection, or unavailable | Never substitute rain/temperature for an unavailable Lightning result |
| **Temperature** | Fresh model temperature | Em dash when missing/stale; never infer it from the rain series |

In Smart mode, lightning does not replace an existing rain/snow state; it remains a secondary badge
when space permits. Accessibility text announces both facts.

### Intermediate and 4×1

Responsive layouts add content in this priority order:

1. mini Compass disc and primary rain/clear/unavailable state;
2. place name and lightning badge;
3. arrival/Now, qualitative peak and confirmed stop detail;
4. last successful update; and
5. compact next-hour graph and reliable direction cue.

The canonical 4×1 layout includes all five groups. Text may shorten according to localized width,
but the underlying state and accessibility description remain identical.

## Alerts and notification state

Adding a widget does not silently enable either global notification switch. **Rain notification**
makes configured widget targets eligible for rain alerts; **Lightning activity notification** does
the same independently for lightning alerts. A widget may still request Lightning for display while
that alert switch is off, but its result cannot notify. Instances that resolve to the same
target/provider/configuration share one eligible decision and one notification. Different fixed
targets retain independent state.

### Rain episode arming

Rain episode evidence and base arming are per target and independent of Lightning. Delivery
eligibility and consumption are additionally per monitoring subscription:

| Evaluation | Alert effect |
| --- | --- |
| Complete 0–60 minute clear window | Arm/re-arm rain. |
| Approaching while armed and dry now | Notify once, then disarm. |
| Approaching while disarmed | Do not repeat. |
| Wet now | Do not issue a new approaching alert; mark the episode active/disarmed. |
| Partial, unknown, unavailable or stale | Do not notify, arm or re-arm. |

This deliberately requires at least one earlier successful complete-window dry evaluation before
an approaching-rain notification. A fresh install or new target that is already wet/approaching
establishes state without immediately alerting. A later complete clear hour re-arms the next
episode.

Persist the armed state, event identity and last successfully evaluated series identity once per
target. Persist each monitoring subscription's last considered/consumed event and delivery policy
separately. Worker, widget and foreground app paths must call one atomic decision engine so
concurrent checks cannot duplicate an alert.

Lightning has its own no-detection/detected arming state. If both independently trigger during the
same transaction, send one combined notification. Lightning may notify alone, or later after rain
was already notified, as specified in
[Rain interaction and notification content](LIGHTNING_ALERT_REQUIREMENTS.md#rain-interaction-and-notification-content).

### Per-widget Do Not Disturb

Every widget is a separate **monitoring subscription** with its own Do Not Disturb (DND), also
described in the UI as **Quiet hours**. DND controls notification delivery arising from that widget
subscription. It does not change Android's system Do Not Disturb, the global Rain/Lightning
notification switches, an unrelated widget, or the app's separate alert subscription.

#### Configuration and time rules

- The DND switch defaults to **off** for every new widget.
- Start and end controls are shown and enabled only while DND is on. Turning DND off retains the
  last valid pair for reuse but ignores it for delivery.
- Use localized Android time pickers and the device's current 12/24-hour convention. The compact
  configuration/preview summary is equivalent to **Do not disturb · 22:00–07:00**.
- Store start and end as recurring local wall-clock times. Evaluate them in the device's **current**
  time zone because quiet hours protect the user, not the monitored weather location. A time-zone
  change takes effect immediately without rewriting the stored clock values.
- When start is before end, the quiet interval is the ordinary same-day range. When start is after
  end, it crosses midnight. Start is inclusive and end is exclusive in both forms.
- Equal start and end is invalid, not an all-day interval. Saving remains unavailable and a concise
  validation message explains that the times must differ. Users can turn the global notification
  switches off when they want alerts disabled entirely.
- Determine membership from the current instant converted with Android's current local time-zone
  rules. A spring-forward gap contains no nonexistent clock times; in a fall-back overlap, both
  occurrences of a repeated quiet wall-clock time remain quiet. Do not schedule exact alarms for
  either boundary.

#### Suppression and episode state

DND suppresses **notification delivery only**. During quiet hours all of the following continue:

- normal WorkManager/foreground polling;
- shared rain and Lightning acquisition;
- cached-state replacement and widget rendering;
- rain/Lightning evidence and episode processing; and
- the visible mini Compass, graph, timestamp and lightning state.

The widget must not dim, freeze or show a “paused” weather state merely because DND is active.

When an armed rain or Lightning event is first detected during a subscription's quiet interval,
mark that event consumed/disarmed for that subscription and send nothing. Do not queue a pending
notification. Continued rain or lightning after quiet hours end must not replay or retroactively
alert. A later successful complete-clear rain window or successful Lightning no-detection result
re-arms the corresponding future episode under its existing rules. Unknown/unavailable data still
does not re-arm.

Manual refresh and foreground evaluation apply the same quiet-hours decision while continuing to
refresh widget data. Editing DND during an active/consumed episode does not make that episode new,
re-arm it or trigger a catch-up notification.

#### Shared targets and delivery deduplication

Acquisition and target-level evidence remain coalesced by frozen target/provider/configuration.
Notification eligibility and consumed state must additionally be tracked per monitoring
subscription so widgets sharing one target can have different quiet hours.

For one target/event:

1. evaluate weather evidence once;
2. apply the global Rain/Lightning notification switch;
3. evaluate each eligible subscription's DND at the decision instant;
4. mark quiet subscriptions consumed without delivery; and
5. emit at most one notification if at least one eligible subscription is outside its quiet window.

Every participating subscription is then consumed for that event, including quiet subscriptions,
so none can replay it later. If all are quiet, emit nothing. A separate app alert subscription or
another widget outside quiet hours may still allow the single notification; one widget's DND does
not silence unrelated subscriptions.

DND settings belong to the widget ID. Reconfiguration carries the new values with that instance;
deleting the widget removes its DND/delivery state without deleting shared target evidence still
used elsewhere.

## Polling and foreground coordination

### Background scheduling

- Use one network-constrained WorkManager coordinator with an approximately 15-minute periodic
  request. Fifteen minutes is Android's periodic minimum; actual execution is inexact and can be
  deferred by Doze, battery optimization, standby and network availability.
- Enqueue an immediate constrained refresh after initial widget configuration, relevant
  reconfiguration, provider change, resolved Follow-target change, and an explicit matching app
  refresh.
- Do not promise a next check at an exact clock time. Force-stop prevents work until Android/the
  user starts the app again; some launchers/OEM battery controls may delay updates further.
- Coalesce widget instances and app alerts by frozen target, resolved provider/configuration and
  source identity. Rain and Lightning share one transaction when Lightning is required by an
  enabled alert or a widget presentation; otherwise omit Lightning work.
- Avoid overlapping work. Each target permits one active generation; a newer generation supersedes
  publication from an older one.
- Preserve the last successful widget state while loading. Publish weather data and alert memory
  atomically only after the transaction validates that its target is still current.
- Whenever Lightning is requested, catch up every unseen observation as its separate requirements
  demand. Rain uses one coherent current 0–60 series rather than replaying stale forecasts.
- If no widgets exist and both notification switches are off, cancel unnecessary periodic weather
  work. Existing app foreground refresh remains unaffected.

### While the app is visible

- “App open” means its activity is visible in the foreground, not merely in Recents or resident in
  memory.
- While visible, scheduled widget/background acquisition exits without starting redundant network
  work.
- Foreground app refreshes publish through the same cache to widgets that resolve to the same
  target/provider. Lightning runs only when an enabled alert or matching widget display requires
  it, and enters the alert decision engine only when its notification switch is on. It does not
  create a second notification.
- A widget targeting a different fixed place retains its cached state while the app is visible; it
  is not secretly polled by a second background path.
- When the app leaves the foreground, scheduled coordination resumes. It preserves cadence,
  checkpoints and arming state and may enqueue one due catch-up check, but never resets state or
  emits a duplicate alert merely because lifecycle changed.

## Appearance and typography

### Palette and day/night behaviour

- Both the widget and its configuration/settings UI follow the app's **currently resolved** manual
  or Automatic day/night profile. They do not independently choose a theme from the widget target.
- Use the resolved app palette for the widget container, Compass palette for the mini disc context,
  and Graph palette for the next-hour graph. Slate, Light and Dark keep their existing colour
  definitions; the widget must not invent a parallel theme.
- Reuse the app's rounded-card shapes, switch and slider styling, iconography, accent treatment and
  spacing hierarchy in both the live widget and configuration surface, adapted only where launcher
  bounds require responsive omission.
- There is no per-widget theme selector. Per-widget background opacity is the only widget-specific
  visual override.
- A settings/profile change republishes affected widgets and the configuration preview without
  waiting for the next weather poll. Appearance-only changes reuse cached weather and do not reset
  alerts.

### Per-widget background opacity

- Provide a per-widget slider covering **fully transparent (0%)** through **fully opaque (100%)**.
- The default equals the current app-card treatment (currently fully opaque).
- Show a live miniature preview containing representative disc, text and background colours in the
  same currently resolved app palette. Show **Do not disturb · Off** when disabled or the concise
  localized hours when enabled, without dimming the preview or implying that updates stop.
- Apply opacity only to widget/card container fills. Text, icons, outlines, weather data,
  rain/snow textures, intensity colours and the lightning indicator remain fully opaque.
- Internal layout must not add an opaque slab that defeats a transparent outer background.
- Store opacity as a stable percentage per widget instance. Reconfiguration republishes locally and
  must not trigger a weather fetch or alert-state change.
- Respect launcher-provided bounds, clipping and corner-radius/background rules. A launcher may add
  its own host treatment; Rain Alarm must not claim pixel-identical output across launchers.

### Font

The app currently uses Android's system `sans`/Material typography and bundles no custom font. The
widget must explicitly use the same system sans-serif family (normally Roboto on standard Android)
and the same Normal, SemiBold and Bold hierarchy/proportions as the corresponding app text. Do not
bundle or substitute a new widget-only font.

Use scalable `sp` sizes and fit/omit secondary text at small bounds rather than compressing the
primary label into an unrelated type scale. Disc centre text remains bold; place and primary status
follow the app's existing emphasis; timestamps and source/status detail use its muted hierarchy.

## Accessibility and localization

- The whole weather surface is a clear tap target. Any separate reconfigure action must have an
  unambiguous label and must not overlap the primary Radar action.
- Expose one concise content description containing place, rain/snow status, arrival, intensity,
  confirmed stop (if known), temperature (when applicable), lightning state, availability and last
  update. Do not force TalkBack to traverse decorative graph points.
- Rain, likely snow, lightning, unavailable and stale states must differ by words/iconography as
  well as colour or texture.
- Honour Android font scale, high-contrast expectations and localized expansion. At large font
  scales omit graph/secondary facts before clipping the disc's primary meaning.
- Localize all widget configuration, status, notification and accessibility strings into the app's
  supported languages. Preserve locale-aware plurals, decimal formatting, units and the device's
  12/24-hour clock choice.
- Give the DND switch, start/end time controls, validation and summary explicit TalkBack labels;
  announce the localized range in reading order without relying on colour.
- Widget refresh and configuration controls must be keyboard/switch-access reachable where the
  launcher supports them.

## Privacy, network and resource limits

- The widget adds no account, advertising, analytics, application server or provider credentials.
- Weather requests send the resolved target coordinate directly to the providers already described
  in [Privacy](PRIVACY.md). Lightning sends a bounded local image request to EUMETSAT as described
  in its separate requirements.
- Update the privacy disclosure before release to describe widgets, per-widget saved IDs/config,
  background network checks, cached display state and alert memory.
- Persist fixed saved-place IDs, widget choices, opacity, normalized display results, timestamps
  and alert state. Do not persist a virtual Current coordinate merely to keep a widget working.
- Reuse the existing bounded point-analysis and HTTP/disk caches. Do not download full map imagery
  or decode regional animations for a widget.
- Place a strict bound on stored widget snapshots and remove orphaned configuration when Android
  deletes an instance. Shared per-target alert state is removed only after no widget/app alert
  target references it.
- Network/battery work scales with unique targets, not widget count. Backoff transient failures and
  avoid rapid manual-refresh loops.

## Lifecycle and failure behaviour

- Persist widget configuration and last successful normalized presentation by Android widget ID.
  Recreate views after launcher/process recreation and application upgrade without waiting on a
  blank network load.
- Ensure periodic work is present when the first widget or alert is enabled and reconcile it on app
  startup/update. Remove it when no widgets or enabled alerts require it.
- Process death, reboot or app update must not re-arm rain/lightning episodes or duplicate a prior
  notification. WorkManager timing remains platform-controlled.
- Loading keeps the last coherent state with **Updating**. Network, provider, decode and incomplete
  coverage failures produce **Update unavailable**, not dry.
- If no successful state exists, show a neutral disc, place (when resolvable), concise unavailable
  text and tap path to Radar or reconfiguration as appropriate.
- If provider eligibility changes for a Follow target, use normal fallback resolution. Do not move
  the configured location to make a preferred provider work.

## Acceptance criteria

Automated tests must cover at least:

- independent configurations for two widget IDs, fixed versus Follow targets, rename/edit/delete
  handling and stale Current location;
- provider resolution and identical normalized results between Now and widget fixtures for
  MeteoGroup, OPERA and RainViewer;
- available/partial/unavailable series, wet now, arrival, confirmed/unconfirmed stop and full-hour
  clear temperature rules;
- exact mini-disc state equivalence with Now for wet threshold, peak colour, centre text, rain
  texture and RainViewer likely-snow texture/palette;
- no invented bearing, snow, rainfall rate or dry conclusion;
- deterministic 1×1 modes and responsive content priority through 4×1;
- graph horizon, severity bands, rain/snow colours and unavailable coverage;
- rain arming transitions and shared-target notification deduplication;
- linked Lightning episode/combined-notification/deep-link behaviour;
- Lightning display with its alert switch off, and omitted acquisition when no widget presentation
  or enabled alert requires it;
- DND-off default, same-day and overnight membership, inclusive-start/exclusive-end boundaries,
  equal-time rejection, current-zone changes and DST gap/overlap behaviour;
- rain/Lightning events consumed during DND with no late alert, later clear/no-detection re-arming,
  and manual/foreground checks respecting quiet delivery while display refresh continues;
- independent DND schedules for widgets sharing or not sharing a target, including a single
  coalesced notification when at least one eligible subscription is outside quiet hours;
- unique-target work coalescing, overlapping generation rejection and cached-state retention;
- foreground suppression/resume without cadence reset or duplicate alerts;
- 0%, default and 100% background opacity proving that text/icons/data colours remain opaque;
- widget/configuration parity for resolved palette, cards, controls, icons, spacing and typography,
  with no per-widget theme selector;
- manual and automatic Day/Night palette resolution without a weather reload;
- system sans typography/weight mapping, localization, font scale and content descriptions; and
- widget deletion cleanup without erasing still-shared target state.

Physical-device/launcher validation must cover at least one Pixel-like launcher and one Samsung-like
launcher in 1×1, intermediate and 4×1 sizes; Dark, Light and Slate/day-night transitions; transparent
and opaque backgrounds; likely snow and rain textures; notification-to-Radar target accuracy; Doze
delay messaging; localized 12/24-hour DND pickers and TalkBack summaries; continued live-looking
display updates during quiet hours; and process/launcher recreation.

## Non-goals

The first widget version does not:

- animate Radar or promise real-time/continuous updates;
- bypass Android's WorkManager minimum, Doze, force-stop or OEM restrictions;
- acquire background location;
- monitor every saved place unless it is an app alert target or configured widget target;
- offer a per-widget radar provider, lightning radius or custom font;
- invent a forecast from unknown/partial data; or
- change the app's selected/startup place merely because the widget is tapped.
