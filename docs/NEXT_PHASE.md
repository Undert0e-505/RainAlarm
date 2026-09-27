# Next phase

The initial vertical slice deliberately proves the answer-first experience,
provider boundaries, a foreground location flow, and observed radar playback.
The following work is deferred until those behaviours have been manually
accepted on representative devices.

## Product work

1. Radar validation
   - Tune motion thresholds against representative weather and coverage cases.
   - Evaluate storm growth/decay models only with defensible validation data.
   - Add screenshot and device tests for GLES overlay alignment, camera moves,
     lifecycle teardown, and style reloads.
2. Alert refinement
   - Validate ETA ranges and deduplication across real rain bands.
   - Consider user-configurable quiet hours after notification usability tests.
3. Home-screen glance — deferred MAYBE (not part of the current items 5–13 build)
   - Consider a Glance widget only after demonstrated demand. It would show the
     cached/latest state for the active selected place, the last-check/freshness
     time and next-rain estimate, refreshed by the existing worker or a manual
     action; the place and timestamp must be prominent so cached data cannot
     look live.
   - It would not keep the app continuously running, improve WorkManager
     guarantees, grant background location, monitor every saved place, or bypass
     force-stop/Doze. The existing Rain Notification already checks the active
     saved place approximately every 15 minutes (inexact) while the app is
     closed and alerts for qualifying rain within 60 minutes; Current location
     still requires a sufficiently fresh lawful foreground fix.
4. Accessibility and polish
   - TalkBack traversal and content-description review.
   - Font-scale, contrast, reduced-motion, tablet, foldable, and landscape QA.
   - Screenshot tests for the principal status states.
5. Radar entry focus transition
   - On genuine Radar entry, coordinate one restrained ~1-second transition:
     animate the selected-location marker from slightly oversized to its normal
     pin-head/dot size, ease from a slightly wider map view into the exact
     remembered/desired camera, and settle the centred title from only slightly
     enlarged to normal in sync.
   - Preserve the final camera exactly, respect Android reduced-motion and
     animation-scale settings, and never replay on data refresh or Compose
     recomposition.
   - Acceptance must cover no post-transition marker jump, weather/radar reload,
     blank-map flash, or disruption to Travel/Follow mode or current-location
     acquisition.
6. App localization
   - Keep English as the canonical source and fallback; initially ship Dutch,
     Flemish / Dutch (Belgium), German, French, Welsh and Irish, with Nordic
     languages considered later from demand. Use `nl` as the Dutch base, permit
     `nl-BE`/Android `values-nl-rBE` vocabulary or phrasing overrides where
     needed, and present it as `Nederlands (België)` rather than inventing a
     language code. Welsh is full `cy`/Android `values-cy` support; Irish is
     full `ga`/Android `values-ga` support and is presented as `Gaeilge` (never
     `Irish Gaelic`). Installed-app localization is the initial scope; decide
     README and release-note translations separately.
   - Externalize every user-facing Android string, including screens, settings,
     loading/status/error text, dialogs, notifications/channels, accessibility
     descriptions, plurals and parameterized messages. Preserve provider and
     product names, legal attribution, URLs and raw place names unchanged.
   - Follow the device/app locale with versioned static Android resources and
     English fallback, retaining locale-correct plurals, dates, times and
     numbers plus existing unit preferences. Reuse Dutch base strings for
     `nl-BE` when wording is identical. English may temporarily fill missing
     development resources, but releases must pass parity checks. Define a
     glossary for rain intensity, ETA, coverage, Travel/Follow mode and provider
     availability across languages and locale variants.
   - Native-speaker review is welcome later but does not block delivery. In its
     place, require placeholder/plural parity and missing-string checks,
     pseudo-localization, small-screen/long-text UI screenshots or Compose
     checks, notification and accessibility checks, cross-language consistency
     review, and explicit `nl-BE` override completeness/drift checks; do not use
     unreviewed runtime machine translation.
