"""Generate the versioned Meteo UK/Ireland nominal radar-coverage geometry.

This is an offline maintainer tool; it is never run by the Android application.
It requires Python 3 with ``pyproj`` and ``shapely`` installed.

Sources and licensing:
* UK/Jersey coordinates and the 255 km useful qualitative range are from the
  Met Office Weather radar factsheet, Crown copyright under OGL v3.0:
  https://www.metoffice.gov.uk/api/assets/file/factsheet_15-weather-radar-2020_2023pdf?prefix=assets
  https://www.metoffice.gov.uk/policies/tandc
* Dublin and Shannon coordinates are in Met Eireann's CC BY 4.0 radar open data.
  Current corrected-reflectivity ODIM HDF sweeps expose 250 x 1000 m bins for
  Dublin and 497 x 500 m bins for Shannon, hence 250.0 km and 248.5 km:
  https://opendata2.met.ie/radar/

The output is a nominal structural reach, not per-frame live availability. It
does not model terrain, beam blockage, temporary outages, or changing inputs.
"""

from __future__ import annotations

import argparse
import math
from dataclasses import dataclass
from pathlib import Path

from pyproj import CRS, Geod, Transformer
from shapely.geometry import Point, Polygon
from shapely.geometry.polygon import orient
from shapely.ops import transform, unary_union
from shapely.validation import explain_validity


GEOD = Geod(ellps="WGS84")
WGS84 = CRS.from_epsg(4326)
# A local equal-area work plane keeps union/simplification errors expressed in metres.
WORK_CRS = CRS.from_proj4("+proj=laea +lat_0=54 +lon_0=-4 +datum=WGS84 +units=m +no_defs")
TO_WORK = Transformer.from_crs(WGS84, WORK_CRS, always_xy=True).transform
TO_WGS84 = Transformer.from_crs(WORK_CRS, WGS84, always_xy=True).transform

UK_NATIVE_PROJ = CRS.from_proj4(
    "+proj=tmerc +lat_0=49 +lon_0=-2 +k=0.9996012717 +x_0=400000 "
    "+y_0=-100000 +ellps=airy +datum=OSGB36 +units=m +no_defs"
)
UK_NATIVE_TO_WGS84 = Transformer.from_crs(UK_NATIVE_PROJ, WGS84, always_xy=True)
UK_RASTER_WIDTH = 583
UK_RASTER_HEIGHT = 767
UK_PIXEL_WIDTH_METRES = 1999.2506234920268
UK_PIXEL_HEIGHT_METRES = 1999.2506234920268
UK_PIXEL_OFFSET_X = -323099.15625
UK_PIXEL_OFFSET_Y = 1194687.75

CIRCLE_STEP_DEGREES = 1.0
# This deliberately matches RegionalProjectionMesh's 24 cells / 25 perimeter vertices,
# so the generated clip and the renderer use the identical piecewise-geographic boundary.
FOOTPRINT_EDGE_SAMPLES = 25
SIMPLIFY_TOLERANCE_METRES = 500.0
MAX_ALLOWED_ERROR_METRES = 1000.0


def dms(degrees: int, minutes: int, seconds: int, sign: int = 1) -> float:
    return sign * (degrees + minutes / 60.0 + seconds / 3600.0)


@dataclass(frozen=True)
class RadarSite:
    name: str
    latitude: float
    longitude: float
    range_km: float


