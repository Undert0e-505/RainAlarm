package com.rainalarm.app.ui

import com.google.gson.JsonObject
import com.rainalarm.app.data.LflLightningPresentation
import com.rainalarm.app.data.LflLightningVisualStyle
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

internal object LflLightningMapLayerIds {
    const val source = "rain-alarm-lfl-flashes-source"
    const val halo = "rain-alarm-lfl-flashes-halo"
    const val core = "rain-alarm-lfl-flashes-core"
    const val innerGlow = "rain-alarm-lfl-flashes-inner-glow"
    const val pinpoint = "rain-alarm-lfl-flashes-pinpoint"
}

/** Owns the experimental LFL GeoJSON source and its four native MapLibre point layers. */
internal class LflLightningMapLayerController(
    private val density: Float,
    private val onLayerError: (String) -> Unit,
) {
    private var currentStyle: Style? = null
    private var source: GeoJsonSource? = null
    private var currentSourceIdentity: String? = null
    private var currentReferenceOffsetSeconds: Double? = null
    private var currentCovered: Boolean? = null

    fun onStyleLoaded(style: Style, presentation: LflLightningPresentation?) {
        try {
            if (currentStyle !== style) {
                currentStyle?.let(::remove)
                currentStyle = style
                currentSourceIdentity = null
                currentReferenceOffsetSeconds = null
                currentCovered = null
                source = null
            }
            if (presentation == null) {
                disable(style)
                return
            }
            ensureLayers(style)
            update(presentation)
        } catch (_: Exception) {
            source = null
            currentSourceIdentity = null
            currentReferenceOffsetSeconds = null
            currentCovered = null
            onLayerError("Lightning layer unavailable")
        }
    }

    fun reconcile(style: Style, presentation: LflLightningPresentation?) {
        if (currentStyle !== style) onStyleLoaded(style, presentation)
        else {
            try {
                if (presentation == null) {
                    disable(style)
                    return
                }
                ensureLayers(style)
                update(presentation)
            } catch (_: Exception) {
                source = null
                currentSourceIdentity = null
                currentReferenceOffsetSeconds = null
                currentCovered = null
                onLayerError("Lightning layer unavailable")
            }
        }
    }

    fun clear() {
        currentStyle?.let(::remove)
        currentStyle = null
        source = null
        currentSourceIdentity = null
        currentReferenceOffsetSeconds = null
        currentCovered = null
    }

    private fun ensureLayers(style: Style) {
        if (source != null && style.getLayer(LflLightningMapLayerIds.pinpoint) != null) return
        remove(style)
        val points = GeoJsonSource(
            LflLightningMapLayerIds.source,
            FeatureCollection.fromFeatures(emptyArray()),
        )
        style.addSource(points)
        source = points

        val halo = CircleLayer(LflLightningMapLayerIds.halo, LflLightningMapLayerIds.source)
            .withProperties(
                PropertyFactory.circleRadius(
                    zoomScaledRadius(LflLightningVisualStyle.haloDiameterDp),
                ),
                PropertyFactory.circleColor(LflLightningVisualStyle.freshColorArgb),
                PropertyFactory.circleOpacity(0f),
                PropertyFactory.circleBlur(0.55f),
            )
        val core = CircleLayer(LflLightningMapLayerIds.core, LflLightningMapLayerIds.source)
            .withProperties(
                PropertyFactory.circleRadius(
                    zoomScaledRadius(LflLightningVisualStyle.coreDiameterDp),
                ),
                PropertyFactory.circleColor(LflLightningVisualStyle.freshColorArgb),
                PropertyFactory.circleOpacity(0f),
                PropertyFactory.circleBlur(LflLightningVisualStyle.coreBlurStop(0)),
            )
        val innerGlow = CircleLayer(
            LflLightningMapLayerIds.innerGlow,
            LflLightningMapLayerIds.source,
        ).withProperties(
            PropertyFactory.circleRadius(
                zoomScaledRadius(LflLightningVisualStyle.innerGlowDiameterDp),
            ),
            PropertyFactory.circleColor(colorHex(LflLightningVisualStyle.innerGlowColorArgb)),
            PropertyFactory.circleOpacity(0f),
            PropertyFactory.circleBlur(0.62f),
        )
        val pinpoint = CircleLayer(
            LflLightningMapLayerIds.pinpoint,
            LflLightningMapLayerIds.source,
        ).withProperties(
            PropertyFactory.circleRadius(
                zoomScaledRadius(LflLightningVisualStyle.pinpointDiameterDp),
            ),
            PropertyFactory.circleColor(colorHex(LflLightningVisualStyle.pinpointColorArgb)),
            PropertyFactory.circleOpacity(0f),
            PropertyFactory.circleBlur(0f),
        )
        // Keep all place/road labels legible. The points sit above map raster/fill layers and
        // below the first symbol layer, with the core directly above its own halo.
        val firstLabel = style.layers.filterIsInstance<SymbolLayer>().firstOrNull()
        if (firstLabel != null) style.addLayerBelow(halo, firstLabel.id) else style.addLayer(halo)
        style.addLayerAbove(core, LflLightningMapLayerIds.halo)
        style.addLayerAbove(innerGlow, LflLightningMapLayerIds.core)
        style.addLayerAbove(pinpoint, LflLightningMapLayerIds.innerGlow)
    }

    private fun disable(style: Style) {
        if (currentSourceIdentity == "off" && source == null) return
        remove(style)
        source = null
        currentSourceIdentity = "off"
        currentReferenceOffsetSeconds = null
        currentCovered = null
    }

    private fun update(presentation: LflLightningPresentation) {
        val sourceChanged = presentation.source.identity != currentSourceIdentity
        if (sourceChanged) {
            requireNotNull(source).setGeoJson(featureCollection(presentation))
            currentSourceIdentity = presentation.source.identity
            currentReferenceOffsetSeconds = null
            currentCovered = null
        }
        if (presentation.referenceOffsetSeconds == currentReferenceOffsetSeconds &&
            presentation.covered == currentCovered
        ) return
        updateTimeExpressions(requireNotNull(currentStyle), presentation)
        currentReferenceOffsetSeconds = presentation.referenceOffsetSeconds
        currentCovered = presentation.covered
    }

    private fun featureCollection(
        presentation: LflLightningPresentation,
    ): FeatureCollection {
        val features = presentation.source.flashes.map { sourceFlash ->
            Feature.fromGeometry(
                Point.fromLngLat(sourceFlash.point.longitude, sourceFlash.point.latitude),
                JsonObject(),
                sourceFlash.point.id,
            ).also { feature ->
                feature.addNumberProperty(BIRTH_OFFSET_PROPERTY, sourceFlash.birthOffsetSeconds)
            }
        }
        return FeatureCollection.fromFeatures(features)
    }

    /** Time-only updates change native paint/filter expressions; GeoJSON remains untouched. */
    private fun updateTimeExpressions(
        style: Style,
        presentation: LflLightningPresentation,
    ) {
        val age = ageExpression(presentation.referenceOffsetSeconds)
        val opacity = opacityExpression(age)
        val color = colorExpression(age)
        val filter = if (presentation.covered) {
            Expression.all(
                Expression.gte(age, 0.0),
                Expression.lt(age, LflLightningVisualStyle.lifetimeSeconds.toDouble()),
            )
        } else Expression.literal(false)
        val halo = requireNotNull(style.getLayerAs<CircleLayer>(LflLightningMapLayerIds.halo))
        val core = requireNotNull(style.getLayerAs<CircleLayer>(LflLightningMapLayerIds.core))
        val innerGlow = requireNotNull(
            style.getLayerAs<CircleLayer>(LflLightningMapLayerIds.innerGlow),
        )
        val pinpoint = requireNotNull(
            style.getLayerAs<CircleLayer>(LflLightningMapLayerIds.pinpoint),
        )
        listOf(halo, core, innerGlow, pinpoint).forEach { it.setFilter(filter) }
        halo.setProperties(
            PropertyFactory.circleColor(color),
            PropertyFactory.circleOpacity(Expression.product(
                opacity,
                Expression.literal(LflLightningVisualStyle.haloOpacityMultiplier),
            )),
        )
        core.setProperties(
            PropertyFactory.circleColor(color),
            PropertyFactory.circleOpacity(opacity),
            PropertyFactory.circleBlur(blurExpression(age)),
        )
        innerGlow.setProperties(
            PropertyFactory.circleOpacity(Expression.product(
                opacity,
                Expression.literal(LflLightningVisualStyle.innerGlowOpacityMultiplier),
            )),
        )
        pinpoint.setProperties(PropertyFactory.circleOpacity(opacity))
    }

    private fun ageExpression(referenceOffsetSeconds: Double): Expression =
        Expression.subtract(
            Expression.literal(referenceOffsetSeconds),
            Expression.get(BIRTH_OFFSET_PROPERTY),
        )

    private fun opacityExpression(age: Expression): Expression = Expression.interpolate(
        Expression.linear(),
        age,
        Expression.stop(0.0, 1.0),
        Expression.stop(LflLightningVisualStyle.lifetimeSeconds.toDouble(), 0.0),
    )

    private fun colorExpression(age: Expression): Expression {
        val stops = (0..LflLightningVisualStyle.visibleCohorts).map { index ->
            Expression.stop(
                index * LflLightningVisualStyle.visualIntervalSeconds.toDouble(),
                Expression.color(LflLightningVisualStyle.colorStopArgb(index)),
            )
        }.toTypedArray()
        return Expression.interpolate(Expression.linear(), age, *stops)
    }

    private fun blurExpression(age: Expression): Expression {
        val stops = (0..LflLightningVisualStyle.visibleCohorts).map { index ->
            Expression.stop(
                index * LflLightningVisualStyle.visualIntervalSeconds.toDouble(),
                LflLightningVisualStyle.coreBlurStop(index),
            )
        }.toTypedArray()
        return Expression.interpolate(Expression.linear(), age, *stops)
    }

    private fun remove(style: Style) {
        style.removeLayer(LflLightningMapLayerIds.pinpoint)
        style.removeLayer(LflLightningMapLayerIds.innerGlow)
        style.removeLayer(LflLightningMapLayerIds.core)
        style.removeLayer(LflLightningMapLayerIds.halo)
        style.removeSource(LflLightningMapLayerIds.source)
    }

    private fun colorHex(argb: Int): String = String.format("#%06X", argb and 0x00ffffff)

    private fun zoomScaledRadius(diameterDp: Float): Expression {
        val closeRadiusPixels = diameterDp * density / 2f
        return Expression.interpolate(
            Expression.linear(),
            Expression.zoom(),
            Expression.stop(
                LflLightningVisualStyle.minimumScaleZoom(),
                closeRadiusPixels * LflLightningVisualStyle.zoomScale(
                    LflLightningVisualStyle.minimumScaleZoom(),
                ),
            ),
            Expression.stop(10f, closeRadiusPixels),
        )
    }

    private companion object {
        const val BIRTH_OFFSET_PROPERTY = "birthOffsetSeconds"
    }
}