7. Consolidate Places and default-place behavior
   - Before coding, review a small user flow/wireframe for the currently split
     Places and Settings behavior. Keep Places as the primary, list-focused
     destination with no persistent map: search and **Choose on map** at the
     top, a permanent Current location row with locating/available/unavailable
     state, then manually ordered Saved places. Move startup choice here and
     remove the Settings duplicate (or retain only a useful read-only deep link).
   - Name the independent states **Selected now** (immediately drives Now/Radar)
     and **Opens on startup** (cold-start choice), avoiding ambiguous “default”
     UI wording. Show a checkmark for selection and a separate non-conflicting
     startup indicator; do not reuse the provider-pin metaphor without a
     deliberate icon decision, and never change one state as a side effect of
     the other.
   - **Choose on map** opens a temporary full-screen pan/zoom picker with a
     centred crosshair, coordinate confirmation and editable name, then returns
     to Places. Unify it with Radar long-press place creation rather than keeping
     two behaviors. Search results should add locality/region/country/postcode
     context where available and detect duplicate or near-duplicate coordinates.
   - Make ordering discoverable with visible drag handles. Give each saved place
     a compact overflow for Rename, **Opens on startup** and Delete, with Undo
     after deletion. Current location has an explicit **Save this location**
     action with an editable name.
   - If opened from Now/Radar's switch control, selecting a place returns to that
     caller; entry through bottom navigation remains on Places. Keep the screen
     lean by excluding weather previews, folders, notes and persistent provider
     badges.
   - Cover deleting, renaming or reordering the startup place; denied/unavailable
     live location; no saved places; upgrades/preference migration; and explicit
     fallback behavior. Accept when Places alone answers “which place am I
     viewing now?” and “what opens next time?”, with one authoritative control
     for each state. This remains subject to the wireframe/usability checkpoint,
     not yet a fixed UI specification.
8. Configurable automatic day/night appearance
   - Apply automatic Day/Night profiles across every appearance surface: app
     surround/system bars, Radar map, Now compass card and Now intensity/graph
     card. Users edit independent **Day** and **Night** profiles, choosing each
     surface's concrete Light/Dark and, where supported, Slate appearance.
   - Allow card/map **Follow app** only where resolution is unambiguous; exclude
     recursive Auto/Follow combinations that could form cycles. Sensible new
     defaults are light app/map with following/light cards by day and dark
     app/map with following/dark cards by night, while still permitting mixes
     such as a dark app with Slate map.
   - Reuse the existing cached selected-place sunrise/sunset state—no new
     endpoint, duplicate request or parallel solar subsystem. Current/Travel
     recalculates on meaningful location/date changes and schedules the next
     solar boundary instead of polling. Genuine missing/stale or polar no-event
     data uses a deterministic platform-night-mode or local-clock fallback, with
     stable boundary scheduling or hysteresis to prevent rapid toggling.
   - Resolve and apply the whole profile atomically at a boundary: no mismatched
     intermediate theme, white flash, blank interval or bright surround around
     a dark map, especially while driving. Preserve camera/zoom, selection,
     playback/timeline, overlays, Follow/Travel state, loaded data and coverage-
     mask darkness without triggering weather/provider reloads. Update system-
     bar icon contrast plus every control, label, attribution and map colour for
     the resolved app/map styles.
   - Settings must edit and preview both profiles regardless of which is active,
     clearly identify the active profile and next switch time, and fit the
     existing Appearance layout without confusion. Review that UX before coding.
   - Migration must map existing independent appearance choices into profiles
     without surprising current users; Auto remains opt-in unless UX review
     explicitly decides otherwise. Test fallback and hysteresis, sunrise/sunset,
     polar dates, timezone/location change, Travel mode, process restart and
     reduced-motion handling for any transition.
