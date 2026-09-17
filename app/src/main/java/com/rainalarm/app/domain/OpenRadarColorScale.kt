package com.rainalarm.app.domain

/** Approximate Universal Blue colour-key inversion for the *point* forecast only.
 * RainViewer's PNG alpha is coverage, not precipitation strength. These sparse
 * colour anchors follow its published scheme-2 key; they are not a copied LUT.
 * The output is a display severity, not a measured rainfall rate.
 */
object OpenRadarColorScale {
    private data class Anchor(val dbz: Float, val rgb: Int)

    private val anchors = listOf(
        Anchor(-10f, 0x636159), Anchor(-5f, 0x726E61),
        Anchor(0f, 0x827B69), Anchor(5f, 0x928871),
        Anchor(10f, 0xCEC087), Anchor(12f, 0xD6C88F),
        Anchor(15f, 0x88DDEE), Anchor(20f, 0x00A3E0),
        Anchor(25f, 0x0077AA), Anchor(30f, 0x005588),
        Anchor(35f, 0xFFEE00), Anchor(40f, 0xFFAA00),
        Anchor(45f, 0xFF4400), Anchor(50f, 0xC10000),
        Anchor(55f, 0xFFAAFF), Anchor(60f, 0xFF77FF),
        Anchor(65f, 0xFFFFFF),
    )

    /** Opaque ~13–15 dBZ clears this boundary; weaker/partly covered returns remain trace. */
    const val WET_SEVERITY_THRESHOLD = 0.25f

    fun fromArgb(argb: Int): Float {
        val coverage = ((argb ushr 24) and 0xff) / 255f
        if (coverage == 0f) return 0f
        val red = ((argb ushr 16) and 0xff).toFloat()
        val green = ((argb ushr 8) and 0xff).toFloat()
        val blue = (argb and 0xff).toFloat()
        var closestDbz = anchors.first().dbz
        var bestDistance = Float.POSITIVE_INFINITY
        for (index in 0 until anchors.lastIndex) {
            val a = anchors[index]
            val b = anchors[index + 1]
            val ar = ((a.rgb ushr 16) and 0xff).toFloat()
            val ag = ((a.rgb ushr 8) and 0xff).toFloat()
            val ab = (a.rgb and 0xff).toFloat()
            val dr = ((b.rgb ushr 16) and 0xff) - ar
            val dg = ((b.rgb ushr 8) and 0xff) - ag
            val db = (b.rgb and 0xff) - ab
            val lengthSquared = dr * dr + dg * dg + db * db
            val fraction = if (lengthSquared == 0f) 0f else
                ((red - ar) * dr + (green - ag) * dg + (blue - ab) * db) /
                    lengthSquared
            val t = fraction.coerceIn(0f, 1f)
            val differenceR = red - (ar + dr * t)
            val differenceG = green - (ag + dg * t)
            val differenceB = blue - (ab + db * t)
            val distance = differenceR * differenceR + differenceG * differenceG + differenceB * differenceB
            if (distance < bestDistance) {
                bestDistance = distance
                closestDbz = a.dbz + (b.dbz - a.dbz) * t
            }
        }
        return severityForDbz(closestDbz) * coverage
    }

    fun severityForDbz(dbz: Float): Float = when {
        dbz <= 0f -> 0f
        dbz < 15f -> dbz / 15f * 0.3f
        dbz < 40f -> 0.3f + (dbz - 15f) / 25f * (2f / 3f - 0.3f)
        dbz < 65f -> 2f / 3f + (dbz - 40f) / 25f / 3f
        else -> 1f
    }
}
