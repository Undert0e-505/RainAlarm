package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLocationSelectionPolicyTest {
    @Test
    fun permissionGrantSelectsVirtualCurrentBeforeAnyFixAndRetryRestartsAcquisition() {
        val initial = CurrentLocationSelectionPolicy.request(DEFAULT_PLACE.id, 0, true)
        assertTrue(initial.allowed)
        assertEquals(CURRENT_LOCATION_ID, initial.selectedId)
        assertEquals(1L, initial.requestGeneration)
        val retryWhileLocating = CurrentLocationSelectionPolicy.request(
            initial.selectedId, initial.requestGeneration, true,
        )
        assertEquals(CURRENT_LOCATION_ID, retryWhileLocating.selectedId)
        assertEquals(2L, retryWhileLocating.requestGeneration)
    }

    @Test
    fun permissionDenialPreservesSavedSelectionAndRequestGeneration() {
        val denied = CurrentLocationSelectionPolicy.request(DEFAULT_PLACE.id, 7, false)
        assertFalse(denied.allowed)
        assertEquals(DEFAULT_PLACE.id, denied.selectedId)
        assertEquals(7L, denied.requestGeneration)
    }
}
