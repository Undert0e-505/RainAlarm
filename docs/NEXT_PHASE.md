# Next phase

Rain Alarm now covers the answer-first Now view, multi-provider Radar, foreground Travel mode,
place management, automatic appearance profiles, installed-app localization and contextual
onboarding. The work below remains deliberately deferred until real-world use justifies it.

## Product work

1. **Radar validation**
   - Tune motion thresholds against representative weather and coverage cases.
   - Evaluate storm growth/decay modelling only with defensible validation data.
   - Add screenshot and device tests for GLES overlay alignment, camera moves, lifecycle teardown
     and style reloads.
2. **Alert refinement**
   - Validate ETA ranges and per-place deduplication against real rain bands.
   - Consider configurable quiet hours after notification usability testing.
3. **Home-screen glance — maybe**
   - Consider an Android Glance widget only after demonstrated demand. It could show the latest
     cached state for the active selected place, its last-check time and next-rain estimate, with a
     manual refresh action.
   - A widget would not keep the app continuously running, improve WorkManager guarantees, grant
     background location, monitor every saved place or bypass force-stop/Doze. Rain Notification
     already checks the active saved place approximately every 15 minutes while the app is closed;
     Current location still requires a sufficiently fresh lawful foreground fix.
4. **Accessibility and polish**
   - Continue TalkBack traversal and content-description review.
   - Expand font-scale, contrast, reduced-motion, tablet, foldable and landscape QA.
   - Add screenshot coverage for principal loading, clear, rain and unavailable states.

## Data and reliability

- Continue validating provider-specific motion estimates and coverage handling without forcing
  independent radar composites to agree.
- Add explicit retry/backoff and provider-health telemetry only with a support and privacy plan.
- Add cache-expiry messaging and offline tests for forecast, radar and ancillary layers.
- Reconfirm MeteoGroup/DTN, Open-Meteo, RainViewer, EUMETNET OPERA, EUMETSAT, OpenFreeMap and
  OpenStreetMap terms, attribution, capacity and store-distribution suitability before wider
  distribution.

## Engineering

- Add a small dependency-injection container if provider construction or environment-specific
  configuration grows further.
- Add instrumented tests for permission denial, disabled providers, stale last-known fixes,
  process restart persistence and MapLibre style reloads.
- Add CI on a normal Linux Android environment for assembly, lint, pure unit tests and APK
  provenance. The current development host retains the direct JUnitCore task only as a local
  AF_UNIX test-worker workaround.
- Add automated dependency/licence reports and stronger reproducibility checks around the existing
  release-signing workflow.

## Explicitly out of scope

Accounts, ads, analytics, billing, background location, opaque machine learning and copied code or
assets from the retired reference application remain out of scope.
