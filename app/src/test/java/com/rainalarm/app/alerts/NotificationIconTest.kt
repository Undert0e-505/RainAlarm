package com.rainalarm.app.alerts

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIconTest {
    private fun source(path: String): String {
        val file = listOf(File("src/main/$path"), File("app/src/main/$path"))
            .firstOrNull(File::isFile)
        requireNotNull(file) { "Missing $path" }
        return file.readText()
    }

    @Test
    fun `notification builder uses dedicated small icon and launcher remains separate`() {
        val alerts = source("java/com/rainalarm/app/alerts/RainAlerts.kt")
        assertTrue(alerts.contains(".setSmallIcon(R.drawable.ic_notification)"))
        assertFalse(alerts.contains(".setSmallIcon(R.drawable.ic_launcher)"))
        val manifest = source("AndroidManifest.xml")
        assertTrue(manifest.contains("@mipmap/ic_launcher"))
        assertTrue(manifest.contains("@mipmap/ic_launcher_round"))
    }

    @Test
    fun `small icon has a transparent canvas with only white visible paths`() {
        val vector = source("res/drawable/ic_notification.xml")
        assertTrue(vector.contains("android:viewportWidth=\"24\""))
        assertTrue(vector.contains("android:viewportHeight=\"24\""))
        assertFalse(vector.contains("M0,0h24"))
        assertFalse(vector.contains("M0,0h48"))
        val colors = Regex("android:(?:fillColor|strokeColor)=\"([^\"]+)\"")
            .findAll(vector).map { it.groupValues[1] }.toList()
        assertTrue(colors.isNotEmpty())
        assertTrue(colors.any { it.equals("#FFFFFFFF", ignoreCase = true) })
        assertTrue(colors.all { it.equals("#FFFFFFFF", ignoreCase = true) ||
            it.equals("#00000000", ignoreCase = true) })
        assertTrue(vector.contains("M12,2 L5.8,8.2"))
        assertTrue(vector.contains("18.2,8.2 L12,2"))
        // Shoulders from the tip have equal rise/run and therefore enclose exactly 90 degrees.
        val leftX = 5.8 - 12.0
        val leftY = 8.2 - 2.0
        val rightX = 18.2 - 12.0
        val rightY = 8.2 - 2.0
        assertTrue(kotlin.math.abs(leftX * rightX + leftY * rightY) < 0.0001)
    }
}
