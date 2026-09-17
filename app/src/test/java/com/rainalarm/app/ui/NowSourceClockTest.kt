package com.rainalarm.app.ui

import com.rainalarm.app.domain.RainMinuteAvailability
import com.rainalarm.app.domain.RainMinutePoint
import com.rainalarm.app.domain.RainMinuteSeries
import com.rainalarm.app.domain.NowVisualGeometry
import com.rainalarm.app.data.NowWeatherMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import javax.imageio.ImageIO

class NowSourceClockTest {
    @Test
    fun threeBandsAndTimeAxisFitCompactAndNormalWidthsWithoutClipping() {
        assertEquals(0, NowChartLayout.severityBand(0.90f))
        assertEquals(1, NowChartLayout.severityBand(0.50f))
        assertEquals(2, NowChartLayout.severityBand(0.10f))
        val compact = NowLayoutPolicy.measure(320, 580)
        assertTrue(compact.totalHeightDp <= 580)
        assertTrue(compact.compassHeightDp > compact.chartHeightDp)
        assertTrue(compact.compassHeightDp - NowLayoutPolicy.cardHeaderHeightDp >= 180)
        assertTrue(compact.chartHeightDp - NowLayoutPolicy.chartTitleHeightDp -
            NowLayoutPolicy.chartAxisHeightDp >= 100)
        assertTrue(!NowLayoutPolicy.usesVerticalScroll)
        for ((width, height, fontScale) in listOf(
            Triple(320, 580, 1f), Triple(390, 700, 1f),
            Triple(600, 480, 1.3f), Triple(900, 1000, 1f),
        )) {
            val layout = NowLayoutPolicy.measure(width, height, fontScale)
            assertTrue(layout.totalHeightDp <= height)
            assertTrue(layout.headerHeightDp >= 48)
            assertTrue(layout.chartHeightDp >= NowLayoutPolicy.minimumChartDp)
        }
        assertEquals("Next 54 minutes", NowChartLayout.horizonTitle(54))
        assertEquals("Next 30 minutes", NowChartLayout.horizonTitle(30))
        assertEquals("Next 7 minutes", NowChartLayout.horizonTitle(7))
        assertEquals("Current radar", NowChartLayout.horizonTitle(0))
        assertEquals("Next hour", NowChartLayout.horizonTitle(60))
        for (domain in listOf(0, 7, 30, 54, 60)) {
            assertEquals(if (domain > 0) 24f else 150f,
                NowChartLayout.plotX(0, 300f, 24f, domain), 0.001f)
            assertEquals(if (domain > 0) 276f else 150f,
                NowChartLayout.plotX(domain, 300f, 24f, domain), 0.001f)
        }
    }

    @Test
    fun bottomCornerReadoutsPreserveCompassAndChartOnPhones() {
        val standardCard = NowLayoutPolicy.measure(360, 700)
        val bodyHeight = standardCard.compassHeightDp - NowLayoutPolicy.cardHeaderHeightDp - 8
        val footerHeight = NowWeatherReadoutPolicy.footerHeightDp(5, 312, 1f)
        val dial = NowWeatherReadoutPolicy.dialDiameterDp(312, bodyHeight, footerHeight)
        val previousBodyHeight = bodyHeight - 22
        val previousDial = minOf(296, ((previousBodyHeight - footerHeight) / 0.93f).toInt(), 300)
        assertTrue(dial >= 310)
        assertTrue(dial > previousDial)
        assertTrue(dial <= 340)
        assertTrue(dial * 0.93f <= bodyHeight - footerHeight + 1)
        assertTrue(standardCard.chartHeightDp - NowLayoutPolicy.chartTitleHeightDp -
            NowLayoutPolicy.chartAxisHeightDp >= 100)
        assertTrue(!NowWeatherReadoutPolicy.stack(312, 1f))
        assertTrue(NowWeatherReadoutPolicy.stack(250, 1.5f))
        assertTrue(NowWeatherReadoutPolicy.footerHeightDp(5, 250, 1.5f) > footerHeight)
        assertEquals(10, NowWeatherReadoutPolicy.fontSizeSp(320, 1f))
        assertEquals(10, NowWeatherReadoutPolicy.fontSizeSp(358, 1f))
        assertEquals(10, NowWeatherReadoutPolicy.fontSizeSp(280, 1f))
        assertEquals(9, NowWeatherReadoutPolicy.fontSizeSp(250, 1.4f))
        assertEquals(13, NowWeatherReadoutPolicy.rowMinimumHeightDp(10))
        assertEquals("Temp", NowWeatherReadoutPolicy.label(NowWeatherMetric.TEMPERATURE))
        assertEquals("Pressure", NowWeatherReadoutPolicy.label(NowWeatherMetric.PRESSURE))
        assertEquals("Humidity", NowWeatherReadoutPolicy.label(NowWeatherMetric.HUMIDITY))
        assertEquals("UV", NowWeatherReadoutPolicy.label(NowWeatherMetric.UV_INDEX))
        assertEquals("Wind", NowWeatherReadoutPolicy.label(NowWeatherMetric.WIND))
        val source = java.io.File("src/main/java/com/rainalarm/app/ui/NowScreen.kt")
            .takeIf(java.io.File::isFile) ?: java.io.File("app/src/main/java/com/rainalarm/app/ui/NowScreen.kt")
        val ui = source.readText()
        assertTrue(ui.contains("Modifier.align(Alignment.BottomCenter).fillMaxWidth()"))
        assertTrue(ui.contains("Modifier.align(Alignment.TopCenter).size(diameter)"))
        assertTrue(ui.contains("val footerDirection = when"))
        assertTrue(ui.contains(".height(NowWeatherReadoutPolicy.directionLineHeightDp.dp)"))
        assertTrue(!ui.contains("Modifier.fillMaxWidth().height(22.dp)"))
        assertTrue(ui.contains("Arrangement.SpaceBetween"))
        assertTrue(!ui.contains("Modifier.weight(1.45f)"))
    }

