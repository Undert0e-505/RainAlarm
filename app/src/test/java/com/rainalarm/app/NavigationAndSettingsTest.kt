package com.rainalarm.app

import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RadarProviderPreference
import com.rainalarm.app.data.RadarPlaybackSpeed
import com.rainalarm.app.data.RadarPlaybackSpeedPreference
import com.rainalarm.app.data.AppearanceMode
import com.rainalarm.app.data.StartupPermissionPolicy
import com.rainalarm.app.data.StartupPermissionStep
import com.rainalarm.app.ui.RadarMapAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAndSettingsTest {
    @Test fun `first run permission prompts are sequential and consumed attempts do not repeat`() {
        assertEquals(StartupPermissionStep.LOCATION,
            StartupPermissionPolicy.next(true, true, false, false))
        assertEquals(StartupPermissionStep.NOTIFICATION,
            StartupPermissionPolicy.next(false, true, false, false))
        assertEquals(StartupPermissionStep.NOTIFICATION,
            StartupPermissionPolicy.next(true, true, true, false))
        assertEquals(StartupPermissionStep.NONE,
            StartupPermissionPolicy.next(false, false, false, false))
        assertEquals(StartupPermissionStep.NONE,
            StartupPermissionPolicy.next(true, false, true, false))
    }
    @Test
    fun `settings is a reachable unique navigation destination`() {
        assertTrue(Destination.SETTINGS in Destination.entries)
        assertEquals(Destination.entries.size, Destination.entries.map { it.label }.distinct().size)
        assertEquals("Settings", Destination.SETTINGS.label)
    }

    @Test
    fun `provider preference has stable persisted values and legacy default`() {
        assertEquals(RadarProviderKind.METEOGROUP_REGIONAL, RadarProviderPreference.decode(null))
        RadarProviderKind.entries.forEach { provider ->
            assertEquals(provider, RadarProviderPreference.decode(RadarProviderPreference.encode(provider)))
        }
    }

    @Test
    fun `playback speed has stable persisted values and two times default`() {
        assertEquals(RadarPlaybackSpeed.DOUBLE, RadarPlaybackSpeedPreference.decode(null))
        assertEquals(RadarPlaybackSpeed.DOUBLE, RadarPlaybackSpeedPreference.decode("old-value"))
        assertEquals(RadarPlaybackSpeed.NORMAL, RadarPlaybackSpeedPreference.decode("NORMAL"))
        RadarPlaybackSpeed.entries.forEach { speed ->
            assertEquals(speed, RadarPlaybackSpeedPreference.decode(RadarPlaybackSpeedPreference.encode(speed)))
        }
    }

    @Test
    fun `app and map appearance defaults preserve dark and system mode resolves independently`() {
        assertEquals(AppearanceMode.DARK, AppearanceMode.decode(null))
        assertEquals(AppearanceMode.DARK, AppearanceMode.decode("unknown"))
        AppearanceMode.entries.forEach { assertEquals(it, AppearanceMode.decode(it.name)) }
        assertTrue(AppearanceMode.FOLLOW_SYSTEM.isDark(true))
        assertTrue(!AppearanceMode.FOLLOW_SYSTEM.isDark(false))
        assertTrue(!AppearanceMode.LIGHT.isDark(true))
        assertEquals("https://tiles.openfreemap.org/styles/dark", RadarMapAppearance.styleUrl(true))
        assertEquals("https://tiles.openfreemap.org/styles/liberty", RadarMapAppearance.styleUrl(false))
    }
}
