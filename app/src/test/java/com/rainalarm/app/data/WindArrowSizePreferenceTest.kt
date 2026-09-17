package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WindArrowSizePreferenceTest {
    @Test fun `missing and invalid stored size preserve existing glyph`() {
        assertEquals(1f, WindArrowSizePreference.decode(null), 0f)
        assertEquals(1f, WindArrowSizePreference.decode(Float.NaN), 0f)
        assertEquals(1f, WindArrowSizePreference.decode(Float.POSITIVE_INFINITY), 0f)
    }

    @Test fun `stored size is bounded but intermediate slider values persist`() {
        assertEquals(0.5f, WindArrowSizePreference.decode(0.1f), 0f)
        assertEquals(2f, WindArrowSizePreference.decode(4f), 0f)
        assertEquals(1.35f, WindArrowSizePreference.decode(1.35f), 0f)
    }
}
