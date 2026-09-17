package com.rainalarm.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowEntryRefreshPolicyTest {
    @Test fun `only a real non Now to Now transition refreshes`() {
        assertFalse(NowEntryRefreshPolicy.entersNow(null, Destination.NOW)) // initial load owns startup
        assertFalse(NowEntryRefreshPolicy.entersNow(Destination.NOW, Destination.NOW))
        assertFalse(NowEntryRefreshPolicy.entersNow(Destination.RADAR, Destination.PLACES))
        for (previous in listOf(Destination.RADAR, Destination.PLACES, Destination.SETTINGS))
            assertTrue(NowEntryRefreshPolicy.entersNow(previous, Destination.NOW))
    }

    @Test fun `navigation refresh uses active work and never suppresses a completed quick return`() {
        val key = "selected-place|METEOGROUP_REGIONAL"
        fun allowed(active: String? = null, pending: Boolean = false) =
            NowEntryRefreshPolicy.shouldStart(key, active, pending)
        assertFalse(NowEntryRefreshPolicy.shouldStart(null, null, false))
        assertFalse(allowed(active = key))
        assertFalse(allowed(pending = true))
        assertTrue(allowed()) // also true immediately after a previous load completed
        assertTrue(allowed(active = "other-place|METEOGROUP_REGIONAL"))
    }

    @Test fun `bottom glyphs grow without changing bar or rail and both navigations use transition policy`() {
        val source = listOf(File("src/main/java/com/rainalarm/app/MainActivity.kt"),
            File("app/src/main/java/com/rainalarm/app/MainActivity.kt")).first(File::isFile).readText()
        val bottom = source.substringAfter("NavigationBar(containerColor = Surface")
            .substringBefore("NavigationRail(containerColor = Surface")
        assertTrue(bottom.contains("onClick = { navigateTo(item) }"))
        assertTrue(bottom.contains("modifier = Modifier.size(29.dp)"))
        assertFalse(bottom.contains("Modifier.height(80.dp)"))
        val rail = source.substringAfter("NavigationRail(containerColor = Surface")
        assertTrue(rail.contains("onClick = { navigateTo(item) }"))
        assertTrue(rail.contains("icon = { Icon(item.icon, contentDescription = null) }"))
        assertTrue(source.contains("NowEntryRefreshPolicy.entersNow(previous, next)"))
        assertTrue(source.contains("forecastRefreshVersion.value++"))
        assertTrue(source.contains("forecastRefreshVersion.value == request.refreshVersion"))
        val nowSource = listOf(File("src/main/java/com/rainalarm/app/ui/NowScreen.kt"),
            File("app/src/main/java/com/rainalarm/app/ui/NowScreen.kt")).first(File::isFile).readText()
        assertTrue(nowSource.contains("val entryProgress = remember(visitGeneration, selectedLocationKey)"))
    }
}
