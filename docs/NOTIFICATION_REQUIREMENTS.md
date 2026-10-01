# App and widget notification requirements

> **Normative status: implemented in Rain Alarm 0.9.1.** This document is the authoritative
> product and engineering specification for Rain Alarm notifications. `WIDGET_REQUIREMENTS.md`
> owns widget acquisition and rendering;
> `LIGHTNING_ALERT_REQUIREMENTS.md` owns Lightning observation semantics. Where either discusses
> notification eligibility, wording or deduplication, this document takes precedence.

## Purpose

Rain Alarm reports a new rain or observed-Lightning episode once, for the correct place, with the
best facts known at delivery time. The app subscription and every widget instance are independent
subscriptions. They may poll asynchronously, but app and widget sources must not repeat the same
phenomenon episode after either source has successfully notified it.

Android schedules background work inexactly. A notification is informational, not a guarantee of
delivery time and not a weather or safety warning.

## Terminology

- **App subscription** — the selected-place Rain notification and Lightning activity notification
  settings in the main app. The two switches are independent.
- **Widget instance / widget subscription** — one launcher widget ID with its own frozen saved
  place, opacity, notification master, Include lightning choice and Do Not Disturb (DND) bounds.
- **Monitored target** — a display name plus provider-independent coordinate identity. Coordinates
  are normalized using the established five-decimal target key. Provider choice and forecast
  details are not part of episode identity. Current-location equivalence is deliberately
  conservative: it matches a saved place only when the normalized coordinates match.
- **Rain episode** — one continuous period of approaching or current precipitation, beginning when
  an authoritative evaluation first changes from no active episode to precipitation and ending
  only after a successful complete 0–60-minute clear evaluation.
- **Lightning episode** — one continuous detected-activity episode under the Lightning observation
  policy. It ends only after a successful no-detection evaluation.
- **Event component** — the rain component or Lightning component of a candidate notification.
  Components are independently eligible, delivered, claimed, suppressed and re-armed.
- **Delivered claim** — persistent evidence that NotificationManager accepted an app or widget
  notification for one target, episode and component. A permission check or attempted post is not
  a claim.
- **Complete clear** — a successful `AVAILABLE` 0–60-minute rain series with no wet sample.
- **Partial/unavailable result** — incomplete, stale, failed, uncovered or low-confidence evidence.
  It cannot prove clear conditions, re-arm rain or close a rain episode.

## Settings, defaults and independence

### Main app

- **Rain notification** controls only the app rain subscription.
- **Lightning activity notification** controls only the app Lightning subscription and defaults
  off on a fresh install.
- App switches never enable, disable or migrate a widget subscription.

### Every widget instance

Each widget persists these independently:

| Setting | Default | Effect |
| --- | --- | --- |
| Saved location | required | Frozen acquisition, display, notification and tap target |
| Background opacity | existing card opacity | Appearance only |
| Widget notifications | **On** | Master for widget-generated rain and Lightning notifications |
| Include lightning | **Off** | Lightning notification eligibility only |
| Do Not Disturb | Off; 22:00–07:00 bounds retained | Silences only this widget's deliveries |

Include lightning is subordinate to Widget notifications. DND has no effect on acquisition or
display. Changing any widget setting does not change the app or another widget.

Old `Smart`, `Rain`, `Lightning` and `Temperature` primary-content values remain decodable only for
serialization compatibility. All migrated and new widgets are rain-first. Existing widget targets,
opacity and DND survive migration; notifications become on and Include lightning off unless an
explicit per-widget value already exists. Legacy global app switches are never copied into widgets.

## Widget visual contract

Visual state is independent of whether notifications are enabled.

### 1×1

- Rain or likely snow now: shared textured Compass disc with `Now`.
- Approaching rain or likely snow: shared disc with integer minutes and `min`.
- Usable dry rain data and a fresh temperature: rounded temperature in the disc.
- Missing temperature or unusable rain data: neutral dash/unavailable treatment.
- Never show a Lightning bolt, no-Lightning tick/checkmark or Lightning status. A 1×1 may still
  acquire and notify Lightning when its Include lightning control is on.

### 2×1, 3×1 and 4×1

