# Open-radar motion estimates

OPERA and RainViewer publish observations but do not supply forecast frames. Rain Alarm therefore
builds a deterministic, spatially varying motion estimate on the device. MeteoGroup/DTN is not part
of this path: its supplied velocity and forecast products remain unchanged.

## Production pipeline

The implementation follows the deterministic Lucas–Kanade structure and starting parameters in
the pySTEPS 1.21.5 extrapolation reference, adapted to Rain Alarm's provider-calibrated severity
raster rather than treating display colour as physical rain rate:

- the latest three regular observations, at five-minute cadence for OPERA and the published cadence
  (normally ten minutes) for RainViewer;
- a maximum 384-pixel OPERA analysis raster (the 790×724 France raster becomes 264×242 after
  integer max pooling), or 256 pixels for RainViewer;
- `log1p(31 * intensity)` feature imagery normalized to 8-bit, with precipitation threshold 0.03
  and a 3×3 morphological opening;
- spatially balanced Shi–Tomasi feature detection in non-overlapping 64×64 analysis tiles, with at
  most 64 corners per tile, quality 0.01, minimum distance 10 pixels and block size 5;
- pyramidal Lucas–Kanade tracking with a 50-pixel window, pyramid level 3, 10 iterations and epsilon
  0.01;
- a reverse pass with maximum forward/backward error 1.5 pixels, a five-pixel edge buffer and
  maximum pair displacement of 72 analysis pixels;
- vectors pooled from both adjacent pairs, locally cleansed using up to 30 spatial neighbours within
  64 analysis pixels, then declustered at 20 pixels; and
- a 64×64 velocity texture with adaptive signed RG8 range, using 5% headroom above the
  99th-percentile magnitude, rounded upward to 0.25 pixels and capped at 96 pixels.

There is deliberately no whole-domain direction filter. A small coherent storm can move differently
from a larger rain band and remains a legitimate local motion regime. Disconnected supports are
kept separately. Inverse-distance interpolation uses up to 20 local contributors (`power=0.5`,
offset 0.5), and both contributors and output cells must remain within an adaptive 24–48
analysis-pixel corridor around observations and their forward trajectories. This gives the
backward-warp renderer enough support while preventing a distant system from bleeding across an
unsupported clear gap.

OPERA pixels with less than 50% source coverage remain non-finite during motion analysis, so
unknown coverage cannot become dry or provide tracking features. RainViewer retains its published
coverage semantics and the tracker buffers image edges.

## Confidence and fallback

Native motion is used only when history is regular, at least eight locally coherent vectors remain
and local support produces sufficient confidence. Insufficient or irregular history, weak support,
OpenCV initialization failure, cancellation-safe tracker failure or degenerate motion falls back
automatically to Rain Alarm's previous bounded block matcher. This fallback is internal; there is no
Settings choice and no additional network request.

Observed-frame interpolation continues to use each adjacent pair's established field. The native
aggregate changes only the forecast field produced from the latest observation, keeping Radar,
Now, alerts and widgets on the same estimate.

## Dependency and resource bounds

Rain Alarm uses the official OpenCV Android Maven AAR `org.opencv:opencv:4.12.0`. OpenCV is licensed
under Apache License 2.0; see [OpenCV licensing](https://opencv.org/license/) and the
[Maven artifact](https://central.sonatype.com/artifact/org.opencv/opencv/4.12.0). Release
minification retains its Java/native bindings.

Only the latest three bounded analysis grids are needed. A three-frame 384×384 ceiling is about
1.7 MiB per tier; the France plan's 264×242 working size is about 0.73 MiB. Dense output is
64×64×2 bytes (8 KiB) per tier. Every OpenCV `Mat` is released deterministically, and estimation
runs in the existing cancellable background preparation context.

## Validation

Pure JVM tests cover cadence selection, local multivariate cleansing, two simultaneous opposing
motion regimes (including a weaker minority system), bounded interpolation, unsupported gaps,
adaptive encoding, confidence and exact fallback. Android instrumentation is required to exercise
the actual OpenCV native library; it covers subpixel translation, stationary/clear/insufficient
inputs, no-data edges, imbalanced systems and a real OPERA session. MeteoGroup wiring tests ensure
the provider-supplied forecast path remains separate.

The algorithm is based on pySTEPS' documented
[Lucas–Kanade and semi-Lagrangian extrapolation example](https://pysteps.readthedocs.io/en/stable/auto_examples/extrapolation/plot_extrapolation_nowcast.html),
but Rain Alarm's Android implementation is independent Kotlin/OpenCV code and does not embed
Python or pySTEPS.
