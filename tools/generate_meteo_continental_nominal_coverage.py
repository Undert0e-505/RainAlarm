"""Generate nominal coverage envelopes for four continental DTN/Meteo feeds.

This is an offline maintainer tool; it is never run by the Android application.
It requires Python 3 with ``pyproj`` and ``shapely`` installed.

The DTN legacy products publish neither their input-radar membership nor a
validity mask. Black pixels in their radar JPEGs mean both dry and unknown, and
their velocity images contain no usable no-data sentinel. Consequently these
are evidence-backed *nominal* national-network approximations, not DTN masks.

Evidence and provenance (retrieved 2026-09-25):

* ``nl`` uses the eight contributors explicitly named in KNMI's 2025
  real-time composite description. KNMI, RMI and DWD publish the ranges used.
* ``de`` uses DWD's current 17-site operational network. DWD documents that
  its qualitative PG/PM composites are assembled from volume-scan products;
  those precipitation-reflectivity volume scans extend to 180 km (the separate
  terrain-following near-surface PRECIP scan extends to 150 km).
* ``fr`` uses the 26 active Météo-France metropolitan/Corsican stations from
  the 31 stations in its 18 March 2025 public-radar specification. The current
  OPERA snapshot marks five listed sites inactive; those are excluded. OPERA's
  current ``maxrange`` field is 256 km for every included station.
* ``ch`` uses the five current sites and 246 km long-range scans documented in
  MeteoSwiss Technical Report 284 (2025).

Exact WGS84 coordinates, current status and OPERA ``maxrange`` fields were
cross-checked against the current official EUMETNET OPERA database snapshot:
https://www.eumetnet.eu/wp-content/themes/aeron-child/observations-programme/current-activities/opera/database/OPERA_Database/Data/OPERA_RADARS_DB_25092026.json

National sources and limitations are recorded in docs/DATA_SOURCES.md. No
cross-border contributors are inferred for DE/FR/CH because DTN does not
publish membership for these commercial composites. Each range union is
clipped to the exact native raster footprint configured by the app.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

from pyproj import CRS, Geod, Transformer
from shapely.geometry import Point, Polygon
from shapely.geometry.polygon import orient
from shapely.ops import transform, unary_union
from shapely.validation import explain_validity


GEOD = Geod(ellps="WGS84")
WGS84 = CRS.from_epsg(4326)
CIRCLE_STEP_DEGREES = 1.0
# Match RegionalProjectionMesh's 24 cells / 25 perimeter vertices so generation and rendering
# use the same piecewise-geographic source-raster boundary.
FOOTPRINT_EDGE_SAMPLES = 25
SIMPLIFY_TOLERANCE_METRES = 500.0
MAX_ALLOWED_ERROR_METRES = 1_000.0
GEOMETRY_VERSION = "2026-09-25"


@dataclass(frozen=True)
class RadarSite:
    name: str
    latitude: float
    longitude: float
    range_km: float


@dataclass(frozen=True)
class ControlPoint:
    name: str
    latitude: float
    longitude: float
    expected_covered: bool


@dataclass(frozen=True)
class Area:
    id: str
    description: str
    raster_width: int
    raster_height: int
    pixel_width_metres: float
    pixel_height_metres: float
    pixel_offset_x: float
    pixel_offset_y: float
    native_projection: str
    work_projection: str
    sites: tuple[RadarSite, ...]
    controls: tuple[ControlPoint, ...]


NL_SITES = (
    RadarSite("Den Helder", 52.9528, 4.7906, 320.0),
    RadarSite("Herwijnen", 51.8371, 5.1380, 320.0),
    RadarSite("Jabbeke", 51.1919, 3.0641, 300.0),
    RadarSite("Houthalen-Helchteren", 51.0702, 5.4054, 200.0),
    RadarSite("Wideumont", 49.9135, 5.5044, 250.0),
    RadarSite("Borkum", 53.5640, 6.7482, 180.0),
    RadarSite("Essen", 51.405649, 6.967111, 180.0),
    RadarSite("Neuheilenbach", 50.109656, 6.548328, 180.0),
)

DE_SITES = (
    RadarSite("Borkum", 53.5640, 6.7482, 180.0),
    RadarSite("Boostedt", 54.0043, 10.0468, 180.0),
    RadarSite("Rostock", 54.1757, 12.0580, 180.0),
    RadarSite("Essen", 51.4055, 6.9669, 180.0),
    RadarSite("Flechtdorf", 51.3112, 8.8020, 180.0),
    RadarSite("Hannover", 52.4600, 9.6945, 180.0),
    RadarSite("Ummendorf", 52.1601, 11.1761, 180.0),
    RadarSite("Prötzel/Berlin", 52.6486, 13.8580, 180.0),
    RadarSite("Neuheilenbach", 50.1097, 6.5483, 180.0),
    RadarSite("Offenthal", 49.9847, 8.7129, 180.0),
    RadarSite("Neuhaus", 50.5001, 11.1351, 180.0),
    RadarSite("Dresden", 51.1246, 13.7686, 180.0),
    RadarSite("Feldberg", 47.8736, 8.0039, 180.0),
    RadarSite("Türkheim", 48.5853, 9.7828, 180.0),
    RadarSite("Memmingen", 48.0421, 10.2192, 180.0),
    RadarSite("Isen/München", 48.1747, 12.1017, 180.0),
    RadarSite("Eisberg", 49.5407, 12.4028, 180.0),
)

FR_SITES = (
    RadarSite("Trappes", 48.7746, 2.0083, 256.0),
    RadarSite("Abbeville", 50.1360, 1.8347, 256.0),
    RadarSite("Bordeaux", 44.8315, -0.6919, 256.0),
    RadarSite("Bourges", 47.0586, 2.3595, 256.0),
    RadarSite("Grèzes", 45.1044, 1.3697, 256.0),
    RadarSite("Falaise", 48.9272, -0.1496, 256.0),
    RadarSite("Nancy", 48.7158, 6.5816, 256.0),
    RadarSite("Nîmes", 43.8061, 4.5027, 256.0),
    RadarSite("Toulouse", 43.5743, 1.3763, 256.0),
    RadarSite("Arcis-sur-Aube", 48.4621, 4.3093, 256.0),
    RadarSite("Sembadel", 45.2892, 3.7095, 256.0),
    RadarSite("Treillères", 47.3374, -1.6563, 256.0),
    RadarSite("Bollène", 44.3230, 4.7621, 256.0),
    RadarSite("Opoul", 42.9184, 2.8650, 256.0),
    RadarSite("Saint Nizier", 46.0678, 4.4453, 256.0),
    RadarSite("Collobrières", 43.2166, 6.3729, 256.0),
    RadarSite("Plabennec", 48.4609, -4.4298, 256.0),
    RadarSite("Aléria", 42.1298, 9.4964, 256.0),
    RadarSite("Montclar", 43.9905, 2.6096, 256.0),
    RadarSite("Avesnes", 50.1283, 3.8118, 256.0),
    RadarSite("Cherves", 46.6986, 0.0656, 256.0),
    RadarSite("Blaisy-Haut", 47.3552, 4.7759, 256.0),
    RadarSite("Momuy", 43.6245, -0.6094, 256.0),
    RadarSite("Montancy", 47.3686, 7.0190, 256.0),
    RadarSite("Mont Maurel", 44.0128, 6.5292, 256.0),
    RadarSite("Ajaccio", 41.9531, 8.7005, 256.0),
)

CH_SITES = (
    RadarSite("Albis", 47.2843, 8.5120, 246.0),
    RadarSite("La Dôle", 46.4251, 6.0994, 246.0),
    RadarSite("Monte Lema", 46.0408, 8.8332, 246.0),
    RadarSite("Pointe de la Plaine Morte", 46.3706, 7.4866, 246.0),
    RadarSite("Weissfluhgipfel", 46.8350, 9.7945, 246.0),
)


AREAS = (
    Area(
        "nl", "Netherlands/Benelux", 700, 765,
        1074.9917354982683, 1072.4027123136912, 0.0, -3911890.1729054004,
        "+proj=stere +lat_0=90 +lat_ts=90 +lon_0=0 +datum=WGS84 +units=m +no_defs",
        "+proj=laea +lat_0=52 +lon_0=5.5 +datum=WGS84 +units=m +no_defs",
        NL_SITES,
        (
            ControlPoint("reported Netherlands location", 51.4841, 5.8596, True),
            ControlPoint("Amsterdam", 52.3676, 4.9041, True),
            ControlPoint("north-west product corner", 55.5, 0.2, False),
            ControlPoint("south-east product corner", 49.0, 10.5, False),
        ),
    ),
    Area(
        "de", "Germany", 460, 460, 2000.0, 2000.0, 0.0, 0.0,
        "+proj=stere +lat_0=90 +lat_ts=60 +lon_0=10 +a=6370000 +b=6370000 "
        "+y_0=3749618.296521171 +x_0=532461.7056238621",
        "+proj=laea +lat_0=51 +lon_0=10 +datum=WGS84 +units=m +no_defs",
        DE_SITES,
        (
            ControlPoint("Berlin", 52.5200, 13.4050, True),
            ControlPoint("Frankfurt", 50.1109, 8.6821, True),
            ControlPoint("far south-west product corner", 47.1, 2.1, False),
            ControlPoint("far north-west product corner", 54.9, 2.2, False),
        ),
    ),
    Area(
        "fr", "France", 790, 724,
        2149.792377130344, 2143.19267210415, -1079854.7058729, -4332668.5727059552,
        "+proj=stere +lat_0=90 +lat_ts=90 +lon_0=5 +datum=WGS84 +units=m +no_defs",
        "+proj=laea +lat_0=46.5 +lon_0=2 +datum=WGS84 +units=m +no_defs",
        FR_SITES,
        (
            ControlPoint("Paris", 48.8566, 2.3522, True),
            ControlPoint("Lyon", 45.7640, 4.8357, True),
            ControlPoint("Corsica", 42.0396, 9.0129, True),
            ControlPoint("far Atlantic product corner", 52.5, -8.5, False),
        ),
    ),
    Area(
        "ch", "Switzerland", 710, 640,
        1001.6589019423421, 1002.217710090751, 255052.3657792788, 480441.57420079992,
        "+proj=somerc +lat_0=46.95240555555556 +lon_0=7.439583333333333 +k_0=1 "
        "+x_0=600000 +y_0=200000 +ellps=bessel "
        "+towgs84=674.374,15.056,405.346,0,0,0,0 +units=m +no_defs",
        "+proj=laea +lat_0=46.8 +lon_0=8 +datum=WGS84 +units=m +no_defs",
        CH_SITES,
        (
            ControlPoint("Bern", 46.9480, 7.4474, True),
            ControlPoint("Zurich", 47.3769, 8.5417, True),
            ControlPoint("far north-west product corner", 49.1, 3.0, False),
            ControlPoint("far south-east product corner", 43.8, 12.2, False),
        ),
    ),
)


def geodesic_circle(site: RadarSite) -> Polygon:
    coordinates = []
    steps = round(360.0 / CIRCLE_STEP_DEGREES)
    for index in range(steps):
        longitude, latitude, _ = GEOD.fwd(
            site.longitude,
            site.latitude,
            index * 360.0 / steps,
            site.range_km * 1000.0,
        )
        coordinates.append((longitude, latitude))
    coordinates.append(coordinates[0])
    return Polygon(coordinates)


def native_raster_footprint(area: Area) -> Polygon:
    native_to_wgs84 = Transformer.from_crs(
        CRS.from_proj4(area.native_projection), WGS84, always_xy=True
    )
    left = area.pixel_offset_x
    right = left + area.raster_width * area.pixel_width_metres
    top = area.pixel_offset_y
    bottom = top - area.raster_height * area.pixel_height_metres
    native = []
    for index in range(FOOTPRINT_EDGE_SAMPLES):
        fraction = index / (FOOTPRINT_EDGE_SAMPLES - 1)
        native.append((left, bottom + (top - bottom) * fraction))
    for index in range(1, FOOTPRINT_EDGE_SAMPLES):
        fraction = index / (FOOTPRINT_EDGE_SAMPLES - 1)
        native.append((left + (right - left) * fraction, top))
    for index in range(1, FOOTPRINT_EDGE_SAMPLES):
        fraction = index / (FOOTPRINT_EDGE_SAMPLES - 1)
        native.append((right, top - (top - bottom) * fraction))
    for index in range(1, FOOTPRINT_EDGE_SAMPLES):
        fraction = index / (FOOTPRINT_EDGE_SAMPLES - 1)
        native.append((right - (right - left) * fraction, bottom))
    coordinates = [native_to_wgs84.transform(x, y) for x, y in native]
    coordinates.append(coordinates[0])
    footprint = Polygon(coordinates)
    if footprint.is_empty or not footprint.is_valid:
        raise RuntimeError(
            f"Invalid {area.id} raster footprint: {explain_validity(footprint)}"
        )
    return footprint


def geometry(area: Area):
    work_crs = CRS.from_proj4(area.work_projection)
    to_work = Transformer.from_crs(WGS84, work_crs, always_xy=True).transform
    to_wgs84 = Transformer.from_crs(work_crs, WGS84, always_xy=True).transform
    circles_work = [transform(to_work, geodesic_circle(site)) for site in area.sites]
    footprint_work = transform(to_work, native_raster_footprint(area))
    exact = unary_union(circles_work).intersection(footprint_work)
    if exact.is_empty or not exact.is_valid:
        raise RuntimeError(f"Invalid {area.id} exact geometry: {explain_validity(exact)}")
    simplified = exact.simplify(
        SIMPLIFY_TOLERANCE_METRES, preserve_topology=True
    ).intersection(footprint_work)
    error = exact.hausdorff_distance(simplified)
    if error > MAX_ALLOWED_ERROR_METRES or simplified.is_empty or not simplified.is_valid:
        raise RuntimeError(
            f"Unsafe {area.id} simplified geometry: error={error}; "
            f"{explain_validity(simplified)}"
        )
    simplified_wgs84 = transform(to_wgs84, simplified)
    if simplified_wgs84.is_empty or not simplified_wgs84.is_valid:
        raise RuntimeError(
            f"Invalid {area.id} WGS84 geometry: {explain_validity(simplified_wgs84)}"
        )
    return exact, simplified, simplified_wgs84, error, footprint_work


def polygons(value):
    if value.geom_type == "Polygon":
        return [orient(value, sign=1.0)]
    if value.geom_type == "MultiPolygon":
        return [orient(polygon, sign=1.0) for polygon in value.geoms]
    raise RuntimeError(f"Unexpected geometry type {value.geom_type}")


def contains(value, latitude: float, longitude: float) -> bool:
    return value.covers(Point(longitude, latitude))


def kotlin_ring(coordinates, indent: str) -> str:
    return "\n".join(
        f"{indent}GeoPoint({latitude:.7f}, {longitude:.7f}),"
        for longitude, latitude in coordinates
    )


def render_polygon(polygon) -> str:
    exterior = kotlin_ring(polygon.exterior.coords, " " * 24)
    holes = []
    for interior in polygon.interiors:
        ring = kotlin_ring(interior.coords, " " * 28)
        holes.append(f"""listOf(
{ring}
                        )""")
    holes_block = ",\n                        ".join(holes)
    return """MeteoNominalCoveragePolygon(
                    exterior = listOf(
%s
                    ),
                    holes = listOf(%s),
                )""" % (
        exterior,
        ("\n                        " + holes_block + "\n                    " if holes else ""),
    )


def render_area(area: Area, value, error_metres: float) -> str:
    polygons_block = ",\n                ".join(
        render_polygon(polygon) for polygon in polygons(value)
    )
    return f'''MeteoNominalCoverageGeometry(
            areaId = "{area.id}",
            geometryVersion = GEOMETRY_VERSION,
            siteCount = {len(area.sites)},
            maxSimplificationErrorMetres = {error_metres:.3f},
            polygons = listOf(
                {polygons_block}
            ),
        )'''


def render_kotlin(results) -> str:
    values = ",\n        ".join(
        f'"{area.id}" to {render_area(area, value, error)}'
        for area, value, error in results
    )
    return f'''package com.rainalarm.app.domain

/** Metadata and polygons for one offline-generated regional nominal coverage envelope. */
data class MeteoNominalCoverageGeometry(
    val areaId: String,
    val geometryVersion: String,
    val siteCount: Int,
    val maxSimplificationErrorMetres: Double,
    val polygons: List<MeteoNominalCoveragePolygon>,
)

/**
 * Evidence-backed nominal reach for DTN/MeteoGroup's continental regional feeds.
 *
 * Generated by tools/generate_meteo_continental_nominal_coverage.py from national-service
 * network descriptions and ranges, with current site coordinates/status cross-checked against
 * EUMETNET OPERA on 2026-09-25. Every geodesic site-range union is clipped to that product's
 * exact native source-raster footprint and topology-preserving simplified with measured error.
 *
 * DTN publishes neither input membership nor validity masks for these legacy products. These
 * geometries are nominal approximations, not live availability or claims that DTN's composites
 * use precisely these inputs. See docs/DATA_SOURCES.md for provenance and limitations.
 */
object MeteoContinentalNominalCoverage {{
    const val GEOMETRY_VERSION = "{GEOMETRY_VERSION}"

    private val areas: Map<String, MeteoNominalCoverageGeometry> = mapOf(
        {values}
    )

    fun geometryForAreaId(areaId: String): MeteoNominalCoverageGeometry? = areas[areaId]
}}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / "app/src/main/java/com/rainalarm/app/domain/MeteoContinentalNominalCoverage.kt",
    )
    args = parser.parse_args()
    rendered = []
    stats = []
    for area in AREAS:
        exact, simplified_work, simplified_wgs84, error, footprint_work = geometry(area)
        for control in area.controls:
            actual = contains(simplified_wgs84, control.latitude, control.longitude)
            if actual != control.expected_covered:
                raise RuntimeError(
                    f"{area.id} control {control.name}: expected "
                    f"{control.expected_covered}, got {actual}"
                )
        # Clipping must never enlarge the represented footprint, and simplification error remains
        # independently bounded rather than inferred from tolerance alone.
        escaped_area = simplified_work.difference(footprint_work).area
        if escaped_area > 1.0:
            raise RuntimeError(f"{area.id} geometry escaped footprint by {escaped_area} m²")
        rendered.append((area, simplified_wgs84, error))
        parts = polygons(simplified_wgs84)
        stats.append(
            f"{area.id}:sites={len(area.sites)} "
            f"exact_area_km2={exact.area / 1e6:.1f} "
            f"footprint_fraction={exact.area / footprint_work.area:.4f} "
            f"components={len(parts)} "
            f"vertices={sum(len(p.exterior.coords) + sum(len(r.coords) for r in p.interiors) for p in parts)} "
            f"holes={sum(len(p.interiors) for p in parts)} "
            f"hausdorff_error_m={error:.3f}"
        )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render_kotlin(rendered), encoding="utf-8")
    print(f"wrote={args.output}")
    print("\n".join(stats))


if __name__ == "__main__":
    main()