- Rain/likely snow always owns the disc and primary status when now or approaching.
- With usable dry data the disc is a dash; fresh temperature is adjacent text.
- Detected Lightning may be additional text. It never displaces rain and is shown independently of
  Include lightning, because that switch controls notification eligibility, not display.
- Unavailable is not the same as clear.

## Rain eligibility and state machine

1. A widget starts unarmed. Only a successful complete clear arms that widget's rain subscription.
2. A partial, unavailable, failed or stale evaluation leaves arming and episode state unchanged.
3. Armed widget + approaching rain creates one candidate rain component.
4. Armed widget + rain already falling creates one candidate with Rain-now wording.
5. Once that widget delivers or consumes the episode, forecast drift, changed duration/intensity,
   provider changes and the transition from forecast to rain-now produce no second widget alert.
6. DND consumes a qualifying widget component without delivery or later catch-up.
7. Only a later complete clear closes the target episode and re-arms the widget for a later episode.
8. The app preserves immediate rain eligibility: its first usable approaching or already-falling
   evaluation can notify without a prior clear. Episode consumption still prevents a later
   forecast-to-rain-now duplicate.

Widget acquisition may be shared for identical targets, but arming, DND and consumption remain per
widget ID.

## Lightning eligibility and state machine

1. App Lightning requires its independent app switch. Widget Lightning requires both that widget's
   master and Include lightning.
2. First eligible `DETECTED` evidence creates a Lightning candidate.
3. Continuing detections, duplicate frames and no-new-frame results do not create another episode.
4. A successful `NO_DETECTION` result ends the episode and re-arms it.
5. Unavailable evidence neither means clear nor re-arms.
6. Wider widgets may acquire Lightning solely for visible secondary text. Such acquisition must
   not create notification eligibility when Include lightning is off.

Rain and Lightning are independent. If both newly qualify in one transaction, send one combined
notification. If one component was already delivered and the other qualifies later, notify only
the new component; do not present the old component as a new alert.

## Symmetric cross-source suppression

App and widget polling is asynchronous. Exact notification text, frame time, forecast start, stop,
duration, provider or peak equality is forbidden as an identity rule.

- A successful **app** delivery claims that target/episode/component against every matching widget.
- A successful **widget** delivery claims that target/episode/component against the app.
- Widget claims do not suppress sibling widgets: each widget remains an independent subscription.
- Claims are component-specific. A rain-only claim cannot suppress Lightning and vice versa; a
  combined successful delivery claims both.
- The first successful delivery wins cross-source, with no app preference and no timing delay.
- Foreground and worker transactions are serialized; claim read, post and successful-claim write
  are coordinated. A claim is written only after NotificationManager accepts the post.
- Disabled settings, denied permission, DND, channel blocking or a post failure create no delivered
  claim. DND may still consume that particular widget's candidate.
- A complete rain clear or successful Lightning no-detection closes the relevant claim. Partial or
  unavailable evidence cannot close it.
- Claims and subscription consumption persist through process/device restart and are bounded by
  truthful episode closure, not arbitrary forecast detail changes.

Example correlation: the app reports `starts at 08:15 · in 15 minutes · lasting about 20 min · peak
intensity 62%`. A widget later sees 08:12–08:38 at 70%, then `Rain now`. These are the same active
episode, so that widget suppresses rain. After a complete clear, a later rain band is a new episode.

## Notification content

Every notification includes the monitored place in its title. Times use the device's localized
clock and the target evaluation instant. Minute plurals are localized. Intensity is the shared Now
graph normalized severity, clamped to 0–100 and rounded to an integer; it is relative intensity,
not mm/h.

### Expected precipitation severity in rain-bearing titles

Every rain-bearing app or widget title includes the expected qualitative severity. One shared
domain calculation and one shared localized title formatter are authoritative for both sources and
all widget sizes; widget rendering and notification services must not recreate the thresholds.

- Input is the same provider-normalized `RainMinuteSeries` upper-envelope (`maximum`) samples used
  by the Now graph, transformed by that series' existing `chartSeverity` normalization and clamped
  to 0–1. Radar tile colour, the instantaneous current sample and the body's numeric peak are not
  alternative title inputs.
