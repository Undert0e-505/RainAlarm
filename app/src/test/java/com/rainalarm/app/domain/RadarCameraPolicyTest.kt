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
    fun `switching saved or virtual current identity centers exactly and keeps zoom`() {
        val snapshot = RadarCameraPolicy.capture(london, 51.55, 0.1, 12.5)
        val moved = RadarCameraPolicy.target(manchester, 400, snapshot)
        assertEquals(manchester.longitude, moved.longitude, 0.0)
        assertEquals(manchester.latitude, moved.latitude, 0.0)
        assertEquals(12.5, moved.zoom, 0.0)
        val current = SavedPlace("Current", 54.5, -1.25, isCurrentLocation = true, id = CURRENT_LOCATION_ID)
        val live = RadarCameraPolicy.target(current, 400, snapshot)
        assertEquals(current.longitude, live.longitude, 0.0)
        assertEquals(current.latitude, live.latitude, 0.0)
        assertEquals(12.5, live.zoom, 0.0)
    }

    @Test
    fun `same virtual current identity preserves camera when its fix and name move`() {
        val current = SavedPlace("Current", 51.5, -0.1, isCurrentLocation = true,
            id = CURRENT_LOCATION_ID)
        val snapshot = RadarCameraPolicy.capture(current, 51.7, 0.2, 14.5)
        val movedFix = SavedPlace("Westminster", 51.51, -0.12, isCurrentLocation = true,
            id = CURRENT_LOCATION_ID)
        val target = RadarCameraPolicy.target(movedFix, 400, snapshot)
        assertEquals(51.7, target.latitude, 0.0)
        assertEquals(0.2, target.longitude, 0.0000001)
        assertEquals(14.5, target.zoom, 0.0)
    }

    @Test
    fun `save and select centers once then same place refresh restores exact viewport`() {
        val memory = RadarCameraMemory()
        memory.capture(london, 51.8, 0.4, 16.25)
        val saved = SavedPlace("Long press", 52.1, -1.3)
        val selected = memory.target(saved, 400, 0)
        assertEquals(saved.latitude, selected.latitude, 0.0)
        assertEquals(saved.longitude, selected.longitude, 0.0)
        assertEquals(16.25, selected.zoom, 0.0)

        // Session replacement sees the same stable ID and must not create a second jump.
        memory.capture(saved, 52.12, -1.27, selected.zoom)
        val refreshed = memory.target(saved, 400, 0)
        assertEquals(52.12, refreshed.latitude, 0.0)
        assertEquals(-1.27, refreshed.longitude, 0.0000001)
        assertEquals(16.25, refreshed.zoom, 0.0)
    }

    @Test
    fun `explicit recenter keeps zoom and delayed tick is consumed exactly once`() {
        val memory = RadarCameraMemory()
        memory.capture(london, 51.55, 0.1, 14.0)
        val switched = memory.target(manchester, 400, 0)
        assertEquals(manchester.latitude, switched.latitude, 0.0)
        assertEquals(manchester.longitude, switched.longitude, 0.0)
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
    fun `capture wraps antimeridian clamps polar camera and bounds zoom safely`() {
        val east = SavedPlace("East", 0.0, 179.9)
        val west = SavedPlace("West", 0.0, -179.9)
        val snapshot = RadarCameraPolicy.capture(east, 95.0, 180.2, 30.0)
        assertEquals(85.05112878, snapshot.centerLatitude, 0.0)
        assertEquals(-179.8, snapshot.centerLongitude, 0.00001)
        assertEquals(21.0, snapshot.zoom, 0.0)
        val switched = RadarCameraPolicy.target(west, 400, snapshot)
        assertEquals(west.latitude, switched.latitude, 0.0)
        assertEquals(west.longitude, switched.longitude, 0.0)
        assertEquals(21.0, switched.zoom, 0.0)
    }
}
