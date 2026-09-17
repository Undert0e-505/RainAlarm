package com.rainalarm.app.domain

import com.rainalarm.app.data.CURRENT_LOCATION_ID
import com.rainalarm.app.data.SavedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarCameraPolicyTest {
    private val london = SavedPlace("London", 51.5, -0.1)
    private val manchester = SavedPlace("Manchester", 53.48, -2.24)

    @Test
    fun `first map uses twenty mile entry zoom then tab return restores exact camera`() {
        val first = RadarCameraPolicy.target(london, 400, null)
        assertEquals(london.latitude, first.latitude, 0.0)
        assertEquals(london.longitude, first.longitude, 0.0)
        assertEquals(RadarEntryZoom.forHorizontalMiles(51.5, 400), first.zoom, 0.0)
        val snapshot = RadarCameraPolicy.capture(london, 51.55, 0.1, 13.25)
        val returned = RadarCameraPolicy.target(london, 400, snapshot)
        assertEquals(51.55, returned.latitude, 0.0)
        assertEquals(0.1, returned.longitude, 0.000001)
        assertEquals(13.25, returned.zoom, 0.0)
    }

    @Test
    fun `switching saved or virtual current place transfers pan offset and zoom`() {
        val snapshot = RadarCameraPolicy.capture(london, 51.55, 0.1, 12.5)
        val moved = RadarCameraPolicy.target(manchester, 400, snapshot)
        assertEquals(-2.04, moved.longitude, 0.00001)
        assertTrue(moved.latitude > manchester.latitude)
        assertEquals(12.5, moved.zoom, 0.0)
        val current = SavedPlace("Current", 54.5, -1.25, isCurrentLocation = true, id = CURRENT_LOCATION_ID)
        val live = RadarCameraPolicy.target(current, 400, snapshot)
        assertEquals(-1.05, live.longitude, 0.00001)
        assertTrue(live.latitude > current.latitude)
        assertEquals(12.5, live.zoom, 0.0)
    }

    @Test
    fun `explicit recenter keeps zoom and delayed tick is consumed exactly once`() {
        val memory = RadarCameraMemory()
        memory.capture(london, 51.55, 0.1, 14.0)
        val switched = memory.target(manchester, 400, 0)
        assertEquals(-2.04, switched.longitude, 0.00001)
        memory.capture(manchester, switched.latitude, switched.longitude, switched.zoom)
        assertTrue(memory.hasPendingRecenter(1))
        val recentered = memory.target(manchester, 400, 1)
        assertEquals(manchester.latitude, recentered.latitude, 0.0)
        assertEquals(manchester.longitude, recentered.longitude, 0.0)
        assertEquals(14.0, recentered.zoom, 0.0)
        assertFalse(memory.hasPendingRecenter(1))
        memory.capture(manchester, recentered.latitude, recentered.longitude, recentered.zoom)
        val returned = memory.target(manchester, 400, 1)
        assertEquals(recentered.latitude, returned.latitude, 0.000001)
        assertEquals(recentered.longitude, returned.longitude, 0.000001)
        assertEquals(recentered.zoom, returned.zoom, 0.0)
    }

    @Test
    fun `selected header recenter repeats after pan without changing place or zoom`() {
        val memory = RadarCameraMemory()
        assertTrue(RadarSelectedCenterPolicy.canCenter(london.id, london))
        assertFalse(RadarSelectedCenterPolicy.canCenter(london.id, null))
        assertFalse(RadarSelectedCenterPolicy.canCenter(manchester.id, london))
        val current = SavedPlace("Current", 54.5, -1.25, isCurrentLocation = true, id = CURRENT_LOCATION_ID)
        assertTrue(RadarSelectedCenterPolicy.canCenter(CURRENT_LOCATION_ID, current))

        memory.capture(london, 51.62, 0.22, 15.0)
        for (tick in 1..2) {
            assertTrue(memory.hasPendingRecenter(tick))
            val target = memory.target(london, 400, tick)
            assertEquals(london.latitude, target.latitude, 0.0)
            assertEquals(london.longitude, target.longitude, 0.0)
            assertEquals(15.0, target.zoom, 0.0)
            assertFalse(memory.hasPendingRecenter(tick))
            memory.capture(london, 51.62, 0.22, target.zoom)
        }
    }

    @Test
    fun `antimeridian offset wraps and polar camera clamps safely`() {
        val east = SavedPlace("East", 0.0, 179.9)
        val west = SavedPlace("West", 0.0, -179.9)
        val wrapped = RadarCameraPolicy.target(west, 400,
            RadarCameraPolicy.capture(east, 0.0, -179.8, 30.0))
        assertEquals(-179.6, wrapped.longitude, 0.00001)
        assertEquals(21.0, wrapped.zoom, 0.0)
        val arctic = SavedPlace("Arctic", 85.0, 0.0)
        val pole = RadarCameraPolicy.target(arctic, 400,
            RadarCameraPolicy.capture(london, 85.0, 0.0, 10.0))
        assertTrue(pole.latitude in -85.05112878..85.05112878)
        assertTrue(pole.longitude in -180.0..180.0)
    }
}