- Scope is exactly the continuous event already selected by `RainMinuteSeriesAnalyzer`: from minute
  zero when raining now, or the arrival minute when approaching, up to but excluding the first dry
  minute. When no dry minute exists inside known coverage, use samples through the available graph
  horizon. A separate later event after a dry gap cannot change this title.
- Use the maximum valid normalized upper-envelope sample in that scope. Existing Now bands are
  authoritative: `0 <= x < 1/3` is **Light**, `1/3 <= x < 2/3` is **Medium**, and
  `2/3 <= x <= 1` is **Severe**. Exact boundaries belong to the higher band.
- Invalid or genuinely unavailable event-peak evidence produces the established unqualified
  rain/snow title; it must never invent a severity. Valid inputs are normalized/clamped before band
  selection.
- Adding severity is presentation only. Episode identity, delivered claims, notification IDs,
  suppression, DND and deduplication remain event/target/component based and never include title
  text or severity.

### Upcoming bounded episode

- Title: `{Severity} rain approaching {place}` or
  `{Severity} snow likely approaching {place}`.
- Include `starts at X` and `in N min`.
- Because a real dry sample closes the same episode inside available coverage, include `lasting
  about N min` and `peak intensity N%`.

### Upcoming unbounded episode

- Include start clock and ETA.
- No invented stop or duration.
- Include `at least N%`, using the maximum known normalized intensity in real coverage.

### Rain or likely snow already falling

When the previous successful complete poll was clear and this source has not already notified:

- bounded: `{Severity} rain now at {place}` (or snow equivalent), stop clock,
  `about N min remaining`, and
  `peak intensity N%`;
- unbounded: Rain-now title and `at least N%`, with no invented end.

If peak intensity is genuinely unavailable, omit it. Preserve valid 0%/100%, midnight, DST and
time-zone behavior.

### Lightning and combined alerts

Standalone text remains deliberately concise:

- Title: `Lightning activity detected near {place}`
- Detail: `Lightning activity was detected within 15 km.`

A combined alert uses the same severity-bearing rain/snow title, retains all rain facts and
appends/folds the Lightning detail. A Lightning-only title remains unchanged. A later Lightning
component must not repeat an already-delivered rain component, or vice versa.

## DND, permission, failure and concurrency

- DND start is inclusive and end exclusive; same-day and cross-midnight ranges use local wall time.
- A qualifying widget component during DND is consumed without catch-up. Acquisition/display
  continue. A genuinely re-armed later episode may notify.
- Android 13+ notification permission and `areNotificationsEnabled` are checked. No permission or
  blocked notifications cannot create a delivered claim.
- Security/post failures leave the undelivered component eligible for a later transaction.
- Episode, claim and per-subscription state are synchronously persisted. Overlapping immediate,
  periodic and foreground paths use the shared transaction lock and atomic state lock.
- Process restart must not duplicate a consumed/claimed episode and must not turn partial data into
  clear evidence.

## Notification taps

- Every tap opens Radar for the notification's exact target without changing the app's pinned or
  selected saved place.
- A rain-only notification opens normal Radar state.
- Any notification that actually includes a new Lightning component opens Radar with the existing
  target-bound temporary Lightning state. App navigation does not cancel that temporary state.
- A suppressed old Lightning component must not cause a rain-only notification tap to enable it.

## Accessibility, localization and privacy

- Switches expose labels and enabled/disabled state. Include lightning is hidden/disabled when the
  widget master is off.
- 1×1 content descriptions follow its visible rain/temperature/dash state and do not announce a
  hidden Lightning state. Wider descriptions may include visible Lightning text.
- All notification and widget-control strings exist in every supported locale; quantities use
  Android plurals. Do not concatenate unlocalized safety qualifications.
- Color/texture is never the only rain/snow distinction.
- Persist only normalized target identity, bounded episode/checkpoint evidence and configuration.
  No account, analytics or location history is introduced.

## Worked examples (normative)

Unless stated otherwise, Widget A monitors **Great Baddow**, widget notifications are on, Include
lightning is off, DND is off, and a complete-clear poll occurred at 08:00.

