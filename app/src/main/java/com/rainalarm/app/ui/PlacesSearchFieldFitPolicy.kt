package com.rainalarm.app.ui

internal data class PlacesSearchTextFit(
    val fontSizeSp: Float,
    val useBaseLineHeight: Boolean,
    val measuredNaturalHeightPx: Int,
)

/** Fits natural font metrics into the fixed-height outlined field without disabling scaling. */
internal object PlacesSearchFieldFitPolicy {
    // Material 3's outlined String TextField overload uses 16 dp top and bottom content padding.
    const val outlinedContentPaddingDp = 16f
    const val minimumSafeTextSlotDp = 12f
    const val minimumFontSizeSp = 8f
    const val fittingIterations = 14

    // Stable across queries so typing different glyphs cannot make the field's type size jump.
    const val representativeGlyphSample = "ÅÉAgjypq"

    fun layoutHeightDp(
        outlinedContainerHeightDp: Float,
        floatingLabelHalfHeightDp: Float,
    ): Float {
        require(outlinedContainerHeightDp.isFinite() && outlinedContainerHeightDp > 0f)
        require(floatingLabelHalfHeightDp.isFinite() && floatingLabelHalfHeightDp >= 0f)
        return outlinedContainerHeightDp + floatingLabelHalfHeightDp
    }

    fun availableTextHeightPx(
        outlinedContainerHeightPx: Int,
        verticalContentPaddingPx: Int,
        floatingLabelHeightPx: Int,
        minimumSafeTextSlotPx: Int,
    ): Int {
        require(outlinedContainerHeightPx > 0)
        require(verticalContentPaddingPx >= 0)
        require(floatingLabelHeightPx >= 0)
        require(minimumSafeTextSlotPx > 0)
        val topPaddingPx = maxOf(verticalContentPaddingPx, (floatingLabelHeightPx + 1) / 2)
        return (outlinedContainerHeightPx - verticalContentPaddingPx - topPaddingPx).also {
            require(it >= minimumSafeTextSlotPx)
        }
    }

    fun fit(
        availableHeightPx: Int,
        baseFontSizeSp: Float,
        measuredBaseStyleHeightPx: Int,
        measureNaturalHeightPx: (fontSizeSp: Float) -> Int,
    ): PlacesSearchTextFit {
        require(availableHeightPx > 0)
        require(baseFontSizeSp.isFinite() && baseFontSizeSp > 0f)
        require(baseFontSizeSp >= minimumFontSizeSp)
        require(measuredBaseStyleHeightPx > 0)

        fun measuredNaturalHeight(fontSizeSp: Float): Int {
            return measureNaturalHeightPx(fontSizeSp).also { require(it > 0) }
        }

        val naturalBaseHeightPx = measuredNaturalHeight(baseFontSizeSp)

        // Preserve the complete Material style only when its declared line box is both large
        // enough for the natural glyph metrics and small enough for the editable text slot.
        if (measuredBaseStyleHeightPx in naturalBaseHeightPx..availableHeightPx) {
            return PlacesSearchTextFit(
                fontSizeSp = baseFontSizeSp,
                useBaseLineHeight = true,
                measuredNaturalHeightPx = naturalBaseHeightPx,
            )
        }

        // A natural line at the base size can fit even when Material's declared line height
        // cannot. Dropping only that declaration avoids creating an undersized line box.
        if (naturalBaseHeightPx <= availableHeightPx) {
            return PlacesSearchTextFit(
                fontSizeSp = baseFontSizeSp,
                useBaseLineHeight = false,
                measuredNaturalHeightPx = naturalBaseHeightPx,
            )
        }

        var fittingFontSizeSp = minimumFontSizeSp
        var fittingHeightPx = measuredNaturalHeight(fittingFontSizeSp)
        require(fittingHeightPx <= availableHeightPx) {
            "The editable text slot cannot fit the minimum safe font size"
        }
        var nonFittingFontSizeSp = baseFontSizeSp
        repeat(fittingIterations) {
            val candidateFontSizeSp = (fittingFontSizeSp + nonFittingFontSizeSp) / 2f
            val candidateHeightPx = measuredNaturalHeight(candidateFontSizeSp)
            if (candidateHeightPx <= availableHeightPx) {
                fittingFontSizeSp = candidateFontSizeSp
                fittingHeightPx = candidateHeightPx
            } else {
                nonFittingFontSizeSp = candidateFontSizeSp
            }
        }
        return PlacesSearchTextFit(
            fontSizeSp = fittingFontSizeSp,
            useBaseLineHeight = false,
            measuredNaturalHeightPx = fittingHeightPx,
        )
    }
}
