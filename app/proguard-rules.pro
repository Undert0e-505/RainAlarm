# Proj4J's Registry stores projection Class objects and calls Class.newInstance().
# R8 sees the Class references but otherwise strips these no-arg constructors;
# UK point sampling then fails immediately after the first radar JPEG is loaded.
# Keep only the projections configured in RegionalRadarAreas, not the whole library.
-keepclassmembers class org.locationtech.proj4j.proj.LongLatProjection { public <init>(); }
-keepclassmembers class org.locationtech.proj4j.proj.TransverseMercatorProjection { public <init>(); }
-keepclassmembers class org.locationtech.proj4j.proj.StereographicAzimuthalProjection { public <init>(); }
-keepclassmembers class org.locationtech.proj4j.proj.SwissObliqueMercatorProjection { public <init>(); }
-keepclassmembers class org.locationtech.proj4j.proj.LambertAzimuthalEqualAreaProjection { public <init>(); }
