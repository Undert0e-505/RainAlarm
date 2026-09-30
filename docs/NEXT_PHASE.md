# Road to 1.0

Rain Alarm 0.9.0 is feature-complete for its intended scope: Now, multi-provider Radar, saved
places, independent home-screen widgets, foreground Travel, rain/Lightning alerts, appearance
profiles, localization and contextual onboarding. Work toward 1.0 now prioritizes reliability and
validation rather than additional features.

## Release priorities

1. **Real-device bug fixing**
   - Exercise repeated foreground/background, process recreation, provider changes, map-style
     reloads and poor-network recovery on representative Android devices.
   - Keep last-good weather visible through replacement work and preserve truthful loading,
     preparing and unavailable states.
2. **Travel follow smoothness**
   - Improve and validate real-device marker/camera cadence. Rain Alarm asks Android for frequent
     foreground fixes, but some phones currently present movement at roughly one update per second
     and the best-effort visual tracker does not consistently improve that on physical hardware.
   - Preserve AUTO's exact wall-clock timeline, bounded weather anchors and the rule that only real
     accepted fixes may affect weather, alerts, places or persistence.
3. **Widget and notification reliability**
   - Validate saved-place widget refresh, minute presentation updates, quiet hours and rain/
     Lightning episode deduplication across Samsung-, Pixel- and other OEM launchers.
   - Record Doze, force-stop, reboot, battery-restriction and transient-network behaviour without
     promising exact WorkManager timing.
4. **Provider validation**
   - Continue checking coverage, freshness, fallback and local-motion behavior across MeteoGroup,
     OPERA, RainViewer, Open-Meteo and EUMETSAT without forcing independent sources to agree.
   - Reconfirm provider terms, attribution, capacity and store-distribution suitability before
     wider distribution. Treat station comparisons, registered EUMETSAT per-flash data and any
     commercial strike source as separate future adapters requiring access, licence, latency,
     bandwidth and operating-cost review.
5. **Accessibility and release QA**
   - Complete TalkBack traversal, switch access, font scale, contrast, reduced-motion, landscape,
     tablet and foldable checks.
   - Add device screenshots for loading, clear, rain, partial, stale and unavailable states and a
     normal Linux CI path for tests, lint, assembly and APK provenance.

## Maintained engineering references

- [Widget behavior](WIDGET_REQUIREMENTS.md)
- [Lightning activity alerts and Radar hand-off](LIGHTNING_ALERT_REQUIREMENTS.md)
- [Data sources and constraints](DATA_SOURCES.md)
- [Development and build](DEVELOPMENT.md)

## Explicitly out of scope

Accounts, ads, analytics, billing, background location, opaque machine learning and copied code or
assets from the retired reference application remain out of scope.
