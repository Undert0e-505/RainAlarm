# Android home-screen widget requirements

> **Status: implemented in Rain Alarm 0.9.1.** This remains the normative engineering description
> of the Android home-screen widget and supporting polling/alert behaviour. Lightning observation,
> notification and temporary Radar-layer behaviour are specified separately in
> [Lightning activity alert requirements](LIGHTNING_ALERT_REQUIREMENTS.md). All app/widget
> notification settings, eligibility, episode correlation, content, DND and cross-source
> suppression are authoritative in
> [App and widget notification requirements](NOTIFICATION_REQUIREMENTS.md). Launcher, device and
> live-provider validation continues on the road to 1.0.

## Objective

The Rain Alarm widget provides an answer-first view of one explicitly configured saved place without
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
- From **2×1** upward, retain the same compact textual hierarchy used at 3×1: saved place, primary
  status, maximum qualitative intensity when wet/approaching, confirmed stop time, nearby-Lightning
  state and last-successful update. Use compact spacing rather than dropping those facts; ordinary
  names such as **Great Baddow** must fit without truncation. The user-selectable 1×1 remains the
  deliberately minimal exception.
- The 2×1, 3×1 and 4×1 rows use one identical fixed mini-Compass diameter and render scale, based
  on the 4×1 Compass. The 1×1 Compass remains an independent square-filling presentation.
- Widget views are static snapshots. They do not continuously animate Radar, sweep the Now graph or
  run a countdown every second.
- Tapping a valid widget opens **Radar** for that widget's exact resolved place. It does not change
  the widget configuration or startup-pinned place.
- When the displayed widget state includes nearby lightning activity, the tap uses the temporary
  Lightning deep link in
  [Lightning activity alert requirements](LIGHTNING_ALERT_REQUIREMENTS.md#notification-tap-and-temporary-radar-state).
- A widget retains its frozen saved-place snapshot if that row is later deleted. It continues to
  monitor and open that exact coordinate, while remaining available for explicit reconfiguration.

## Per-widget configuration

Every widget instance has independent persisted configuration:

| Setting | Choices/default |
| --- | --- |
| Location | One saved place |
| Background opacity | 0–100%, defaulting to the opacity of the app's existing cards |
| Widget notifications | **On** by default; independent for every instance |
| Include lightning | **Off** by default; subordinate to this widget's notification master |
| Do Not Disturb | **Off** by default; when on, distinct local start and end times |

The configuration screen must show the resolved place and a live miniature widget preview. It uses
the app's currently resolved palette, card shapes, controls, iconography, spacing hierarchy and
system-sans typography. It does not have a per-widget theme selector; background opacity is the only
widget-specific visual override.

Saving a location or newly required Lightning stream triggers an immediate network-constrained
refresh. Saving only opacity, notification eligibility or Do Not Disturb republishes configuration
without changing the weather target. The launcher and an in-app entry point must allow an existing
widget to be reconfigured.

There is no per-widget radar-provider choice in this version. Each resolved target uses the same
preferred-provider and geographic fallback rules as Now and Radar.

### Saved-place target

- Persist a self-contained target snapshot per widget: stable saved-place ID, display name,
  coordinates and any zone information required for presentation. Acquisition must not depend on
  MainActivity, the current app selection, a live device fix or in-memory app state.
- Normal app navigation, changing **Selected now**, changing the startup pin or entering Travel does
  not alter the widget target.
- A rename may update the displayed label while retaining the same snapshot coordinates. A place
  coordinate edit is not silently adopted; reconfigure the widget to choose the edited target.
- If the source saved-place row is deleted, retain the frozen name/coordinates and continue. Never
  substitute Current, the startup place or another saved place.
- Configuration cannot complete without a saved place. An app-styled empty state opens Places so
  the user can create one, then the configuration reloads.
- Legacy fixed widgets freeze their existing saved target. Legacy Follow widgets freeze the active
  selection only when it is saved, otherwise the startup/pinned saved place; with no saved fallback
  they show **Configuration required** and open configuration. Migration is idempotent.

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
change invalidates incompatible widget data and shows a truthful loading state for that target
until replacement succeeds or the completed attempt has no usable target-matching result. It must
not show another provider/target snapshot as though it were current.

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
- When all available samples are dry, show a fresh temperature in the 1×1 disc or as adjacent text
  at wider sizes; wider discs and temperature-missing 1×1 widgets use the neutral dash. An incomplete
  horizon must not be described as a complete one-hour clear outlook or arm prior-clear alert
  eligibility.
- `Unavailable`, no-usable-sample and stale states use concise explicit wording and an em
  dash/neutral disc. Partial but usable samples render normally through their real horizon and
  retain the successful update time.
- The 4×1 layout always shows the place and last successful update. Smaller layouts expose the
  equivalent information in their content description when it cannot fit visually.

While a refresh is in flight, keep the last successful coherent state visible with unobtrusive
**Updating**. A skipped/deferred worker, resize/recomposition, a transient
endpoint failure, or one failed optional stream must not replace it with **Update unavailable**.
Keep its real last-success time/age and swap a successful replacement atomically so the widget does
not flash blank. Show **Update unavailable** only when the completed acquisition has no semantically
current, target-matching field and no usable cached field. Stale cached data never enters an alert
decision.

Compact storage retention and display validity are separate. Recalculate arrival, stop and
intensity against the current wall clock from the stored timestamped samples. A countdown therefore
advances between polls and becomes unavailable immediately after its real source horizon ends; the
storage hard-expiry must never freeze text such as **Rain in 35 min**. Temperature and Lightning use
their own freshness horizons: temperature for at most 90 minutes from its successful fetch and
Lightning display evidence for at most 20 minutes from its observation frame. Episode/checkpoint
semantics remain separate. Older normalized state may remain bounded on disk for recovery and
deduplication but cannot be presented as current weather.

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
- plain branded-blue dry treatment when available samples are dry.

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
| Dry available samples, compact 1×1 | App branded-blue dry disc | fresh rounded temperature, or `—` |
| Dry available samples, 2×1/3×1/4×1 | App branded-blue dry disc | `—`; temperature stays in the status column |
| No usable samples / unknown / unavailable | Neutral unavailable treatment | `—` |

## Next-hour graph

The canonical 4×1 presentation includes a compact next-hour Now graph built from the same minute
series as the app:

- the plot ends at the real known horizon and never pads an incomplete tail;
- Light, Medium and Severe bands use the shared chart-severity thresholds;
- the average curve and available minimum/maximum envelope preserve their existing meanings;
- the baseline is the provider wet threshold after presentation normalization, not raw zero;
- rain and likely-snow segments use the app's existing palettes;
- render no axes, tick marks, tick labels, numbers or captions;
- map the first available sample to the left plot edge and the last to the right, with only a
  stroke-width anti-clipping inset, and use the full available height for normalized severity;
- render at the launcher's actual responsive pixel bounds and density (or a safely oversampled
  equivalent), with anti-aliased paths and no later upscaling; cache by dimensions, density,
  palette and data so resizing cannot retain a smaller fuzzy bitmap; and
