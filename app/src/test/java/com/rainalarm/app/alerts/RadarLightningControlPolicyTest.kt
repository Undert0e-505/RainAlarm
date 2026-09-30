package com.rainalarm.app.alerts

import com.rainalarm.app.data.SavedPlace
import org.junit.Assert.assertEquals
import org.junit.Test

class RadarLightningControlPolicyTest {
    private val place = SavedPlace("York", 53.96, -1.08, id = "york")
    private val lease = TemporaryLightningLease(
        place.id,
        place.name,
        place.latitude,
        place.longitude,
        expiresAtEpochSeconds = 2_000L,
    )

    @Test fun `temporary state is target bound expires by wall clock and persistent on wins`() {
        assertEquals(
            RadarLightningControlState.TEMPORARY,
            RadarLightningControlPolicy.state(false, lease, place, 1_000L),
        )
        assertEquals(
            RadarLightningControlState.OFF,
            RadarLightningControlPolicy.state(
                false,
                lease,
                SavedPlace("Elsewhere", 54.0, -1.0, id = "elsewhere"),
                1_000L,
            ),
        )
        assertEquals(RadarLightningControlState.OFF,
            RadarLightningControlPolicy.state(false, lease, place, 2_000L))
        assertEquals(RadarLightningControlState.ON,
            RadarLightningControlPolicy.state(true, lease, place, 1_000L))
    }

    @Test fun `tap mapping promotes off or temporary and disables persistent on`() {
        assertEquals(true, RadarLightningControlPolicy.persistentAfterTap(RadarLightningControlState.OFF))
        assertEquals(true, RadarLightningControlPolicy.persistentAfterTap(RadarLightningControlState.TEMPORARY))
        assertEquals(false, RadarLightningControlPolicy.persistentAfterTap(RadarLightningControlState.ON))
    }
}
