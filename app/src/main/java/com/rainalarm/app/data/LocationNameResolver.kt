package com.rainalarm.app.data

import java.util.Locale

/** Platform reverse-geocoding is optional; a coordinate label is always truthful. */
object LocationNameResolver {
    fun fallback(latitude: Double, longitude: Double): String =
        String.format(Locale.ROOT, "%.1f, %.1f", latitude, longitude)

    fun preferred(locality: String?, county: String?, region: String?, fallback: String): String =
        sequenceOf(locality, county, region)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull() ?: fallback
}
