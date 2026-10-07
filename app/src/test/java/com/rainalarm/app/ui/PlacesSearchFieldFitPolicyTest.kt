package com.rainalarm.app.ui

import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesSearchFieldFitPolicyTest {
    @Test fun `floating label allowance preserves a 56 dp outlined container`() {
        assertEquals(64f, PlacesSearchFieldFitPolicy.layoutHeightDp(56f, 8f), 0.001f)
        assertEquals(72f, PlacesSearchFieldFitPolicy.layoutHeightDp(56f, 16f), 0.001f)
        assertEquals(
            24,
            PlacesSearchFieldFitPolicy.availableTextHeightPx(
                outlinedContainerHeightPx = 56,
                verticalContentPaddingPx = 16,
                floatingLabelHeightPx = 16,
                minimumSafeTextSlotPx = 12,
            ),
        )
        assertEquals(
            16,
            PlacesSearchFieldFitPolicy.availableTextHeightPx(
                outlinedContainerHeightPx = 56,
                verticalContentPaddingPx = 16,
                floatingLabelHeightPx = 48,
                minimumSafeTextSlotPx = 12,
            ),
        )
        assertTrue(PlacesSearchFieldFitPolicy.representativeGlyphSample.contains("Agjypq"))
    }

    @Test fun `normal Material input typography is unchanged when its natural glyphs fit`() {
        val result = PlacesSearchFieldFitPolicy.fit(
            availableHeightPx = 24,
            baseFontSizeSp = 16f,
            measuredBaseStyleHeightPx = 24,
            measureNaturalHeightPx = { 19 },
        )

        assertEquals(16f, result.fontSizeSp, 0f)
        assertTrue(result.useBaseLineHeight)
        assertEquals(19, result.measuredNaturalHeightPx)
    }

    @Test fun `natural line replaces a declared line box that could clip glyph metrics`() {
        val result = PlacesSearchFieldFitPolicy.fit(
            availableHeightPx = 24,
            baseFontSizeSp = 16f,
            measuredBaseStyleHeightPx = 20,
            measureNaturalHeightPx = { 22 },
        )

        assertEquals(16f, result.fontSizeSp, 0f)
        assertFalse(result.useBaseLineHeight)
        assertEquals(22, result.measuredNaturalHeightPx)
    }

    @Test fun `moderately enlarged font stays at the base size when a natural line fits`() {
        val result = PlacesSearchFieldFitPolicy.fit(
            availableHeightPx = 24,
            baseFontSizeSp = 16f,
            measuredBaseStyleHeightPx = 29,
            measureNaturalHeightPx = { fontSizeSp -> ceil(fontSizeSp * 1.25f).toInt() },
        )

        assertEquals(16f, result.fontSizeSp, 0f)
        assertFalse(result.useBaseLineHeight)
        assertEquals(20, result.measuredNaturalHeightPx)
    }

    @Test fun `large scale finds the largest naturally measured line that fits`() {
        val result = PlacesSearchFieldFitPolicy.fit(
            availableHeightPx = 24,
            baseFontSizeSp = 16f,
            measuredBaseStyleHeightPx = 48,
            measureNaturalHeightPx = { fontSizeSp -> ceil(fontSizeSp * 2f).toInt() },
        )

        assertTrue(result.fontSizeSp in 11.99f..12f)
        assertFalse(result.useBaseLineHeight)
        assertEquals(24, result.measuredNaturalHeightPx)
    }

    @Test fun `fitted size is monotonic and rendered natural height stays capped`() {
        var previousFontSizeSp = Float.POSITIVE_INFINITY
        var previousRenderedHeightPx = 0
        for (fontScale in listOf(0.9f, 1f, 1.25f, 1.5f, 2f, 3f)) {
            val result = PlacesSearchFieldFitPolicy.fit(
                availableHeightPx = 24,
                baseFontSizeSp = 16f,
                measuredBaseStyleHeightPx = ceil(24f * fontScale).toInt(),
                measureNaturalHeightPx = { fontSizeSp ->
                    ceil(fontSizeSp * fontScale).toInt()
                },
            )

            assertTrue(result.fontSizeSp <= previousFontSizeSp + 0.001f)
            assertTrue(result.measuredNaturalHeightPx >= previousRenderedHeightPx)
            assertTrue(result.measuredNaturalHeightPx <= 24)
            previousFontSizeSp = result.fontSizeSp
            previousRenderedHeightPx = result.measuredNaturalHeightPx
        }
    }

    @Test fun `invalid metrics and a slot below the safe font bound are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlacesSearchFieldFitPolicy.fit(
                availableHeightPx = 24,
                baseFontSizeSp = 16f,
                measuredBaseStyleHeightPx = 24,
                measureNaturalHeightPx = { 0 },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlacesSearchFieldFitPolicy.fit(
                availableHeightPx = 12,
                baseFontSizeSp = 16f,
                measuredBaseStyleHeightPx = 24,
                measureNaturalHeightPx = { fontSizeSp -> ceil(fontSizeSp * 2f).toInt() },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlacesSearchFieldFitPolicy.availableTextHeightPx(
                outlinedContainerHeightPx = 43,
                verticalContentPaddingPx = 16,
                floatingLabelHeightPx = 32,
                minimumSafeTextSlotPx = 12,
            )
        }
    }
}
