package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LocationNameResolverTest {
    @Test
    fun localityIsPreferredButFallbackRemainsTruthful() {
        val fallback = LocationNameResolver.fallback(53.4808, -2.2426)
        assertEquals("53.5, -2.2", fallback)
        assertEquals("Manchester", LocationNameResolver.preferred(" Manchester ", "Greater Manchester", "England", fallback))
        assertEquals("Greater Manchester", LocationNameResolver.preferred(null, "Greater Manchester", "England", fallback))
        assertEquals("England", LocationNameResolver.preferred(null, null, "England", fallback))
        assertEquals(fallback, LocationNameResolver.preferred(null, " ", null, fallback))
    }

    @Test fun coordinatesHaveOneDecimalNoPrefixAndRootDecimalSeparator() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("56.3, 43.1", LocationNameResolver.fallback(56.26, 43.14))
            assertEquals("51.5, -0.1", LocationNameResolver.fallback(51.49, -0.12))
            assertEquals("-33.9, -151.2", LocationNameResolver.fallback(-33.86, -151.21))
        } finally {
            Locale.setDefault(original)
        }
    }
}
