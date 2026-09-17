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
3. Home-screen glance
   - Add a Glance widget after the status and freshness language is stable.
   - Show the selected place and timestamp so cached data cannot look live.
4. Accessibility and polish
   - TalkBack traversal and content-description review.
   - Font-scale, contrast, reduced-motion, tablet, foldable, and landscape QA.
   - Screenshot tests for the principal status states.

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