9. Restructure Settings “About the data”
   - Replace the long closing paragraph with one compact, initially collapsed
     **About the data** row/card with a clear chevron and expanded state. Keep the
     installed app version visible at all times in the normal collapsed Settings
     view, independent of and outside the expansion. Prefer a stable compact
     location near the information section/footer with accessibility semantics;
     leave its exact layout for wireframe review.
   - Beside the version, keep a compact **GitHub** or **Source code** link visible
     while collapsed, targeting the canonical
     `https://github.com/Undert0e-505/RainAlarm` repository without displaying
     the raw URL. Open it through the normal external-browser intent rather than
     a WebView, with an adequate tap target, focus semantics, the accessible
     label “Open Rain Alarm source repository”, and graceful no-handler behavior.
   - Expand into scannable source-based sections for Radar providers
     (MeteoGroup/DTN, OPERA and RainViewer), Map, Open-Meteo model/current
     weather and Wind, EUMETSAT Clouds/Lightning, search/geocoding/postcodes,
     and privacy/network behavior; combine only where that stays clearer. Each
     section gets a short plain-language description, relevant attribution,
     licence/limitations and clickable source/terms link.
   - Preserve every required credit. This Settings presentation does not replace
     MapLibre/OpenStreetMap's required on-map attribution. At wireframe review,
     decide whether any per-source accordions help without excessive nesting.
   - Expansion must be local UI state only: no network request, unnecessary
     persistence, scroll disruption or recomposition side effect. Make the full
     row tappable and expose TalkBack expanded/collapsed state, meaningful
     headings, link semantics and logical keyboard focus order.
   - Test small screens, large fonts, light/dark themes, reduced-motion-aware
     expansion, link targets (including the exact canonical repository URL) and
     attribution completeness. Accept when ordinary Settings is materially
     shorter, named sources are quick to find, and no credit or important
     limitation has disappeared.
10. Add horizontal swipe navigation
   - Introduce a new synchronized phone pager/navigation state in the order
     Now → Radar → Places → Settings. Bottom navigation remains visible,
     authoritative and accessible; taps and swipes update the same state, with
     no first/last-page wrapping. Normal noninteractive areas accept left/right
     swipes with a directional transition. Wide/tablet rails may remain
     tap-driven unless UX review finds paging valuable.
   - Radar must preserve MapLibre one-finger pan, pinch and rotate/zoom: an
     ordinary map drag must never also change pages. Permit only deliberate
     inward page swipes beginning in narrow left/right map-edge zones positioned
     clear of Android system-back insets, with horizontal dominance plus tuned
     distance/velocity thresholds. Cancel for vertical movement, multi-touch /
     pinch, ambiguity or a short drag, restoring the camera after cancellation.
   - The Radar header, safe outer gutter and other non-map/noninteractive space
     can use the ordinary pager gesture. Add a subtle discoverability cue only
     if testing proves necessary; do not add persistent clutter by default.
   - Interactive gesture islands always win: radar timeline/scrubbing/playback,
     Now graph, Settings sliders/switches, Places reorder/map picker, dialogs and
     horizontally interactive accessibility controls must not page accidentally.
   - Preserve destination state and expensive resources: no provider/radar
     reload, camera/timeline/playback reset, Travel/Follow interruption, overlay
     refetch, duplicate Now-entry refresh or loss of in-progress place/search
     state. Apply existing entry policies exactly once only when a destination
     genuinely becomes selected.
   - Respect reduced motion/platform animation scale and TalkBack, exposing page
     position and standard accessibility actions while bottom navigation remains
     usable. Test Android gesture and three-button navigation, both directions,
     edge thresholds, phone sizes, future RTL behavior, rapid swipes, lifecycle /
     process restoration and every interactive conflict.
   - Require emulator/manual gesture tuning before finalizing edge widths,
     distances or velocities; those constants are deliberately not fixed here.
11. Always-available Travel mode control
   - Keep the Travel/Follow arrow visible in its established Radar top-row
     position for saved places and virtual Current alike, retaining its
     background-free styling: normal map-control colour while inactive and the
     established accent while active.
   - An inactive press is one atomic intent: select virtual live Current, reuse
     or request a precise foreground fix, recenter, and enable Travel/Follow. It
     must neither save/create a place nor change the saved **Opens on startup**
     selection. Do not reset playback, notifications or already usable data.
   - While permission/fix is pending, retain the normal Radar screen, loading
     arc and consolidated **Location loading** status; enter and recenter when a
     fix arrives. Denial or timeout stays nonintrusive and leaves the control
     available for retry, never opening a separate failure screen.
   - While active, show a small persistent **Travel mode** label beside the
     bottom map information area/icon—not a transient toast. Define a wireframe
     rail/priority layout so it coexists without overlap with attribution,
     loading/unavailable states and transient renderer/provider notices.
   - Pressing the active arrow stops following, screen-on and Travel automation,
     but keeps Current selected and the camera where it is. Selecting a saved
     place also exits consistently. Preserve continuous precise foreground
     fixes, recentering, keep-awake and cadence-aware data refresh while active.
   - Localize Travel terminology and active/inactive content descriptions and
     ensure contrast in every map appearance. Test every saved/provider and
     permission state, slow/no fix, repeated taps, exit/place selection,
     lifecycle/process restoration, page swiping, day/night switching and
     compact layouts.
