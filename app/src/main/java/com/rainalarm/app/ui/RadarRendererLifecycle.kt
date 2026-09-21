package com.rainalarm.app.ui

import com.rainalarm.app.domain.RainAlarmPalette

/** User-visible state of the radar surface. Errors are deliberately propagated to Compose. */
sealed interface RadarRendererStatus {
    data object Loading : RadarRendererStatus
    data object Ready : RadarRendererStatus
    data class Compatibility(val message: String) : RadarRendererStatus
    data class Error(val message: String) : RadarRendererStatus
}

internal enum class RadarRendererAction { NONE, INITIALIZE, DRAW }

/**
 * Pure lifecycle gate used by the TextureView renderer. Android can deliver session and surface
 * callbacks in either order, and an EGL context can be recreated after a temporary surface loss.
 */
internal class RadarRendererLifecycle {
    private var bound = false
    private var surfaceAvailable = false
    private var initialized = false
    private var disposed = false

    var status: RadarRendererStatus = RadarRendererStatus.Loading
        private set

    fun bind(): RadarRendererAction {
        if (disposed) return RadarRendererAction.NONE
        bound = true
        status = RadarRendererStatus.Loading
        return nextAction()
    }

    fun surfaceAvailable(): RadarRendererAction {
        if (disposed) return RadarRendererAction.NONE
        surfaceAvailable = true
        status = RadarRendererStatus.Loading
        return nextAction()
    }

    fun initialized(): RadarRendererAction {
        if (disposed || !bound || !surfaceAvailable) return RadarRendererAction.NONE
        initialized = true
        return RadarRendererAction.DRAW
    }

    fun drawSucceeded() {
        if (!disposed && initialized) status = RadarRendererStatus.Ready
    }

    fun failed(message: String) {
        if (!disposed) {
            initialized = false
            status = RadarRendererStatus.Error(message)
        }
    }

    fun surfaceDestroyed() {
        surfaceAvailable = false
        initialized = false
        if (!disposed) status = RadarRendererStatus.Loading
    }

    fun dispose() {
        disposed = true
        surfaceAvailable = false
        initialized = false
    }

    private fun nextAction(): RadarRendererAction = when {
        disposed || initialized -> RadarRendererAction.NONE
        bound && surfaceAvailable -> RadarRendererAction.INITIALIZE
        else -> RadarRendererAction.NONE
    }
}

/** One source for every declaration that is active in both GLES 2 shader stages. */
internal enum class RadarShaderPrecision(val qualifier: String) {
    HIGH("highp"), MEDIUM("mediump"),
}

internal object RadarShaderPrecisionPolicy {
    fun select(fragmentHighPrecisionBits: Int): RadarShaderPrecision =
        if (fragmentHighPrecisionBits > 0) RadarShaderPrecision.HIGH else RadarShaderPrecision.MEDIUM
}

internal object RadarShaderSources {
    private const val SHARED_MARKER_MODE = "uniform mediump float markerMode;"
    val vertex: String get() = vertexFor(RadarShaderPrecision.HIGH)
    val fragment: String get() = fragmentFor(RadarShaderPrecision.HIGH)

    fun vertexFor(precision: RadarShaderPrecision) = """
        attribute highp vec2 position;
        attribute ${precision.qualifier} vec2 textureCoordinate;
        varying ${precision.qualifier} vec2 uv;
        $SHARED_MARKER_MODE
        void main() {
            uv = textureCoordinate;
            gl_Position = vec4(position, 0.0, 1.0);
            gl_PointSize = markerMode > 0.5 ? 16.0 : 1.0;
        }
    """.trimIndent()