- unavailable coverage is visually distinct from a zero/dry curve.

The graph is a snapshot, not an animated Radar timeline. Omit the graph before removing the place,
disc or compact textual status at narrower widths.

## Lightning in the widget

When a widget layout or per-widget notification choice requires Lightning, it is evaluated in the same polling transaction and
at the exact same frozen target coordinate as rain. Detection radius, five-minute frame catch-up,
freshness, episode deduplication, wording and error semantics are defined in
[Lightning activity alert requirements](LIGHTNING_ALERT_REQUIREMENTS.md).

- Nearby detected activity appears as a distinct lightning badge/status alongside rain. It never
  hides a known wet/approaching rain state.
- A 1×1 never presents Lightning: it remains rain-first, then temperature/dash when dry.
- A widget displaying nearby lightning opens Radar with the target-bound ten-minute temporary
  Lightning state.
- No-detection and unavailable are different. Do not show an unavailable source as safely clear.
- A wider widget's Lightning text is independent of its Include lightning notification choice.
- Wider responsive layouts create Lightning display demand. A 1×1 creates Lightning demand only
  when its own Widget notifications and Include lightning controls are both on.

## Responsive content priority

### 1×1 rain-first state

The 1×1 has no selectable primary mode. Rain/likely snow now or approaching always owns the disc.
When usable rain data is dry, fresh rounded temperature is shown; otherwise it shows a neutral
dash. Lightning is never shown or announced by the 1×1 face.

### Intermediate and 4×1

Responsive layouts add content in this priority order:

1. mini Compass disc and primary rain/clear/unavailable state;
2. place name and lightning badge;
3. arrival/Now, qualitative peak and confirmed stop detail;
4. last successful update; and
5. compact next-hour graph and reliable direction cue.

The canonical 4×1 layout includes all five groups. Both 2×1 and 3×1 include groups 1–4 with the
same information hierarchy and the same Compass geometry as 4×1. Text may shorten according to
localized width, but the underlying state and accessibility description remain identical. The 4×1
row uses compact fixed Compass/status blocks and lets the graph fill all remaining width, retaining
balanced outer padding rather than reserving dead spacer columns.

## Alerts and notification state

Every widget has its own notification master (on by default), Include lightning (off by default),
prior-clear rain state, component consumption and DND. App switches are unrelated. Episode rules,
Rain-now behavior, exact content, symmetric first-successful app/widget suppression and exhaustive
examples are normative in [App and widget notification requirements](NOTIFICATION_REQUIREMENTS.md).

