package com.rainalarm.app.ui

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherArtworkTest {
    private fun main(path: String): File = listOf(File("src/main/$path"), File("app/src/main/$path"))
        .firstOrNull(File::isFile) ?: error("Missing $path")

    @Test fun `supplied launcher pack has complete legacy and adaptive raster densities`() {
        val sizes = mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96,
            "xxhdpi" to 144, "xxxhdpi" to 192)
        sizes.forEach { (density, size) ->
            for (name in listOf("ic_launcher", "ic_launcher_round", "ic_launcher_foreground", "ic_launcher_background")) {
                val image = ImageIO.read(main("res/mipmap-$density/$name.png"))
                val expected = if (name.endsWith("foreground") || name.endsWith("background")) size * 9 / 4 else size
                assertEquals(expected, image.width)
                assertEquals(expected, image.height)
                if (name == "ic_launcher_round" || name == "ic_launcher_foreground") {
                    assertEquals(0, image.getRGB(0, 0) ushr 24)
                }
            }
        }
        val store = ImageIO.read(File("artwork/play_store_icon_512.png").takeIf(File::isFile)
            ?: File("../artwork/play_store_icon_512.png"))
        assertEquals(512, store.width)
        assertEquals(512, store.height)
    }

    @Test fun `adaptive launcher uses supplied raster layers while notification stays separate`() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val icon = main("res/mipmap-anydpi-v26/$name.xml").readText()
            assertTrue(icon.contains("@mipmap/ic_launcher_foreground"))
            assertTrue(icon.contains("@mipmap/ic_launcher_background"))
        }
        assertTrue(!main("res/drawable/ic_notification.xml").readText().contains("#FF111727"))
        val notification = main("res/drawable/ic_notification.xml").readText()
        assertTrue(notification.contains("android:viewportWidth=\"24\""))
    }
}
