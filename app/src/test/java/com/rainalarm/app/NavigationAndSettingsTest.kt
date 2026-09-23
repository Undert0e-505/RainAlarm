package com.rainalarm.app

import com.rainalarm.app.data.RadarProviderKind
import com.rainalarm.app.data.RadarProviderPreference
import com.rainalarm.app.data.RadarPlaybackSpeed
import com.rainalarm.app.data.RadarPlaybackSpeedPreference
import com.rainalarm.app.data.AppearanceMode
import com.rainalarm.app.data.RadarMapStyle
import com.rainalarm.app.data.NowCardAppearance
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
    fun `rapid provider correction keeps the last deliberate choice`() {
        val regional = RadarProviderKind.METEOGROUP_REGIONAL
        val open = RadarProviderKind.OPEN_RAINVIEWER

        assertTrue(RadarProviderSwitchPolicy.accepts(open, regional, null))
        assertEquals(open, RadarProviderSwitchPolicy.effective(regional, open))
        // A tap back to the currently persisted provider must not be discarded while Open is
        // pending; it is a new last-choice intent that cancels the earlier write/load.
        assertTrue(RadarProviderSwitchPolicy.accepts(regional, regional, open))
        assertEquals(regional, RadarProviderSwitchPolicy.effective(regional, regional))
        assertTrue(!RadarProviderSwitchPolicy.shouldPersist(regional, regional))
        assertTrue(RadarProviderSwitchPolicy.shouldPersist(open, regional))
    }

    @Test
    fun `provider switch quiet period is short and explicit`() {
        assertEquals(400L, RadarProviderSwitchPolicy.settleMillis)
        assertTrue(RadarProviderSwitchPolicy.settleMillis in 250L..750L)
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
    fun `app and map appearance defaults preserve dark and map styles resolve independently`() {
        assertEquals(AppearanceMode.DARK, AppearanceMode.decode(null))
        assertEquals(AppearanceMode.DARK, AppearanceMode.decode("unknown"))
        AppearanceMode.entries.forEach { assertEquals(it, AppearanceMode.decode(it.name)) }
        assertEquals(AppearanceMode.DARK, AppearanceMode.decodeApp(AppearanceMode.SLATE.name))
        assertEquals(AppearanceMode.DARK, AppearanceMode.decodeApp(null))
        assertTrue(AppearanceMode.FOLLOW_SYSTEM.isDark(true))
        assertTrue(!AppearanceMode.FOLLOW_SYSTEM.isDark(false))
        assertTrue(!AppearanceMode.LIGHT.isDark(true))
        assertTrue(AppearanceMode.SLATE.isDark(false))
        assertEquals(RadarMapStyle.DARK, AppearanceMode.DARK.resolveMapStyle(false))
        assertEquals(RadarMapStyle.LIGHT, AppearanceMode.LIGHT.resolveMapStyle(true))
        assertEquals(RadarMapStyle.DARK, AppearanceMode.FOLLOW_SYSTEM.resolveMapStyle(true))
        assertEquals(RadarMapStyle.LIGHT, AppearanceMode.FOLLOW_SYSTEM.resolveMapStyle(false))
        assertEquals(RadarMapStyle.SLATE, AppearanceMode.SLATE.resolveMapStyle(false))
        assertTrue(RadarMapStyle.DARK.darkControls)
        assertTrue(RadarMapStyle.SLATE.darkControls)
        assertTrue(!RadarMapStyle.LIGHT.darkControls)
        assertEquals("https://tiles.openfreemap.org/styles/dark",
            RadarMapAppearance.styleUrl(RadarMapStyle.DARK))
        assertEquals("https://tiles.openfreemap.org/styles/liberty",
            RadarMapAppearance.styleUrl(RadarMapStyle.LIGHT))
        assertEquals("https://tiles.openfreemap.org/styles/fiord",
            RadarMapAppearance.styleUrl(RadarMapStyle.SLATE))
    }

    @Test fun `compass and graph card preferences independently follow app by default`() {
        assertEquals(NowCardAppearance.FOLLOW_APP, NowCardAppearance.decode(null))
        assertEquals(NowCardAppearance.FOLLOW_APP, NowCardAppearance.decode("unknown"))
        NowCardAppearance.entries.forEach { mode ->
            assertEquals(mode, NowCardAppearance.decode(mode.name))
        }
        assertTrue(NowCardAppearance.FOLLOW_APP.isDark(true))
        assertTrue(!NowCardAppearance.FOLLOW_APP.isDark(false))
        assertTrue(!NowCardAppearance.LIGHT.isDark(true))
        assertTrue(NowCardAppearance.DARK.isDark(false))
        assertTrue(NowCardAppearance.SLATE.isDark(false))
        assertEquals(NowCardAppearance.SLATE, NowCardAppearance.decode("SLATE"))
    }
}
