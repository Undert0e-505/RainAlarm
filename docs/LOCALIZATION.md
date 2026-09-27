# Installed-app localization

English (`values`) is the canonical source and runtime fallback. Rain Alarm ships
versioned static Android resources for Dutch (`nl`), Dutch for Belgium (`nl-BE`),
German (`de`), French (`fr`), Welsh (`cy`) and Irish (`ga`). Android's app/device
locale selection chooses the resource set; the app does not use runtime machine
translation. `nl-BE` inherits the complete Dutch base and contains only genuine
Belgian wording overrides. Its user-facing name is **Nederlands (België)**. Irish
is labelled **Gaeilge**, never “Irish Gaelic”.

A fresh install presents the in-app language chooser before location or
notification permission onboarding, with Device language preselected. The same
chooser is available in Settings above the always-visible version/source row.
Both use AndroidX per-app locales; Device language clears the app override.

The installed-app scope includes screens, settings, loading/error/status language,
dialogs, notifications and channels, accessibility descriptions, plurals and
parameterized messages. Provider/product names, legal attribution, URLs and raw
place names remain verbatim. Dates, clocks, quantities and numbers use the active
locale while retaining the user's Android 12/24-hour clock preference.

## Weather terminology glossary

| Concept | English | Dutch / nl-BE | German | French | Welsh | Irish |
| --- | --- | --- | --- | --- | --- | --- |
| Light rain | Light | Licht | Leicht | Faible | Ysgafn | Éadrom |
| Medium rain | Medium | Matig | Mittel | Modérée | Cymedrol | Measartha |
| Severe rain | Severe | Zwaar | Stark | Forte | Trwm | Trom |
| Estimated arrival | Rain in … min | Regen over … min | Regen in … Min. | Pluie dans … min | Glaw mewn … mun | Báisteach i gceann … nóim |
| Radar coverage | Radar coverage | Radardekking | Radarabdeckung | Couverture radar | Cwmpas radar | Clúdach radair |
| Travel mode | Travel mode | Reismodus | Reisemodus | Mode Voyage | Modd teithio | Mód taistil |
| Provider unavailable | Provider unavailable | Provider niet beschikbaar | Anbieter nicht verfügbar | Fournisseur indisponible | Darparwr ddim ar gael | Soláthraí gan fáil |

Keep concise constrained-UI variants consistent with the checked string resources;
this table documents meaning rather than overriding those exact strings. “Travel”
is the user-facing mode; “follow” may describe its camera behaviour in technical
documentation but must not become a second UI term.

## Quality gates

- Full locales must have the same string/plural keys, placeholder signatures and
  plural quantities as English. `nl-BE` overrides must remain a compatible subset
  of the Dutch base and must not silently drift.
- Debug builds expose Android pseudo-locales for expansion/bidirectional testing.
- Test constrained screens and large text for truncation or overlap, especially
  Radar status rails, Compass status, Places actions, profile editors and About.
- Exercise localized notification title/summary/detail, channel text and TalkBack
  descriptions as well as visible screen copy.
- Native review can improve future wording but does not block delivery; changes
  remain reviewed, versioned static resources rather than live translation.

README and release-note translation is a separate documentation decision. The
current product scope is the installed app.