    @Test
    fun standardPhoneReclaimsAtLeastFortyDpOfPlotWithoutShrinkingDial() {
        val current = NowLayoutPolicy.measure(390, 700)
        val oldFlexible = 700 - 10 * 2 - 60 - 8 * 2
        val oldCompass = (oldFlexible * 0.75f).toInt()
        val oldChart = oldFlexible - oldCompass
        val widthInsideCard = 390 - 16 * 2 - 8 * 2
        val footer = NowWeatherReadoutPolicy.footerHeightDp(5, widthInsideCard, 1f)
        val oldDial = NowWeatherReadoutPolicy.dialDiameterDp(
            widthInsideCard, oldCompass - 64 - 16, footer,
        )
        val newDial = NowWeatherReadoutPolicy.dialDiameterDp(
            widthInsideCard, current.compassHeightDp - NowLayoutPolicy.cardHeaderHeightDp - 8, footer,
        )
        assertTrue(current.chartHeightDp - oldChart >= 40)
        assertTrue(newDial >= oldDial)
        assertTrue(current.totalHeightDp <= 700)
    }

    @Test
    fun tenMinuteMarksStayOnLocalClockBoundariesInsideRealForecastHorizon() {
        val london = ZoneId.of("Europe/London")
        fun ticks(utc: String, end: Int, width: Int = 390) = NowChartLayout.clockTicks(
            Instant.parse(utc).epochSecond, end, london, width, 1f,
        )
        assertEquals(listOf("Now", "19:20", "19:30", "19:40", "19:50", "20:00"),
            ticks("2026-09-16T18:15:00Z", 52).map { it.label })
        assertEquals(listOf("Now", "19:50", "20:00", "20:10", "20:20", "20:30"),
            ticks("2026-09-16T18:40:00Z", 52).map { it.label })
        assertEquals(listOf("Now"), ticks("2026-09-16T18:15:00Z", 3).map { it.label })
        assertEquals(listOf("Now", "23:50", "00:00", "00:10", "00:20", "00:30"),
            ticks("2026-09-16T22:45:00Z", 52).map { it.label })
        assertEquals(listOf("Now", "02:00", "02:10", "02:20", "02:30", "02:40"),
            ticks("2026-03-29T00:50:00Z", 52).map { it.label })
        assertEquals("01:00", ticks("2026-10-25T00:40:00Z", 52)[2].label)
        assertEquals(listOf("Now"), ticks("2026-09-16T18:15:00Z", 0).map { it.label })
        val compact = ticks("2026-09-16T18:15:00Z", 52, 320)
        assertEquals(6, compact.size) // marks remain even when labels do not fit
        assertTrue(compact.any { !it.showLabel })
        assertTrue(compact.first().showLabel)
        val secondsOffset = NowChartLayout.clockTicks(
            Instant.parse("2026-09-16T18:15:20Z").epochSecond, 52, london, 390, 1f,
        )[1].offsetMinutes
        assertEquals(4f + 40f / 60f, secondsOffset, 0.001f)
    }