| ID | Input and prior state | Expected result |
| --- | --- | --- |
| EX-01 | 1×1, dry, 17.4°C | Disc `17°`; no tick/bolt/text Lightning state |
| EX-02 | 1×1, dry, temperature missing | Neutral dash |
| EX-03 | 1×1, rain starts 08:15 | Rain disc `15 min` |
| EX-04 | 1×1, raining now | Rain disc `Now` |
| EX-05 | 1×1, likely snow in 9 min | Snow texture/disc `9 min` |
| EX-06 | 1×1, Lightning detected, dry 17°C | Still `17°`; Lightning may notify only if Include lightning is on |
| EX-07 | 2×1/3×1/4×1, dry 17°C | Dash in disc; adjacent `17°` |
| EX-08 | Wider widget, rain + Lightning | Rain remains primary; `Lightning nearby` is secondary text |
| EX-09 | Any size, unavailable rain and no temperature | Neutral unavailable/dash; never “clear” |
| EX-10 | New/migrated widget | Notifications on, Include lightning off, rain-first; legacy content ignored |
| EX-11 | Master off, approaching rain + Lightning | Display updates; no widget notification of either component |
| EX-12 | Master on, Include lightning off, only Lightning | Wider text may show Lightning; no Lightning notification |
| EX-13 | Master on, Include lightning on, only Lightning | `Lightning activity detected near Great Baddow`; radius sentence |
| EX-14 | DND 22:00–07:00, event at 23:00 | Widget component consumed silently; no 07:00 catch-up |
| EX-15 | Same DND, event at 07:00 | Eligible (end is exclusive) |
| EX-16 | DND 09:00–17:00, event at 09:00/16:59/17:00 | Suppress/suppress/deliver respectively |
| EX-17 | First widget poll already wet | No rain alert: no qualifying prior clear |
| EX-18 | Complete clear at 08:00; rain 08:15–08:35, Light graph band, detail peak .624 | `Light rain approaching Great Baddow`; `Starts at 08:15 · in 15 minutes`; `lasting about 20 min · peak intensity 62%` |
| EX-19 | Complete clear then rain now, ending 08:40, Medium graph band, detail peak .617 | `Medium rain now at Great Baddow`; stop 08:40; about 40 min remaining; peak 62% |
| EX-20 | Rain forecast notified; next poll says rain now | No duplicate for that subscription episode |
| EX-21 | Start drifts 08:15→08:12; stop 08:35→08:38; peak 62→70 | Same episode; no duplicate |
| EX-22 | Complete clear after EX-21, then rain at 10:00 | New episode; subscription may notify again |
| EX-23 | Partial dry horizon after EX-21 | Does not close/re-arm; no new episode |
| EX-24 | Unavailable poll after EX-21 | Does not close/re-arm; cached display rules remain separate |
| EX-25 | Upcoming rain reaches coverage edge, peak .704 | Start + ETA + `at least 70%`; no stop/duration |
| EX-26 | Rain now reaches coverage edge, peak 1.0 | `Rain now at Great Baddow · at least 100%`; no end |
| EX-27 | Bounded episode, intensity .004/.995 | Rounded integer 0%/100% are valid |
| EX-28 | Peak samples unavailable/NaN | Omit intensity rather than invent it |
| EX-29 | App rain only qualifies | One app rain notification; no Lightning text/temp state |
| EX-30 | App Lightning only qualifies | Standalone concise Lightning notification; Lightning tap state |
| EX-31 | Widget rain only qualifies | Widget rain notification with full facts |
| EX-32 | Widget Lightning only qualifies | Widget Lightning notification only; 1×1 face unchanged |
| EX-33 | Rain + Lightning newly qualify together | One combined notification; both components claimed/consumed |
| EX-34 | Rain already delivered; Lightning qualifies later | Lightning-only alert; do not repeat rain facts as a new event |
| EX-35 | Lightning delivered; rain qualifies later | Rain-only alert; tap does not enable temporary Lightning |
| EX-36 | App successfully delivers rain first | All matching widgets suppress rain for that episode; their displays still update |
| EX-37 | Widget A successfully delivers rain first | App suppresses rain; Widget B remains independently eligible |
| EX-38 | App/widget overlapping race | Transaction/claim serialization permits exactly one cross-source winner; no timing delay |
| EX-39 | First post fails or permission denied | No claim; matching other source and later retry remain eligible |
| EX-40 | Same target, different providers/details | Same normalized target/episode; cross-source suppression applies |
| EX-41 | Different target (York widget) | Great Baddow claim has no effect |
| EX-42 | Two widgets same target, different DND | Each independently consumes/delivers; one widget does not silence its sibling |
| EX-43 | Process restarts after successful delivery | Persisted consumption/claim prevents duplicate |
| EX-44 | Successful clear/no-detection after restart | Corresponding episode and claim close; later event may notify |
| EX-45 | App disabled, Widget A eligible | Widget can notify; app creates no claim |
| EX-46 | Widget A DND consumes rain, app eligible | DND creates no delivered claim; app may still notify |
| EX-47 | Forecast crosses midnight/DST | Localized start/stop clocks remain correct; elapsed minutes derive from instants |
| EX-48 | Rain-only notification tap | Opens exact Radar target; normal Lightning setting |
| EX-49 | Lightning-only/combined tap | Opens exact Radar target with temporary Lightning enabled |
| EX-50 | First app poll is already wet | App may notify Rain now immediately; widget subscriptions remain unarmed |
| EX-51 | Event graph maximum 0 or just below 1/3 | `Light rain approaching Great Baddow` |
| EX-52 | Event graph maximum exactly 1/3 | `Medium rain approaching Great Baddow` |
| EX-53 | Event graph maximum just below 2/3 | `Medium rain approaching Great Baddow` |
| EX-54 | Event graph maximum exactly 2/3 | `Severe rain approaching Great Baddow` |
| EX-55 | Event graph maximum 1 (or a normalized input clamped down to 1) | `Severe rain approaching Great Baddow` |
| EX-56 | Light event at 08:15, dry gap, unrelated Severe event at 09:00 | First event title stays `Light rain approaching Great Baddow` |
| EX-57 | Medium event already falling | `Medium rain now at Great Baddow`; existing stop/duration/body rules unchanged |
| EX-58 | Severe likely-snow event | `Severe snow likely approaching Great Baddow`; never relabel as rain |
| EX-59 | Event peak invalid or unavailable | Existing unqualified rain/snow temporal title; omit rather than invent severity |
| EX-60 | Light rain and Lightning qualify together | `Light rain approaching Great Baddow`; Lightning remains in the body |
| EX-61 | Lightning-only episode | `Lightning activity detected near Great Baddow`; unchanged |
| EX-62 | App and any widget evaluate identical series/event | Identical severity and localized title |
| EX-63 | Open-ended Medium event reaches the known graph edge | Use the event maximum through that edge; no invented stop; after a confirmed stop/clear, do not retitle the closed event |
| EX-64 | Active event severity changes Light→Severe after delivery | No duplicate; suppression and claims remain tied to the same episode |