### Per-widget Do Not Disturb

Every widget is a separate **monitoring subscription** with its own Do Not Disturb (DND), also
described in the UI as **Quiet hours**. DND controls notification delivery arising from that widget
subscription. It does not change Android's system Do Not Disturb, an unrelated widget, or the
app's separate notification settings.

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
  validation message explains that the times must differ. Users can turn this widget's notification
  master off when they want its alerts disabled entirely.
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
2. apply each widget's independent master and Include lightning choice;
3. evaluate each eligible widget's DND at the decision instant;
4. mark quiet subscriptions consumed without delivery; and
5. coordinate persistent component claims against the app source without collapsing sibling
   widget subscriptions.

One widget's DND or delivered claim does not silence another widget. Cross-source app/widget
suppression follows the first successful delivery as specified in `NOTIFICATION_REQUIREMENTS.md`.

DND settings belong to the widget ID. Reconfiguration carries the new values with that instance;
deleting the widget removes its DND/delivery state without deleting shared target evidence still
used elsewhere.

## Polling and foreground coordination

### Background scheduling

- Use one network-constrained WorkManager coordinator with an approximately 15-minute periodic
  request. Fifteen minutes is Android's periodic minimum; actual execution is inexact and can be
  deferred by Doze, battery optimization, standby and network availability.
- Enqueue an immediate constrained refresh after initial widget configuration, relevant
  reconfiguration, provider change and an explicit matching app refresh.
- Persist the widget configuration, monitoring subscription and a pending-initial-refresh marker
  atomically before scheduling that first refresh. Every newly configured saved-place widget must
  reach data without another widget, launcher resize or app-open event waking the coordinator.
- Configuration can finish while its Activity is still visible. Widget acquisition is independent
  of app visibility, so the initial worker remains eligible and its durable pending marker is not
  consumed until a usable publication or bounded terminal failure. The constrained initial work
  uses bounded exponential backoff. A transient completed acquisition
  failure remains retryable, while a bounded number of completed failures may end in a truthful
  terminal unavailable state when there is no usable target-matching snapshot.
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

### Presentation clock between acquisitions

Network acquisition remains on the provider/WorkManager cadence above. It is deliberately separate
from a single process-wide, presentation-only minute ticker used by all configured widgets. While
the device is interactive, that ticker repaints affected widgets at local wall-clock minute
boundaries from the latest timestamped cached series. It performs no network request, location
request, alert evaluation or delivery, and never changes rain/Lightning episode eligibility.

- Relative text is derived again from wall-clock now on every repaint: `Rain in 47 min` becomes
  `Rain in 46 min`, reaches `Rain in 1 min`, then `Rain now`. It never displays zero or a negative
  countdown. Current/maximum intensity and rain/likely-snow treatment move through the cached
  samples at the same time. An absolute stop time may remain absolute.
- **Updated HH:mm** remains the successful data-acquisition time. A presentation repaint must not
  disguise old source data as newly fetched.
- The ticker uses one non-wakeup, inexact platform alarm plus an in-process minute callback. It
  does not request exact-alarm permission and does not run a foreground service. Android does not
  expose reliable launcher-page visibility, so device-interactive is the practical visibility
  boundary. The alarm may be batched while the screen is off; after wake or any deferred delivery,
  the widget derives directly from the current clock rather than replaying missed minutes.
- Only widgets whose visible cached presentation can change keep the ticker scheduled. Multiple
  widget instances coalesce into one schedule, and removal of the final dynamic widget cancels it.
  Reboot, package replacement, manual clock changes and time-zone changes reconcile from the new
  device-local wall clock without carrying forward interval drift.
- A presentation tick reads the newest snapshot at execution time. It cannot replace a concurrent
  acquisition result with an older generation, blank a last-good state, or extend a rain series
  after its real timestamped horizon has expired.

### Independence from app lifecycle

- Scheduled and initial widget acquisition remains eligible while MainActivity or the widget
  configuration Activity is visible, while the app is backgrounded, and after process recreation.
- Foreground app and widget requests for an identical frozen target may share safe repository work,
  but every widget still receives a terminal state publication; an app callback is never required.
- Widget acquisition never requests device location and still works when location permission is
  denied. App current-location monitoring remains a separate app-only concern.
- Lifecycle changes preserve cadence, checkpoints and arming state and never reset state or emit a
  duplicate alert merely because visibility changed.

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
  confirmed stop (if known), temperature (when applicable), availability and last update. Wider
  widgets may add their visible Lightning state; 1×1 must not announce hidden Lightning evidence.
  Do not force TalkBack to traverse decorative graph points.
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
- Keep the privacy disclosure aligned with widgets, per-widget saved IDs/config, background
  network checks, cached display state and alert memory.
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
- Persist pending-initial-refresh and bounded-failure state by Android widget ID. Successful usable
  publication clears it; reconfiguration replaces it for the new exact target; widget deletion
  removes it. Identical targets may share one acquisition, but coalescing must complete every
  matching widget's pending request and must never starve the only request for a distinct target.