    fun fragmentFor(precision: RadarShaderPrecision) = """
        precision ${precision.qualifier} float;
        uniform ${precision.qualifier} float time;
        uniform ${precision.qualifier} float velocityScale;
        uniform ${precision.qualifier} float layerAlpha;
        uniform ${precision.qualifier} vec2 pixelSize;
        uniform ${precision.qualifier} vec2 textureOffset;
        uniform ${precision.qualifier} vec2 textureScale;
        uniform lowp sampler2D texRadarFrom;
        uniform lowp sampler2D texRadarTo;
        uniform lowp sampler2D texVelocity;
        uniform lowp sampler2D texColorLut;
        $SHARED_MARKER_MODE
        uniform ${precision.qualifier} float coloredSource;
        uniform ${precision.qualifier} float futureFactor;
        varying ${precision.qualifier} vec2 uv;
        float inside(vec2 p) {
            return step(0.0, p.x) * step(p.x, 1.0) * step(0.0, p.y) * step(p.y, 1.0);
        }
        vec2 textureCoordinate(vec2 p) {
            return textureOffset + clamp(p, 0.0, 1.0) * textureScale;
        }
        vec3 radarFrom(vec2 p) {
            vec4 sample = texture2D(texRadarFrom, textureCoordinate(p));
            return vec3(sample.r, sample.g * coloredSource, mix(1.0, sample.b, coloredSource)) * inside(p);
        }
        vec3 radarTo(vec2 p) {
            vec4 sample = texture2D(texRadarTo, textureCoordinate(p));
            return vec3(sample.r, sample.g * coloredSource, mix(1.0, sample.b, coloredSource)) * inside(p);
        }
        vec2 paletteUv(float value, float snow) {
            return vec2((clamp(value, 0.0, 1.0) * 255.0 + 0.5) / 256.0,
                (clamp(snow, 0.0, 1.0) + 0.5) / 2.0);
        }
        void main() {
            if (markerMode > 0.5) {
                vec2 delta = gl_PointCoord - vec2(0.5);
                if (dot(delta, delta) > 0.25) discard;
                gl_FragColor = vec4(0.31, 0.76, 0.97, 1.0);
                return;
            }
            vec2 encoded = texture2D(texVelocity, textureCoordinate(uv)).ra;
            vec2 velocity = (encoded * 2.0 - 1.0) * velocityScale * pixelSize;
            if (futureFactor > 0.0) {
                vec2 p = uv - velocity * futureFactor;
                vec3 projected = radarFrom(p);
                vec4 projectedColor = texture2D(texColorLut, paletteUv(projected.x, projected.y));
                gl_FragColor = vec4(projectedColor.rgb, projectedColor.a * projected.z * layerAlpha);
                return;
            }
            vec3 fromSample = radarFrom(uv - velocity * time);
            vec3 toSample = radarTo(uv + velocity * (1.0 - time));
            vec3 sample = mix(fromSample, toSample, time);
            vec4 color = texture2D(texColorLut, paletteUv(sample.x, sample.y));
            gl_FragColor = vec4(color.rgb, color.a * sample.z * layerAlpha);
        }
    """.trimIndent()
}

/** Shader interface constraints checked before the device driver sees either stage. */
internal object RadarShaderContract {
    private data class Declaration(val storage: String, val precision: String?, val type: String, val name: String)
    private val declaration = Regex(
        "(?m)^\\s*(attribute|uniform|varying)\\s+(?:(lowp|mediump|highp)\\s+)?(\\w+)\\s+(\\w+)\\s*;",
    )

    fun validateGles2(vertex: String, fragment: String): Result<Unit> = runCatching {
        require("#version 300" !in vertex && "#version 300" !in fragment) {
            "GLES 3 shader supplied to GLES 2 context"
        }
        require(!Regex("\\w+\\s*\\(\\s*sampler2D\\s+").containsMatchIn(fragment)) {
            "Sampler parameters are not portable across GLES 2 drivers"
        }
        val vertexDeclarations = declarations(vertex)
        val fragmentDeclarations = declarations(fragment)
        val vertexVaryings = vertexDeclarations.filter { it.storage == "varying" }.associateBy { it.name }
        val fragmentVaryings = fragmentDeclarations.filter { it.storage == "varying" }.associateBy { it.name }
        require(vertexVaryings.keys == fragmentVaryings.keys) { "Shader varying sets do not match" }
        vertexVaryings.forEach { (name, fromVertex) ->
            val fromFragment = requireNotNull(fragmentVaryings[name])
            require(fromVertex.type == fromFragment.type && fromVertex.precision == fromFragment.precision) {
                "Varying $name type/precision does not match"
            }
            require(fromVertex.precision != null) { "Floating varying $name needs explicit precision" }
        }
        val vertexUniforms = vertexDeclarations.filter { it.storage == "uniform" }.associateBy { it.name }
        val fragmentUniforms = fragmentDeclarations.filter { it.storage == "uniform" }.associateBy { it.name }
        vertexUniforms.keys.intersect(fragmentUniforms.keys).forEach { name ->
            val fromVertex = requireNotNull(vertexUniforms[name])
            val fromFragment = requireNotNull(fragmentUniforms[name])
            require(fromVertex.type == fromFragment.type && fromVertex.precision == fromFragment.precision) {
                "Uniform $name type/precision does not match"
            }
            require(fromVertex.precision != null) { "Shared floating uniform $name needs explicit precision" }
        }
        listOf(
            "texRadarFrom", "texRadarTo", "texVelocity", "texColorLut", "coloredSource", "futureFactor",
            "textureOffset", "textureScale",
        )
            .forEach { require(fragmentDeclarations.any { declaration -> declaration.name == it }) { "Missing $it" } }
        require("gl_FragColor" in fragment) { "Fragment output is missing" }
    }

    private fun declarations(source: String): List<Declaration> = declaration.findAll(source).map { match ->
        Declaration(
            storage = match.groupValues[1],
            precision = match.groupValues[2].ifBlank { null },
            type = match.groupValues[3],
            name = match.groupValues[4],
        )
    }.toList()
}