## Acceptance criteria and verification map

| Requirement | Automated evidence | Physical/manual evidence |
| --- | --- | --- |
| Rain-first responsive rendering | `WidgetPoliciesTest` and `NotificationRequirementsTest` (`NOTIFY VIS`) | Resize 1×1 through 4×1 on launcher |
| Defaults/migration/independence | serialization and configuration tests (`NOTIFY CFG`) | Reconfigure two widgets independently |
| Prior-clear, partial/unavailable and Rain-now | policy and requirements tests (`NOTIFY RAIN`) | Observe one real clear→rain transition |
| Lightning episodes/components | Lightning policy + requirements tests (`NOTIFY LTG`) | Optional real Lightning observation |
| Symmetric async suppression | claim policy/scenario tests (`NOTIFY XSR`) | Open app after widget alert; confirm no repeat |
| Bounded/unbounded content/intensity | notification-text tests (`NOTIFY TEXT`) | Inspect localized Android notification |
| Event-scoped title severity and app/widget parity | notification severity tests (`NOTIFY SEVERITY`) | Compare app and widget alerts for one event |
| DND/permission/failure/restart | policy and persistence-source tests (`NOTIFY DEL`) | Toggle permission and cross-midnight DND |
| Target-isolated taps | deep-link and coalescing tests (`NOTIFY TAP`) | Tap rain and Lightning notifications |
| Locale completeness | resource test (`NOTIFY I18N`) | Spot-check chosen language |

Release acceptance requires the direct unit suite, lint and signed minified release build, plus a
same-signed physical preview check of widget controls, responsive visuals and notification taps.
