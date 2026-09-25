package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverageMaskDarknessPreferenceTest {
    @Test fun `missing and malformed values use the one-shade-darker default`() {
        assertEquals(0.50f, CoverageMaskDarknessPreference.decode(null), 0f)
        assertEquals(0.50f, CoverageMaskDarknessPreference.decode(Float.NaN), 0f)
        assertEquals(0.50f, CoverageMaskDarknessPreference.decode(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0.50f, CoverageMaskDarknessPreference.decode(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test fun `stored darkness keeps slider precision and clamps to no-mask through dark`() {
        assertEquals(0f, CoverageMaskDarknessPreference.decode(-0.1f), 0f)
        assertEquals(1f, CoverageMaskDarknessPreference.decode(1.1f), 0f)
        assertEquals(0.37f, CoverageMaskDarknessPreference.decode(0.37f), 0f)
    }
}
