package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationNameResolverTest {
    @Test
    fun localityIsPreferredButFallbackRemainsTruthful() {
        val fallback = LocationNameResolver.fallback(53.4808, -2.2426)
        assertTrue(fallback.contains("53.481"))
        assertEquals("Manchester", LocationNameResolver.preferred(" Manchester ", "Greater Manchester", "England", fallback))
        assertEquals("Greater Manchester", LocationNameResolver.preferred(null, "Greater Manchester", "England", fallback))
        assertEquals(fallback, LocationNameResolver.preferred(null, " ", null, fallback))
    }
}