- Ensure periodic work is present when the first widget or alert is enabled and reconcile it on app
  startup/update. Remove it when no widgets or enabled alerts require it.
- Process death, reboot or app update must not re-arm rain/lightning episodes or duplicate a prior
  notification. WorkManager timing remains platform-controlled.
- Loading keeps the last semantically current coherent state with **Updating**. Refresh already in
  progress, resize, transient network/provider/decode failure and
  a failed optional stream leave that state and its true update time intact. A Lightning failure
  does not erase valid rain/temperature, and a rain failure does not erase fresh Lightning.
- A stored rain series is current only while its timestamped coverage contains wall-clock now;
  cached countdown/stop/intensity are re-derived from the remaining samples. Temperature and
  Lightning have independent bounded freshness. The three-hour snapshot hard-expiry is storage
  eviction/last-resort metadata, not permission to display a three-hour-old rain answer.
- **Update unavailable** is reserved for a completed acquisition with no usable current or cached
  field for that exact target, or a snapshot beyond hard expiry. A target change clears the old
  target's presentation and shows loading until success/final failure.
- A first configuration with no prior target-matching snapshot remains **Updating** while its
  initial refresh is queued, in flight or retrying. It must not publish
  **Update unavailable** merely because a worker was skipped or a transient first attempt failed.
- If no successful state exists, show a neutral disc, place (when resolvable), concise unavailable
  text and tap path to Radar or reconfiguration as appropriate.
- If provider eligibility changes, use normal fallback resolution. Do not move the configured
  saved-place snapshot to make a preferred provider work.

## Acceptance criteria

Automated tests must cover at least:

- independent saved-place snapshots for two widget IDs, rename/delete retention, legacy migration,
  no-saved-place configuration and location-permission independence;
- provider resolution and identical normalized results between Now and widget fixtures for
  MeteoGroup, OPERA and RainViewer;
- available/partial/unavailable series, wet now, arrival, confirmed/unconfirmed stop, dry-state
  temperature presentation and the rule that partial dry data never arms prior-clear alerts;
- exact mini-disc state equivalence with Now for wet threshold, peak colour, centre text, rain
  texture and RainViewer likely-snow texture/palette;
- no invented bearing, snow, rainfall rate or dry conclusion;
- deterministic rain-first 1×1 and responsive content priority through 4×1, including equivalent
  place/status/intensity/stop/update hierarchy at 2×1 and 3×1, identical Compass diameter and
  pixel geometry at 2×1/3×1/4×1, and independent square-filling 1×1 sizing;
- graph horizon, full-width/full-height sample mapping, actual-pixel/density render sizing,
  resize-keyed assets, severity bands, rain/snow colours, absence of axes and unavailable coverage,
  including a Samsung-like 4×1 bound where the graph fills the expanded remaining width without
  colliding with ordinary status text;
- app-immediate versus widget-prior-clear rain eligibility, widget arming transitions and
  symmetric first-successful cross-source component suppression;
- linked Lightning episode/combined-notification/deep-link behaviour;
- wider-widget Lightning display with Include lightning off, compact omission, and acquisition only
  when a visible layout or that widget's enabled Lightning notifications require it;
- DND-off default, same-day and overnight membership, inclusive-start/exclusive-end boundaries,
  equal-time rejection, current-zone changes and DST gap/overlap behaviour;
- rain/Lightning events consumed during DND with no late alert, later clear/no-detection re-arming,
  and manual/foreground checks respecting quiet delivery while display refresh continues;
- independent DND and delivery state for widgets sharing or not sharing a target;
- unique-target work coalescing, overlapping generation rejection, last-good retention through
  resize/transient/partial failure, target-change isolation, hard expiry,
  successful recovery, and clock advance proving a countdown cannot freeze beyond source coverage;
- minute presentation scheduling and countdown transitions (`47` to `46` to `1` to `Now`) without
  network/location/alert side effects, including multiple-widget coalescing, final-widget deletion,
  acquisition races, process restoration, midnight/DST/time-zone or manual-clock changes, truthful
  source expiry, and an unchanged last-success timestamp;
- app-visible and process-recreated acquisition without cadence reset or duplicate alerts;
- first-and-only saved-place widget reaching data independently, two distinct saved targets,
  identical-target coalescing without starvation, transient initial failure followed by retry,
  deletion before queued initial work, and operation with device-location permission denied;
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