    @Test
    fun rainMarkerUsesOneSilhouetteAndSharedMotionIsBoundedAndSynchronized() {
        val source = java.io.File("src/main/java/com/rainalarm/app/ui/NowScreen.kt")
            .takeIf(java.io.File::isFile) ?: java.io.File("app/src/main/java/com/rainalarm/app/ui/NowScreen.kt")
        val canvas = source.readText()
        assertTrue(canvas.contains("val silhouette = Path().apply"))
        assertTrue(canvas.contains("arcTo(Rect("))
        assertTrue(canvas.contains("drawPath(silhouette, requireNotNull(pointerFill))"))
        assertTrue(canvas.contains("clipPath(silhouette)"))
        assertTrue(canvas.contains("drawPath(silhouette, NowAccent.copy(alpha = 0.85f)"))
        assertTrue(!canvas.contains("drawCircle(fill, radius, indicator)"))
        assertTrue(!canvas.contains("NowVisualGeometry.rainHat"))
        assertTrue(canvas.contains("NowMotionPolicy.centerDiscScale(entryProgress)"))
        assertTrue(canvas.contains("NowMotionPolicy.centerScale(entryProgress)"))
        assertTrue(canvas.contains("val centerFill = pointerFill ?: NowDeepBlue"))
        assertTrue(canvas.contains("drawPath(silhouette, requireNotNull(pointerFill))"))
        assertTrue(canvas.contains("drawCircle(centerFill,"))
        assertEquals(1000, NowMotionPolicy.durationMillis)
        assertEquals(1.16f, NowMotionPolicy.centerScale(0f), 0.0001f)
        assertEquals(1.08f, NowMotionPolicy.centerScale(0.5f), 0.0001f)
        assertEquals(1f, NowMotionPolicy.centerScale(1f), 0.0001f)
        assertEquals(1.16f, NowMotionPolicy.centerDiscScale(0f), 0.0001f)
        assertEquals(1.08f, NowMotionPolicy.centerDiscScale(0.5f), 0.0001f)
        assertEquals(1f, NowMotionPolicy.centerDiscScale(1f), 0.0001f)
        assertEquals(0.225f * 0.75f, NowMotionPolicy.centerDiscRadiusFraction, 0.000001f)
        assertEquals(48f, NowMotionPolicy.centerFontSizeSp(331f, 1f, "Now", 97f), 0.0001f)
        assertEquals(48f, NowMotionPolicy.centerFontSizeSp(331f, 1f, "14°", 72f), 0.0001f)
        assertEquals(48f, NowMotionPolicy.centerFontSizeSp(331f, 1f, "-20°", 96f), 0.0001f)
        assertEquals(48f, NowMotionPolicy.centerFontSizeSp(331f, 1f, "+35°", 102f), 0.0001f)
        assertEquals(20f, NowMotionPolicy.centerUnitFontSizeSp(48f), 0.0001f)
        for ((width, height, fontScale) in listOf(Triple(390, 700, 1f), Triple(320, 580, 1.4f))) {
            val layout = NowLayoutPolicy.measure(width, height, fontScale)
            val bodyWidth = width - layout.horizontalPaddingDp * 2 - 16
            val footer = NowWeatherReadoutPolicy.footerHeightDp(5, bodyWidth, fontScale)
            val diameter = NowWeatherReadoutPolicy.dialDiameterDp(bodyWidth,
                layout.compassHeightDp - NowLayoutPolicy.cardHeaderHeightDp - 8, footer).toFloat()
            val usable = NowMotionPolicy.centerUsableDiameterDp(diameter)
            for ((label, targetWidthDp) in listOf(
                "Now" to 97f, "60" to 56f, "—" to 24f, "14°" to 72f, "-20°" to 96f,
                "+35°" to 102f, "+120°" to 131f,
            )) {
                val actualTargetWidth = targetWidthDp * fontScale
                val main = NowMotionPolicy.centerFontSizeSp(diameter, fontScale, label, actualTargetWidth)
                val unit = if (label == "60") NowMotionPolicy.centerUnitFontSizeSp(main) else 0f
                for (progress in listOf(0f, 0.5f, 1f)) {
                    val textScale = NowMotionPolicy.centerScale(progress)
                    val discScale = NowMotionPolicy.centerDiscScale(progress)
                    val available = 2f * NowMotionPolicy.centerDiscRadiusFraction * diameter * discScale - 6f
                    assertTrue("$label width at $progress", actualTargetWidth * main / 48f * textScale <= available + 0.01f)
                    assertTrue("$label height at $progress", (main + unit) * 1.2f * fontScale * textScale <= available + 0.01f)
                }
                assertTrue(actualTargetWidth * main / 48f <= usable + 0.01f)
            }
        }
        assertTrue(NowMotionPolicy.centerFontSizeSp(331f, 1f, "+120°", 131f) < 48f)
        val compactDialDp = 244f
        val halfOutlineStrokeDp = 1f
        assertTrue((NowMotionPolicy.centerDiscRadiusFraction + 0.009f) * NowMotionPolicy.centerDiscScale(0f) +
            halfOutlineStrokeDp / compactDialDp < 0.30f - 0.055f / 2f)
        assertTrue(canvas.contains("val visibleProgress = if (animationsEnabled) entryProgress.value else 1f"))
        assertEquals(20f, NowMotionPolicy.revealRight(20f, 120f, 0f), 0.001f)
        assertEquals(70f, NowMotionPolicy.revealRight(20f, 120f, 0.5f), 0.001f)
        assertEquals(120f, NowMotionPolicy.revealRight(20f, 120f, 1f), 0.001f)
    }

