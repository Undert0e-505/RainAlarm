package com.rainalarm.app.domain

/**
 * Small decoded neighbourhood around one selected place, retained instead of a regional bitmap.
 * Coordinates use raster edges. The regional GLES texture layout maps u=0/1 to the first/last
 * texel centres, so a projected pixel coordinate x maps to texel index x*(width-1)/width.
 */
class RegionalPointPatch(
    val rasterWidth: Int,
    val rasterHeight: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val pointX: Double,
    val pointY: Double,
    private val values: FloatArray,
) {
    init {
        require(rasterWidth > 0 && rasterHeight > 0 && width > 0 && height > 0)
        require(left >= 0 && top >= 0 && left + width <= rasterWidth && top + height <= rasterHeight)
        require(values.size == width * height)
        require(pointX in 0.0..rasterWidth.toDouble() && pointY in 0.0..rasterHeight.toDouble())
        require(values.all { it in 0f..1f })
    }

    fun sample(offsetX: Float = 0f, offsetY: Float = 0f): Float {
        val x = pointX + offsetX
        val y = pointY + offsetY
        if (!x.isFinite() || !y.isFinite() || x !in 0.0..rasterWidth.toDouble() ||
            y !in 0.0..rasterHeight.toDouble()) return 0f
        val localX = (x * (rasterWidth - 1) / rasterWidth - left)
            .coerceIn(0.0, (width - 1).toDouble())
        val localY = (y * (rasterHeight - 1) / rasterHeight - top)
            .coerceIn(0.0, (height - 1).toDouble())
        return RadarLinearSampling.bilinear(values, width, height, localX, localY)
    }
}
