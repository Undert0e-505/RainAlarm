# Lightning activity alert requirements

> **Status: planned, not implemented.** This document defines the app-level lightning activity
> alert and Radar deep-link behaviour for a future release. It is deliberately separate from the
> [home-screen widget requirements](WIDGET_REQUIREMENTS.md): lightning alerts must work when no
> widget has been added.

## Objective

Rain Alarm will optionally monitor recent EUMETSAT lightning observations near the same place as
its rain alert evaluation. It will send one low-noise notification when a new nearby lightning
episode is detected, combine that information with a rain alert when both begin together, and open
Radar with Lightning temporarily visible when the user follows the notification.

This is an observation feature, not a strike forecast or a safety warning.

## Product decisions

| Decision | Requirement |
| --- | --- |
| Settings | Add **Lightning activity notification** directly below **Rain notification** in Settings. |
| Default | The Lightning switch is **off** on a fresh install and after upgrade when no explicit choice exists. |
| Independence | The switch controls alert eligibility, independently of Radar and widget display choices. |
| Detection distance | The default detection radius is **15 km** from the resolved target coordinate. |
| Source | EUMETSAT `mtg_fd:li_afa`, evaluated as observed five-minute accumulated flash areas. |
| Repetition | Notify once per lightning episode; a successfully observed no-detection interval must re-arm it. |
| Radar hand-off | A notification containing lightning opens the exact target in Radar with Lightning visible for ten minutes. |

The 15 km value must exist once as a named domain/configuration value, for example
`LightningDetectionPolicy.defaultRadiusKilometres`. Detection, persistence, worker and UI APIs
must receive or expose a radius value rather than embedding `15` as repeated literals. This keeps
the state model compatible with a future user-selectable radius. A radius setting in the UI is
explicitly out of scope for this version.

## Source meaning and public wording

`mtg_fd:li_afa` is a satellite optical **Lightning Imager accumulated flash-area** product. Each
advertised frame accumulates observations over a nominal five-minute interval. It includes both
intracloud and cloud-to-ground activity; this product cannot distinguish between them. It is not a
future forecast and it does not identify a verified ground-strike point.

User-visible language must therefore use wording such as:

- **Lightning activity detected near Baltinglass**; or
- **Lightning detected within 15 km of Baltinglass**.

It must not say that a strike is predicted, imply that lightning will occur at the selected point,
or describe the result as cloud-to-ground lightning. A no-detection result means only that Rain
Alarm found no qualifying flash-area pixels in the successfully evaluated product and interval. It
must never be presented as an assurance that the area is safe.

