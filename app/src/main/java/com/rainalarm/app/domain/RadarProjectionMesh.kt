package com.rainalarm.app.domain

import com.rainalarm.app.data.RegionalRadarArea
import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate

data class RadarGeoVertex(
    val latitude: Double,
    val longitude: Double,
    val u: Float,
    val v: Float,
)

data class RadarProjectionMesh(
    val columns: Int,
    val rows: Int,
    val vertices: List<RadarGeoVertex>,
    val indices: ShortArray,
    val projectionAccurate: Boolean,
) {
    /** Exact outer raster perimeter in clockwise geographic order, closed at its first vertex. */
    fun perimeterClockwise(): List<RadarGeoVertex> {
        require(columns >= 2 && rows >= 2 && vertices.size == columns * rows)
        fun vertex(row: Int, column: Int) = vertices[row * columns + column]
        return buildList(2 * columns + 2 * rows - 3) {
            // Bottom-left -> top-left -> top-right -> bottom-right -> bottom-left.
            for (row in rows - 1 downTo 0) add(vertex(row, 0))
            for (column in 1 until columns) add(vertex(0, column))
            for (row in 1 until rows) add(vertex(row, columns - 1))
            for (column in columns - 2 downTo 0) add(vertex(rows - 1, column))
        }
    }
}

data class RadarPixelCoordinate(val x: Double, val y: Double)

object RegionalProjection {
    fun pixelFor(area: RegionalRadarArea, latitude: Double, longitude: Double): RadarPixelCoordinate? =
        runCatching {
            val factory = CRSFactory()
            val geographic = factory.createFromParameters(
                "wgs84-longlat",
                "+proj=longlat +datum=WGS84 +no_defs",
            )
            val radar = factory.createFromParameters("radar-${area.id}", area.projection)
            val result = ProjCoordinate()
            CoordinateTransformFactory().createTransform(geographic, radar)
                .transform(ProjCoordinate(longitude, latitude), result)
            RadarPixelCoordinate(
                (result.x - area.pixelOffsetX) / area.pixelWidthMetres,
                (area.pixelOffsetY - result.y) / area.pixelHeightMetres,
            ).takeIf { it.x in 0.0..area.rasterWidth.toDouble() && it.y in 0.0..area.rasterHeight.toDouble() }
        }.getOrNull()
}

object RegionalProjectionMesh {
    private const val GRID_CELLS = 24

    fun build(area: RegionalRadarArea): RadarProjectionMesh = runCatching {
        projected(area)
    }.getOrElse {
        boundsFallback(area)
    }

    private fun projected(area: RegionalRadarArea): RadarProjectionMesh {
        val factory = CRSFactory()
        val source = factory.createFromParameters("radar-${area.id}", area.projection)
        val destination = factory.createFromParameters(
            "wgs84-longlat",
            "+proj=longlat +datum=WGS84 +no_defs",
        )
        val transform = CoordinateTransformFactory().createTransform(source, destination)
        val columns = GRID_CELLS + 1
        val rows = GRID_CELLS + 1
        val vertices = ArrayList<RadarGeoVertex>(columns * rows)
        for (row in 0 until rows) {
            val sourceY = area.pixelOffsetY - area.rasterHeight * area.pixelHeightMetres * row / GRID_CELLS
            // Bitmap/JPEG row zero is the north/top edge. GL receives that row first,
            // so its texture coordinate is v=0 even though screen NDC uses +Y upward.
            val v = row.toFloat() / GRID_CELLS
            for (column in 0 until columns) {
                val sourceX = area.pixelOffsetX + area.rasterWidth * area.pixelWidthMetres * column / GRID_CELLS
                val result = ProjCoordinate()
                transform.transform(ProjCoordinate(sourceX, sourceY), result)
                require(result.x.isFinite() && result.y.isFinite() && result.y in -90.0..90.0)
                vertices += RadarGeoVertex(result.y, result.x, column.toFloat() / GRID_CELLS, v)
            }
        }
        return RadarProjectionMesh(columns, rows, vertices, indices(columns, rows), true)
    }

    private fun boundsFallback(area: RegionalRadarArea): RadarProjectionMesh {
        val vertices = listOf(
            RadarGeoVertex(area.north, area.west, 0f, 0f),
            RadarGeoVertex(area.north, area.east, 1f, 0f),
            RadarGeoVertex(area.south, area.west, 0f, 1f),
            RadarGeoVertex(area.south, area.east, 1f, 1f),
        )
        return RadarProjectionMesh(2, 2, vertices, indices(2, 2), false)
    }

    private fun indices(columns: Int, rows: Int): ShortArray {
        require(columns * rows <= Short.MAX_VALUE)
        val result = ShortArray((columns - 1) * (rows - 1) * 6)
        var offset = 0
        for (row in 0 until rows - 1) {
            for (column in 0 until columns - 1) {
                val topLeft = row * columns + column
                val topRight = topLeft + 1
                val bottomLeft = topLeft + columns
                val bottomRight = bottomLeft + 1
                result[offset++] = topLeft.toShort()
                result[offset++] = bottomLeft.toShort()
                result[offset++] = topRight.toShort()
                result[offset++] = topRight.toShort()
                result[offset++] = bottomLeft.toShort()
                result[offset++] = bottomRight.toShort()
            }
        }
        return result
    }
}

object BoundsProjectionMesh {
    fun build(bounds: GeoQuad): RadarProjectionMesh = RadarProjectionMesh(
        columns = 2,
        rows = 2,
        vertices = listOf(
            RadarGeoVertex(bounds.topLeft.latitude, bounds.topLeft.longitude, 0f, 0f),
            RadarGeoVertex(bounds.topRight.latitude, bounds.topRight.longitude, 1f, 0f),
            RadarGeoVertex(bounds.bottomLeft.latitude, bounds.bottomLeft.longitude, 0f, 1f),
            RadarGeoVertex(bounds.bottomRight.latitude, bounds.bottomRight.longitude, 1f, 1f),
        ),
        indices = shortArrayOf(0, 2, 1, 1, 2, 3),
        projectionAccurate = true,
    )
}
