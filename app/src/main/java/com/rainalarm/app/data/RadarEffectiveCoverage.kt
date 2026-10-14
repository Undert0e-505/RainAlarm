package com.rainalarm.app.data

import com.rainalarm.app.domain.GeoPoint
import com.rainalarm.app.domain.GeoQuad
import com.rainalarm.app.domain.IntensityGrid
import com.rainalarm.app.domain.MeteoNominalCoverage
import com.rainalarm.app.domain.WebMercator
import kotlin.math.roundToInt

/**
 * The alpha field used by the map's nearest-neighbour unknown-area raster.
 *
 * A zero byte is fully lit on the map. Any non-zero byte contributes visible shadow and is not
 * authoritative coverage for the selected location. Keeping the byte field next to the rendered
 * bitmap makes provider selection and rendering use one quantised coverage representation.
 */
data class RadarEffectiveCoverage(
    val bounds: GeoQuad,
    val width: Int,
    val height: Int,
    val unknownAlpha: ByteArray,
    val semantics: RadarCoverageSemantics,
) {
    init {
        require(width > 0 && height > 0)
        require(unknownAlpha.size == width * height)
    }

    fun covers(point: GeoPoint): Boolean {
        val (left, top) = WebMercator.worldFraction(bounds.topLeft)
        val (right, bottom) = WebMercator.worldFraction(bounds.bottomRight)
        val (x, y) = WebMercator.worldFraction(point)
        if (right <= left || bottom <= top || x !in left..right || y !in top..bottom) return false
        val pixelX = (((x - left) / (right - left)) * width).toInt().coerceIn(0, width - 1)
        val pixelY = (((y - top) / (bottom - top)) * height).toInt().coerceIn(0, height - 1)
        return unknownAlpha[pixelY * width + pixelX].toInt() and 0xff == 0
    }

    companion object {
        /** Uses the same rounding as the alpha bitmap supplied to MapLibre. */
        fun fromCoveredWeights(
            bounds: GeoQuad,
            coverage: IntensityGrid,
            semantics: RadarCoverageSemantics,
        ): RadarEffectiveCoverage = RadarEffectiveCoverage(
            bounds = bounds,
            width = coverage.width,
            height = coverage.height,
            unknownAlpha = ByteArray(coverage.values.size) { index ->
                ((1f - coverage.values[index].coerceIn(0f, 1f)) * 255f)
                    .roundToInt().toByte()
            },
            semantics = semantics,
        )
    }
}

/** The single eligibility predicate for accepting or reusing a loaded radar session. */
object RadarSessionCoverage {
    fun coverageAt(session: RadarSession, point: GeoPoint): Boolean? {
        if (session.isReleased) return false
        val capability = RadarProviderCapabilityResolver.capability(
            session.providerSelection.active,
            point,
        )
        if (!capability.geographicallyUsable) return false
        session.effectiveCoverage?.let { return it.covers(point) }
        return when (session.providerSelection.active) {
            RadarProviderKind.METEOGROUP_REGIONAL -> session.region?.id?.let { areaId ->
                MeteoNominalCoverage.covers(areaId, point)
            }
            RadarProviderKind.EUMETNET_OPERA,
            RadarProviderKind.OPEN_RAINVIEWER,
            -> null
        }
    }

    fun state(session: RadarSession, point: GeoPoint): RadarProviderCoverageState {
        val capability = RadarProviderCapabilityResolver.capability(
            session.providerSelection.active,
            point,
        )
        if (!capability.geographicallyUsable) return capability.state
        return RadarProviderCapabilityResolver.refine(capability, coverageAt(session, point)).state
    }
}

/** A downloaded candidate is authoritative only after its effective map coverage proves the pin. */
object RadarCandidateCoveragePolicy {
    fun accepts(state: RadarProviderCoverageState): Boolean =
        state == RadarProviderCoverageState.COVERED
}
