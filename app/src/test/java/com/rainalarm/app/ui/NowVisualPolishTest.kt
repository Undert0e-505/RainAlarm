package com.rainalarm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowVisualPolishTest {
    @Test fun `compass status targets a true two times size on normal and stacked layouts`() {
        assertEquals(12f, NowCompassStatusTypography.settledBaseSp(stackedReadouts = false), 0f)
        assertEquals(24f, NowCompassStatusTypography.targetSp(stackedReadouts = false), 0f)
        assertEquals(10f, NowCompassStatusTypography.settledBaseSp(stackedReadouts = true), 0f)
        assertEquals(20f, NowCompassStatusTypography.targetSp(stackedReadouts = true), 0f)
    }

    @Test fun `compass status only downscales when measured text would clip`() {
        assertEquals(24f, NowCompassStatusTypography.fittedSp(false, 200, 240f), 0f)
        assertEquals(18f, NowCompassStatusTypography.fittedSp(false, 320, 240f), 0.001f)
        assertEquals(12f, NowCompassStatusTypography.fittedSp(false, 800, 100f), 0f)
        assertEquals(10f, NowCompassStatusTypography.fittedSp(true, 800, 100f), 0f)
        assertTrue(NowCompassStatusTypography.fittedSp(false, 280, 240f) in 12f..24f)
    }

    @Test fun `compass status retains corner readout space unless the footer is stacked`() {
        assertEquals(151.2f, NowCompassStatusTypography.availableWidthDp(360f, false), 0.01f)
        assertEquals(344f, NowCompassStatusTypography.availableWidthDp(360f, true), 0f)
        assertEquals(96f, NowCompassStatusTypography.availableWidthDp(200f, false), 0f)
    }
}
