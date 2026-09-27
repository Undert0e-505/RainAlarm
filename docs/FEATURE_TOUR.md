# Feature tour

Rain Alarm uses a two-stage contextual spotlight rather than a separate slideshow. A dark modal
scrim cuts out the bounds reported by the real composed control; a nearby card is placed above or
below that target using available safe-inset space. There are no connector lines or guessed screen
coordinates. Rotation, insets, font scale and appearance changes therefore update the measured
geometry instead of relying on timing delays.

## Flow and persistence

The versioned `feature_tour_v1` state has independent Now and Radar completion markers, written
synchronously before navigation:

- Now: selected-place switcher, complete Compass card, complete graph card, Radar navigation item.
- Radar: Play plus timeline, weather-layer group, Travel control.

The automatic tour is eligible only when the existing place-store migration proves a genuinely
fresh install. Upgrades default both markers to complete. It starts only after language selection
and one-shot permission onboarding have finished, Now is selected and the first target has reported
real layout bounds. Completing Now records that stage before selecting Radar; Radar waits until its
page has settled and its target is laid out. Skip, Back or tapping the scrim completes the whole
tour, so a skipped Radar stage cannot surprise the user later. Process recreation can restart the
current stage at step one. **Show app tour** in Settings resets both markers deliberately.

## Example presentation isolation

`FeatureTourScenario` is a pure in-memory presentation fixture. Its one-minute Now series is dry at
the opening instant, starts rain at minute 11 from the southwest, briefly reaches medium intensity
and then eases. Seven locally generated Radar frames move one procedural weather field northeast on a
quarter-hour-relative timeline. The chosen coordinate is used when available; otherwise the
existing safe presentation coordinate is copied without selecting or saving it. The Radar example
uses one cached, seeded intensity field rather than geometric lines or live tiles: broad irregular
areas, broken edges, dry gaps and detached showers share the app's cyan-to-blue radar palette with
only restrained medium-intensity purple cores. All seven frames translate that same field northeast,
so play and scrubbing remain coherent without regenerating pixels or flickering.

The fixture has no repository, transport, preference, WorkManager or notification dependency. Real
state can continue loading behind it, but the presentation does not write weather/radar caches,
saved places, location, alerts, providers, layers or playback preferences. Radar keeps the real
session attached but temporarily invisible, with a separate example cursor/play state, so ending
the tour restores the exact retained real session, frame and play/pause state without teardown.

## Interaction and accessibility

The overlay is above the retained pager, bottom navigation and MapLibre surface, blocking unrelated
map and page gestures. The highlighted control is explanatory rather than implicitly activated;
only the card's localized action and persistent **Skip tour** action advance or dismiss it. The pane
announces the step title/body and step count, requests logical focus for its card, exposes full-size
buttons and respects the app's resolved palette. The outline is static, so no essential information
or progress depends on animation and Android reduced-motion settings need no substitute effect.