12. Align Radar timeline labels with ticks
   - Centre every time label directly beneath its tick using the same exact x
     coordinate. Do not clamp or offset label text independently; where an edge
     label would overflow, inset the tick-and-label anchor together so the whole
     centred label remains within timeline bounds.
   - Use clock-aligned 15-minute ticks at :00, :15, :30 and :45, including only
     boundaries inside the real Radar time domain. Label every tick at ordinary
     phone width/font scale. On constrained layouts, retain every tick mark but
     use measured collision detection to omit alternate labels rather than
     overlap, shrink excessively or shift a label away from its tick.
   - First/last clipping follows the same rule: inset the tick-and-label anchor
     together only when needed to fit. Measure rendered text or use centre-
     aligned layout rather than fixed character-width assumptions.
   - Preserve observed/forecast bar geometry, cursor behavior and scrub mapping;
     provider frame cadence and time mapping remain independent of this visual
     tick cadence. Cover :00/:15/:30/:45, normal/narrow/large-font layouts,
     12/24-hour and localized labels, varying digit widths and future RTL. Add
     light/dark screenshot or layout tests asserting label-centre and tick-x
     alignment within a small pixel tolerance.
13. Enlarge Compass rain/motion status
   - Double the current font size of the shared bottom-centre Compass status
     slot (`No rain`, `From SW`, or equivalent rain/motion wording), giving every
     state the same prominence while preserving its intended centre alignment
     and baseline.
   - Do not increase card height or move/shrink the compass, temperature disc,
     sunrise/sunset block, weather metrics or graph card; use existing whitespace.
     Keep the slot single-line and geometrically stable so state changes do not
     make the card jump.
   - Target a true 2× size on normal supported phones. For genuinely constrained
     width, large fonts or localized strings, prefer concise translations and
     measured fitting, with bounded downscaling only as a last resort—never
     clip, wrap or overlap other content.
   - Retain existing semantic colour/intensity behavior and accessibility text;
     this changes hierarchy, not weather logic. Add screenshot/layout coverage
     for every status and transition, light/dark/Slate cards, small devices,
     font scaling, and planned Dutch, German, French, Flemish, Welsh and Irish.

## Data and reliability

- Evaluate an OPERA/EUMETNET-derived European radar service or a self-hosted
  composite for production reliability. This requires licensing, coverage,
  tile-generation, hosting, and operational decisions.
- Add explicit retry/backoff and provider health telemetry only with a
  privacy-preserving, opt-in design.
- Add cache expiry messaging and offline tests for forecast and radar.
- Reconfirm MeteoGroup/DTN, Open-Meteo, RainViewer, OpenFreeMap, and OpenStreetMap terms,
  attribution, capacity, and store-distribution suitability before release.

## Engineering

- Add a small dependency-injection container if provider construction or
  environment-specific configuration grows further.
- Add instrumented tests for permission denial, disabled providers, a stale
  last-known fix, process restart persistence, and MapLibre style reloads.
- Add CI on a normal Linux Android environment for assemble, lint, pure unit
  tests, and APK provenance. The current development host cannot open Gradle's
  local selector pipe for test workers; the repository includes a direct
  JUnitCore task solely as a deterministic host workaround.
- Establish signing, reproducible release builds, dependency/license reports,
  and a privacy policy before publishing.

## Explicitly out of scope for the initial slice

Accounts, ads, analytics, billing, background location, opaque machine
learning, and copied code or assets from the retired reference application
remain out of scope.