The existing data-source constraints and EUMETSAT attribution in
[Data sources and constraints](DATA_SOURCES.md#optional-eumetsat-satellite-layers) remain
authoritative.

## Settings and permission behaviour

- The Lightning switch is persisted separately from **Rain notification**. Either notification
  type can be enabled without the other. It controls Lightning **alert eligibility**, not whether a
  configured widget can request and display lightning state.
- On first install and on upgrade from a version without this preference, Lightning is off.
- Enabling it uses Android's existing notification runtime-permission flow. If permission is
  denied, the switch returns to off and Settings explains that notification permission is needed.
- A transition from off to on begins alert eligibility unarmed. A detected episode may be shown,
  but a successful no-detection evaluation must occur before a later episode can notify.
- Enabling Lightning does not change the persistent Radar Lightning layer choice. Toggling the
  Radar layer does not change the alert switch.
- Disabling Lightning prevents lightning notifications and cancels lightning-only **alert** work.
  A configured widget mode/layout that requires lightning may still acquire and evaluate the same
  observations for display, but that result cannot notify or be folded into a rain notification.
- Disabling the switch does not clear widget display configuration, the persistent Radar Lightning
  layer choice or rain-alert state.
- If the alert switch is off and no configured widget currently requires lightning, perform no
  background Lightning acquisition or evaluation.
- Display-only evaluation can update shared acquisition checkpoints and widget cache, but it must
  not arm, disarm or otherwise advance the separate notification episode state.
- The setting and status text must be localized with the rest of Settings.

## Monitored targets and location resolution

A **monitored target** is an immutable snapshot used by one polling transaction. It contains a
stable target identity, display name, resolved latitude/longitude, target mode and provider
context. Targets can originate from the app alert selection or from a configured widget. Multiple
surfaces that resolve to the same target share acquisition. They share alert decisions only when
the relevant notification setting makes that target alert-eligible.

For every target transaction:

1. Resolve the target once.
2. Freeze that coordinate for every rain and/or lightning evaluator requested by the transaction.
3. When both are requested, use exactly the same frozen coordinate for both.
4. Evaluate and publish only against that same snapshot.
5. Discard a late result if the target configuration changed before publication.

The supported target modes are:

- **Fixed saved place** — resolve the current coordinates of that saved-place ID. Renaming the
  place changes its label without creating a new weather episode; materially changing its
  coordinates creates a new target identity for alert-state purposes.
- **Follow app selection** — resolve whichever saved place or virtual Current location the app is
  using when the transaction begins. It must not silently fall back to the startup-pinned place.

Virtual **Current location** can be evaluated only from a lawful fix obtained while the app was in
the foreground. Reuse the app's existing five-minute freshness limit; the worker must not request
a new location in the background. If the in-memory fix is missing, stale, lost after process death,
or permission has been revoked, every requested rain or lightning evaluation for that target is
unavailable. No old coordinate may be substituted and no alert may be sent. Rain Alarm continues
to request no background-location permission, foreground location service, exact alarm or
persistent location notification.

The target key used for persistence must distinguish different resolved coordinates and radius
configurations while allowing two widget instances that resolve to the same place/configuration to
deduplicate work and notifications.

## Acquisition and detection

### Frame discovery and freshness

- Read the product's advertised start, latest time and cadence from bounded EUMETSAT capabilities
  metadata, using the existing hardened parsing and HTTPS host allowlist.
- Use the advertised cadence when valid, with five minutes as the product's nominal cadence.
- The newest advertised frame must be no more than 30 minutes old and no more than five minutes in
  the future, matching the existing Lightning overlay freshness envelope. Otherwise the evaluation
  is unavailable.
- Store the last completely processed advertised frame identity per target/configuration. On each
  check, evaluate every new five-minute frame after that checkpoint through the latest fresh frame;
  a roughly 15-minute worker interval must not sample only the latest frame and miss intervening
  activity.
- Deduplicate exact product/time identities. Do not interpret a repeated advertised frame as a new
  observation or alert event.
- Advance the contiguous success checkpoint only through frames that were fetched, validated,
  decoded and evaluated. A missing middle frame remains retryable on the next transaction.

### Bounded local request

- Request only a bounded local WMS image around the target, large enough to contain the full
  configured circle plus the sampling margin needed for edge pixels. Do not download the regional
  Radar overlay set merely to make the alert decision.
- Preserve projected aspect ratio and request enough pixels for the effective ground sampling to
  classify the radius boundary consistently.
- Validate HTTPS host, response status and type, bounded byte size, PNG structure/checksums and
  exact expected dimensions before decoding, as the existing EUMETSAT path does.
- Confirm that the complete detection circle lies inside the product's advertised coverage. A
  clipped circle is unknown, even if the visible portion contains no activity.

### Geographic test

- A positive detection requires at least one classified flash-area sample whose geographic centre
  is at most the configured radius from the target.
- Test the true circular geodesic distance from the target, not merely membership in the square WMS
  bounding box. Boundary tests must cover different latitudes and the antimeridian.
- Pixel/classification logic must be shared by foreground and background evaluation. It must be
  based on the documented product rendering, not on a screenshot-specific colour guess.
- A valid PNG signature, HTTP success or wholly transparent image alone is not proof of a clear
  interval. A frame is successfully **No detection** only when the whole circle has known product
  coverage and the decoded product semantics support the absence conclusion.
- A positively classified flash area is useful even if another frame in the transaction is
  unavailable. Conversely, incomplete acquisition can never establish a clear interval.

### Evaluation outcomes

Each transaction produces one of these public-independent domain outcomes:

| Outcome | Meaning |
| --- | --- |
| `Detected` | At least one qualifying flash area occurred within the radius in a newly evaluated frame. |
| `NoDetection` | Every required new frame was successfully covered/evaluated and none qualified. |
| `NoNewFrames` | The latest fresh advertised identity was already processed. |
| `Unavailable` | Coverage, freshness, network, validation or decode could not support a conclusion. |

Network failure, provider no-data, stale capabilities, missing frames and malformed images are
`Unavailable`, never `NoDetection`. User-facing status may say **Lightning unavailable**; raw
provider errors remain internal diagnostics.

## Episode state and deduplication

Lightning uses an arming state separate from rain:

| Input | Armed before | Notification | State after |
| --- | --- | --- | --- |
| Complete `NoDetection` | either | none | armed |
| First `Detected` | yes | one lightning event | disarmed/active episode |
| Continuing `Detected` | no | none | disarmed/active episode |
| `Unavailable` or `NoNewFrames` | either | none | unchanged |

Apply this state chronologically to all newly discovered frames. Thus, a complete clear frame
followed by a detected frame can arm and trigger within one catch-up transaction. On a first-ever
check that contains only detected activity, record the active episode without notifying; a
successful no-detection evaluation is required before the first alert.

Persist, per target/configuration:

- armed/active-episode state;
- the last contiguous successful frame identity;
- bounded identities needed to reject duplicate positive frames;
- target event identity/evidence; and
- the radius/configuration version used to make the decision.

Persist notification delivery eligibility, last considered/consumed event and DND policy per
monitoring subscription. That delivery layer must not duplicate target evidence merely because two
widgets resolve to the same place.

State must survive process recreation and device restart. Updates to frame checkpoints, episode
state and notification decisions must be atomic so overlapping foreground and WorkManager checks
cannot both notify. App-alert work and multiple widgets resolving to the same target/configuration
must share one evidence record and emit at most one notification per target/event, while preserving
each subscription's delivery/consumed state. Removing one widget must not erase evidence still
referenced by another widget or by the app alert target.

## Rain interaction and notification content

When lightning is required by an enabled alert or widget display, it is evaluated for the same
frozen target as rain in one coordinated polling transaction. Their arming state remains
independent. Display-only lightning evaluation while its notification switch is off never enters
the notification decision path.

- If rain and alert-enabled lightning both newly qualify in the same transaction, issue one
  combined notification rather than two alerts.
- If rain was already notified and lightning begins later, Lightning can issue its own notification.
- If Lightning was already notified and a separately armed rain episode later approaches, Rain can
  notify without repeating Lightning as a new event. The current nearby-lightning fact may be shown
  as secondary context, but must not sound twice.
- A rain notification is eligible only after the separate rain state was armed by an earlier
  successful evaluation showing no rain anywhere in the complete 0–60 minute window. See
  [Widget alerts](WIDGET_REQUIREMENTS.md#alerts-and-notification-state).
- Likely-snow wording continues to come only from a provider that supports that classification.

For an alert-eligible widget subscription, notification delivery also honours its
[per-widget Do Not Disturb](WIDGET_REQUIREMENTS.md#per-widget-do-not-disturb). Quiet hours do not
stop Lightning observation acquisition, widget display or target-level episode processing. If a
qualifying Lightning event is first processed while that subscription is quiet, consume it for that
subscription without delivery; do not queue it or issue a catch-up notification when quiet hours
end. Continued activity remains part of the same consumed episode until a later successful
no-detection evaluation re-arms it. The global default-off Lightning notification switch remains a
prerequisite for any Lightning notification regardless of DND.

Example notification forms:

- **Lightning activity detected near Baltinglass**
- **Rain approaching Baltinglass · lightning activity detected nearby**

Content must include the monitored place name and remain concise in collapsed notification form.
The expanded form can state the 15 km distance. Notification IDs/PendingIntents must be unique per
target/event so a later alert cannot cause an older card to open the wrong place.

## Notification tap and temporary Radar state

Tapping a notification whose state includes lightning must:

1. open **Radar**, not merely the app's last tab;
2. show the exact monitored target snapshot used by the notification;
3. select a representable current time without starting playback unexpectedly; and
4. make Lightning temporarily visible without altering the persistent layer preference.

The hand-off must not change the startup-pinned place or any widget configuration. It should use a
transient Radar target rather than persistently changing the app's selected place beyond what is
strictly required to display the alert target. If the saved place was deleted after the alert, the
validated coordinate/name snapshot can still be shown for that navigation without recreating the
saved place.

### Temporary Lightning lease

- The temporary lease lasts **ten minutes by wall clock**, representing two nominal five-minute
  observations. It begins when the notification deep link is handled, not when the notification
  was originally posted.
- Navigation among Now, Radar, Places and Settings, app backgrounding and ordinary activity/process
  recreation do not cancel it.
- Persist the target identity and absolute expiry time. On restart, restore an unexpired lease and
  discard an expired one. Reopening the same alert may begin a fresh ten-minute lease.
- The lease is bound to the alert target. It is hidden while Radar displays another place and may
  reappear only if the original target is revisited before expiry. It must never leak to another
  place.
- A new lightning deep link for a different target replaces the single active transient lease.
- Expiry restores the user's persistent Lightning choice. It does not force Lightning off if the
  user had made it persistently on.

### Three-state Radar control

Radar's Lightning segment has three semantic states:

| State | Presentation | Tap result |
| --- | --- | --- |
| **Off** | Existing inactive treatment | Set persistent Lightning **On**. |
| **Temporary** | Active treatment plus a non-colour cue such as a small clock badge | Set persistent Lightning **On** and cancel the temporary expiry. |
| **On** | Existing persistent active treatment | Set persistent Lightning **Off**. |

If persistent On and a temporary lease both exist, **On** is the effective state. Turning the
persistent choice off while an unexpired matching lease exists reveals **Temporary** rather than
discarding that lease.

On first Radar entry through the lightning deep link, the bottom information rail shows
**Lightning shown temporarily · tap to keep on** for two seconds. The notice must not reappear on
ordinary tab navigation during the same lease. The Temporary icon needs a TalkBack label such as
**Lightning, temporarily on, double tap to keep on**; state cannot rely on colour alone.

## Foreground and background coordination

- This feature must work with no widget installed whenever the Lightning setting is enabled and a
  valid app alert target exists.
- A lightning-capable widget may keep display acquisition active while the alert switch is off. If
  neither that display demand nor an enabled Lightning alert exists, skip Lightning work entirely.
- Scheduled work is network-constrained and approximately 15-minute, not exact. Doze, battery
  controls and force-stop can defer or prevent it.
- While the app is visible in the foreground, redundant background/widget polling is suspended.
  Foreground refreshes run Lightning only when an enabled alert or matching widget display requires
  it, use the same frozen target/evaluator, and enter the atomic alert-decision path only when
  Lightning notifications are enabled. The result then updates matching widgets.
- “Open” means foreground and visible, not merely retained in Recents or kept in the process.
- When the app leaves the foreground, scheduled polling resumes without clearing checkpoints,
  re-arming an episode or generating a duplicate notification.
- Only one evaluation for a target/configuration may be in flight. A stale completion cannot
  overwrite a newer foreground or worker result.

## Privacy, disclosure and safety

- The bounded target coordinate and image request go directly to EUMETSAT over HTTPS. Rain Alarm
  still has no account, analytics or application server.
- The privacy disclosure and Settings **About the data** section must be updated before release to
  describe background Lightning requests, their purpose, cadence and provider.
- Do not log precise coordinates, WMS URLs containing precise bounds, or decoded local imagery.
- Keep cached local lightning images bounded and short-lived. Persist decisions/frame identities,
  not imagery, unless an existing bounded cache safely owns it.
- Revoking notification permission or disabling the setting prevents new alerts without changing
  Radar's persistent layer choice.
- This remains an experimental information aid. Missing activity can result from observation,
  coverage, publication, network or scheduling limitations.

## Acceptance criteria

Automated tests must cover at least:

- Settings placement, fresh-install/off migration default and independence from Radar's layer flag;
- alert-off widget display acquisition, display-only episode isolation, and no-demand/no-work cases;
- named/default radius propagation and circular inside/outside/boundary cases at several latitudes;
- coverage clipping, malformed/transparent/no-data images and stale/future metadata;
- chronological catch-up across at least three five-minute frames and duplicate frame identities;
- `NoDetection -> Detected -> Detected -> NoDetection -> Detected` episode transitions;
- unavailable checks preserving armed/disarmed state and checkpoints;
- atomic deduplication across worker/foreground execution and identical widget targets;
- combined rain/lightning, lightning-only-later and rain-only-later notification decisions;
- per-widget DND suppression, consumed episodes with no end-of-quiet catch-up, and later
  no-detection re-arming without interrupting observation/display;
- fixed, Follow-app and unavailable Current-location target resolution;
- deep-link target isolation, deleted-place snapshot handling and unique PendingIntents;
- ten-minute wall-clock expiry across navigation, backgrounding and process recreation;
- Off/Temporary/On tap transitions, restoration of the persistent state and TalkBack descriptions;
  and
- localized notification text that never describes the observation as a forecast or verified strike.

Device validation must confirm the temporary icon/badge remains legible in every app/map
appearance, the two-second information rail does not clash with Radar status, notification taps
open the intended place, and Android battery restrictions are described honestly.

## Non-goals

This version does not:

- predict lightning, strike probability or storm safety;
- distinguish intracloud from cloud-to-ground flashes;
- offer a radius-selection UI (the domain is only structured to permit one later);
- request background location;
- monitor continuously or promise five-/fifteen-minute delivery;
- make the EUMETSAT observation global where its advertised coverage does not apply; or
- make the Radar layer permanently active merely because Lightning alerts are enabled.