    @Test
    fun pointerTextureFollowsTheSinglePieceSilhouetteWithoutAnotherDecode() {
        val source = File("src/main/java/com/rainalarm/app/ui/NowScreen.kt")
            .takeIf(File::isFile) ?: File("app/src/main/java/com/rainalarm/app/ui/NowScreen.kt")
        val canvas = source.readText()
        val pointerStart = canvas.indexOf("if (sourceBearing != null && hasRain) {")
        val fill = canvas.indexOf("drawPath(silhouette, requireNotNull(pointerFill))", pointerStart)
        val clip = canvas.indexOf("clipPath(silhouette)", fill)
        val textureIndex = canvas.indexOf("drawImage(rainTexture,", clip)
        val outline = canvas.indexOf("drawPath(silhouette, NowAccent.copy(alpha = 0.85f)", textureIndex)
        val disc = canvas.indexOf("drawCircle(centerFill, discRadius, center)", outline)
        assertTrue(pointerStart >= 0 && pointerStart < fill && fill < clip && clip < textureIndex && textureIndex < outline && outline < disc)
        assertTrue(canvas.contains("NowRainTexturePolicy.pointerTextureDiameterPx(radius)"))
        assertTrue(canvas.contains("rotate(animatedBearing, pivot = indicator)"))
        assertTrue(canvas.contains("srcOffset = IntOffset(pointerTextureCrop.left, pointerTextureCrop.top)"))
        assertTrue(canvas.contains("indicator.x - halfTexture"))
        assertTrue(canvas.contains("indicator.y - halfTexture"))
        assertEquals(1, Regex("BitmapFactory\\.decodeResource\\(").findAll(canvas).count())
        assertTrue(!canvas.contains("BlendMode.Screen"))

        assertEquals(NowRainTextureCrop(224, 144, 680), NowRainTexturePolicy.pointerCrop(1088, 1088))
        assertEquals(NowRainTextureCrop(112, 72, 340), NowRainTexturePolicy.pointerCrop(544, 544))
        val textureFile = File("src/main/res/drawable-nodpi/now_rain_drops.png")
            .takeIf(File::isFile) ?: File("app/src/main/res/drawable-nodpi/now_rain_drops.png")
        val image = ImageIO.read(textureFile)
        val crop = NowRainTexturePolicy.pointerCrop(image.width, image.height)
        val topCentreX = crop.left + crop.side / 2
        assertTrue("the pointer tip samples visible texture", image.getRGB(topCentreX, crop.top) ushr 24 >= 60)
        assertTrue("texture continues inward from the tip", image.getRGB(topCentreX, crop.top + 20) ushr 24 >= 60)

        for (radius in listOf(10f, 40f)) {
            val half = NowRainTexturePolicy.pointerTextureDiameterPx(radius) / 2f
            assertEquals(kotlin.math.sqrt(2f) * radius, half, 0.001f)
            for (bearing in listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)) {
                val shape = NowVisualGeometry.rainDropOutline(bearing, radius)
                val radians = Math.toRadians(bearing)
                val rotatedTop = (kotlin.math.sin(radians) * half).toFloat() to
                    (-kotlin.math.cos(radians) * half).toFloat()
                assertEquals(shape.tip.first, rotatedTop.first, 0.001f)
                assertEquals(shape.tip.second, rotatedTop.second, 0.001f)
                for (point in listOf(shape.firstJoin, shape.tip, shape.secondJoin)) {
                    assertTrue(kotlin.math.abs(point.first) <= half + 0.001f)
                    assertTrue(kotlin.math.abs(point.second) <= half + 0.001f)
                }
            }
        }
    }

    @Test
    fun rainDiscKeepsPointerFillWithOpaqueWhiteShadowedText() {
        val source = java.io.File("src/main/java/com/rainalarm/app/ui/NowScreen.kt")
            .takeIf(java.io.File::isFile) ?: java.io.File("app/src/main/java/com/rainalarm/app/ui/NowScreen.kt")
        val canvas = source.readText()
        assertTrue(canvas.contains("val centerFill = pointerFill ?: NowDeepBlue"))
        assertTrue(canvas.contains("Text(centerLabel, color = Color.White"))
        assertTrue(canvas.contains("Text(\"min\", color = Color.White"))
        assertTrue(canvas.contains("style = TextStyle(shadow = CenterGlyphShadow)"))
        assertTrue(canvas.contains("rememberTextMeasurer()"))
        assertTrue(!canvas.contains("NowCenterColorPolicy"))
    }

    @Test
    fun rainTextureIsTransparentRainOnlyAndFollowsAnimatedDisc() {
        assertTrue(NowRainTexturePolicy.shouldShow(true, null))
        assertTrue(NowRainTexturePolicy.shouldShow(false, 12))
        assertTrue(!NowRainTexturePolicy.shouldShow(false, null))
        assertEquals(NowRainTextureCrop(0, 0, 544), NowRainTexturePolicy.crop(544, 544))
        assertEquals(NowRainTextureCrop(48, 0, 544), NowRainTexturePolicy.crop(640, 544))
        for (progress in listOf(0f, 0.5f, 1f)) {
            assertEquals(2f * 331f * NowMotionPolicy.centerDiscRadiusFraction *
                NowMotionPolicy.centerDiscScale(progress),
                NowRainTexturePolicy.discDiameterPx(331f, progress), 0.001f)
        }

        val asset = File("src/main/res/drawable-nodpi/now_rain_drops.png")
            .takeIf(File::isFile) ?: File("app/src/main/res/drawable-nodpi/now_rain_drops.png")
        val image = ImageIO.read(asset)
        assertEquals(1088, image.width)
        assertEquals(1088, image.height)
        assertEquals(0, image.getRGB(0, 0) ushr 24)
        assertEquals(0, image.getRGB(1087, 1087) ushr 24)
        var transparent = 0
        var translucent = 0
        for (y in 0 until image.height step 32) for (x in 0 until image.width step 32) {
            val alpha = image.getRGB(x, y) ushr 24
            if (alpha == 0) transparent++ else if (alpha in 1..175) translucent++
            assertTrue("droplets retain base colour", alpha <= 175)
        }
        assertTrue(transparent > 100)
        assertTrue(translucent > 100)

        val source = File("src/main/java/com/rainalarm/app/ui/NowScreen.kt")
            .takeIf(File::isFile) ?: File("app/src/main/java/com/rainalarm/app/ui/NowScreen.kt")
        val canvas = source.readText()
        val fill = canvas.indexOf("drawCircle(centerFill, discRadius, center)")
        val texture = canvas.lastIndexOf("drawImage(rainTexture,")
        val outline = canvas.indexOf("drawCircle(NowAccent.copy(alpha = 0.32f)")
        val text = canvas.indexOf("Text(centerLabel, color = Color.White")
        assertTrue(fill >= 0 && fill < texture && texture < outline && outline < text)
        assertTrue(canvas.contains("if (hasRain && rainTexture != null && rainTextureCrop != null)"))
        assertTrue(canvas.contains("clipPath(clip)"))
        assertTrue(!canvas.contains("BlendMode.Screen"))
    }

    @Test
    fun chartStartsAtWallClockWhileFreshnessUsesObservationTime() {
        val now = Instant.parse("2026-09-16T08:40:00Z").epochSecond
        val series = RainMinuteSeries(
            now,
            listOf(RainMinutePoint(0, 0f, 0f, 0f, true)),
            "regional",
            1.0,
            RainMinuteAvailability.PARTIAL,
            latestObservationEpochSeconds = now - 31 * 60,
        )
        val london = ZoneId.of("Europe/London")
        assertEquals(31L, NowSourceClock.ageMinutes(series, now))
        assertEquals("09:40", NowSourceClock.label(series.startEpochSeconds, 0, london))
        assertEquals("10:40", NowSourceClock.label(series.startEpochSeconds, 60, london))
    }
}