# Coordinates are transcribed from the Met Office factsheet table. Wardon Hill
# is deliberately omitted because that table marks it R&D/not operational.
SITES = (
    RadarSite("Clee Hill", dms(52, 23, 55), dms(2, 35, 43, -1), 255.0),
    RadarSite("Hameldon Hill", dms(53, 45, 17), dms(2, 17, 19, -1), 255.0),
    RadarSite("Chenies", dms(51, 41, 21), dms(0, 31, 50, -1), 255.0),
    RadarSite("Castor Bay", dms(54, 30, 0), dms(6, 20, 24, -1), 255.0),
    RadarSite("Predannack", dms(50, 0, 12), dms(5, 13, 21, -1), 255.0),
    RadarSite("Ingham", dms(53, 20, 6), dms(0, 33, 33, -1), 255.0),
    RadarSite("Crug-y-Gorllwyn", dms(51, 58, 47), dms(4, 26, 41, -1), 255.0),
    RadarSite("Hill of Dudwick", dms(57, 25, 51), dms(2, 2, 10, -1), 255.0),
    RadarSite("Druim a'Starraig", dms(58, 12, 40), dms(6, 10, 59, -1), 255.0),
    RadarSite("Cobbacombe Cross", dms(50, 57, 48), dms(3, 27, 10, -1), 255.0),
    RadarSite("Thurnham", dms(51, 17, 41), dms(0, 36, 15), 255.0),
    RadarSite("Dean Hill", dms(51, 1, 50), dms(1, 39, 16, -1), 255.0),
    RadarSite("Holehead", dms(56, 1, 6), dms(4, 13, 8, -1), 255.0),
    RadarSite("Munduff Hill", dms(56, 12, 53), dms(3, 18, 38, -1), 255.0),
    RadarSite("High Moorsley", dms(54, 48, 20), dms(1, 28, 32, -1), 255.0),
    RadarSite("Channel Islands", dms(49, 12, 34), dms(2, 11, 56, -1), 255.0),
    RadarSite("Dublin", 53.4299, -6.2443, 250.0),
    RadarSite("Shannon", 52.692787, -8.919994, 248.5),
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


def native_raster_footprint() -> Polygon:
    left = UK_PIXEL_OFFSET_X
    right = left + UK_RASTER_WIDTH * UK_PIXEL_WIDTH_METRES
    top = UK_PIXEL_OFFSET_Y
    bottom = top - UK_RASTER_HEIGHT * UK_PIXEL_HEIGHT_METRES
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
    coordinates = [UK_NATIVE_TO_WGS84.transform(x, y) for x, y in native]
    coordinates.append(coordinates[0])
    return Polygon(coordinates)


def geometry():
    circles_work = [transform(TO_WORK, geodesic_circle(site)) for site in SITES]
    footprint_work = transform(TO_WORK, native_raster_footprint())
    exact = unary_union(circles_work).intersection(footprint_work)
    if exact.is_empty or not exact.is_valid:
        raise RuntimeError(f"Invalid exact geometry: {explain_validity(exact)}")
    # Re-intersect after simplification so a displaced simplified segment can never leak beyond
    # the authoritative source footprint. The footprint uses the renderer's exact mesh perimeter.
    simplified = exact.simplify(SIMPLIFY_TOLERANCE_METRES, preserve_topology=True).intersection(
        footprint_work
    )
    error = exact.hausdorff_distance(simplified)
    if error > MAX_ALLOWED_ERROR_METRES or not simplified.is_valid:
        raise RuntimeError(f"Unsafe simplified geometry: error={error}; {explain_validity(simplified)}")
    return exact, simplified, transform(TO_WGS84, simplified), error


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


def render_kotlin(value, error_metres: float) -> str:
    parts = []
    for polygon in polygons(value):
        exterior = kotlin_ring(polygon.exterior.coords, " " * 20)
        holes = []
        for interior in polygon.interiors:
            ring = kotlin_ring(interior.coords, " " * 24)
            holes.append(f"""listOf(
{ring}
                    )""")
        holes_block = ",\n                    ".join(holes)
        parts.append(
            """MeteoNominalCoveragePolygon(
                exterior = listOf(
%s
                ),
                holes = listOf(%s),
            )""" % (exterior, ("\n                    " + holes_block + "\n                " if holes else ""))
        )
    polygons_block = ",\n            ".join(parts)
    return f'''package com.rainalarm.app.domain

/** One polygon in the versioned, offline-generated nominal radar coverage. */
data class MeteoNominalCoveragePolygon(
    val exterior: List<GeoPoint>,
    val holes: List<List<GeoPoint>> = emptyList(),
)

/**
 * Nominal structural reach of the Meteo UK/Ireland radar network.
 *
 * Generated by tools/generate_meteo_uk_nominal_coverage.py from the 16 Met Office/Jersey
 * sites in Factsheet 15 (255 km), Dublin (250 km) and Shannon (248.5 km). The geodesic
 * union is clipped to the exact native UK source-raster footprint, then topology-preserving
 * simplified with {error_metres:.1f} m measured Hausdorff error. This is not live availability
 * and deliberately says nothing about terrain, beam blockage, outages, or current echoes.
 *
 * Contains public sector information licensed under the Open Government Licence v3.0.
 * Met Eireann radar open data is licensed under CC BY 4.0.
 */
object MeteoNominalCoverage {{
    const val GEOMETRY_VERSION = "2026-09-25"
    const val MAX_SIMPLIFICATION_ERROR_METRES = {error_metres:.3f}

    private val uk: List<MeteoNominalCoveragePolygon> = listOf(
            {polygons_block}
    )

    /** Only UK/Ireland has evidence-backed, redistributable nominal geometry today. */
    fun forAreaId(areaId: String): List<MeteoNominalCoveragePolygon>? =
        if (areaId == "uk") uk else null
}}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).resolve().parents[1]
        / "app/src/main/java/com/rainalarm/app/domain/MeteoNominalCoverage.kt",
    )
    args = parser.parse_args()
    exact, simplified_work, simplified_wgs84, error = geometry()
    checks = {
        "Fair Isle": (59.53, -1.63, True),
        "Shetland Mainland": (60.32, -1.23, False),
        "Great Baddow": (51.729, 0.503, True),
        "Dublin": (53.3498, -6.2603, True),
        "Stornoway": (58.209, -6.386, True),
        "Atlantic": (55.0, -15.0, False),
    }
    for name, (latitude, longitude, expected) in checks.items():
        actual = contains(simplified_wgs84, latitude, longitude)
        if actual != expected:
            raise RuntimeError(f"Control point {name}: expected {expected}, got {actual}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render_kotlin(simplified_wgs84, error), encoding="utf-8")
    print(
        f"wrote={args.output} sites={len(SITES)} exact_area_km2={exact.area / 1e6:.1f} "
        f"simplified_area_km2={simplified_work.area / 1e6:.1f} "
        f"components={len(polygons(simplified_wgs84))} "
        f"vertices={sum(len(p.exterior.coords) + sum(len(r.coords) for r in p.interiors) for p in polygons(simplified_wgs84))} "
        f"holes={sum(len(p.interiors) for p in polygons(simplified_wgs84))} "
        f"hausdorff_error_m={error:.3f}"
    )


if __name__ == "__main__":
    main()
