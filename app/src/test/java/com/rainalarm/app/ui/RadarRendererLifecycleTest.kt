package com.rainalarm.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarRendererLifecycleTest {
    @Test
    fun `bind before surface initializes then schedules first draw`() {
        val state = RadarRendererLifecycle()
        assertEquals(RadarRendererAction.NONE, state.bind())
        assertEquals(RadarRendererAction.INITIALIZE, state.surfaceAvailable())
        assertEquals(RadarRendererAction.DRAW, state.initialized())
        state.drawSucceeded()
        assertEquals(RadarRendererStatus.Ready, state.status)
    }

    @Test
    fun `surface before bind initializes in the opposite callback order`() {
        val state = RadarRendererLifecycle()
        assertEquals(RadarRendererAction.NONE, state.surfaceAvailable())
        assertEquals(RadarRendererAction.INITIALIZE, state.bind())
    }

    @Test
    fun `surface recreation returns to loading and initializes again`() {
        val state = RadarRendererLifecycle()
        state.bind()
        state.surfaceAvailable()
        state.initialized()
        state.drawSucceeded()
        state.surfaceDestroyed()
        assertEquals(RadarRendererStatus.Loading, state.status)
        assertEquals(RadarRendererAction.INITIALIZE, state.surfaceAvailable())
    }

    @Test
    fun `failure is visible and disposal rejects later callbacks`() {
        val state = RadarRendererLifecycle()
        state.bind()
        state.surfaceAvailable()
        state.failed("shader failed")
        assertEquals(RadarRendererStatus.Error("shader failed"), state.status)
        state.dispose()
        assertEquals(RadarRendererAction.NONE, state.surfaceAvailable())
        assertEquals(RadarRendererAction.NONE, state.bind())
    }

    @Test
    fun `production shader interface matches exactly and contains both rendering paths`() {
        RadarShaderPrecision.entries.forEach { precision ->
            val vertex = RadarShaderSources.vertexFor(precision)
            val fragment = RadarShaderSources.fragmentFor(precision)
            assertTrue(RadarShaderContract.validateGles2(vertex, fragment).isSuccess)
            assertTrue("precision ${precision.qualifier} float;" in fragment)
            assertTrue("varying ${precision.qualifier} vec2 uv;" in vertex)
            assertTrue("varying ${precision.qualifier} vec2 uv;" in fragment)
            assertTrue("attribute ${precision.qualifier} vec2 textureCoordinate;" in vertex)
            assertTrue("uniform ${precision.qualifier} vec2 textureOffset;" in fragment)
            assertTrue("uniform ${precision.qualifier} vec2 textureScale;" in fragment)
            assertTrue("return vec3(sample.r, sample.g * coloredSource" in fragment)
        }
        assertEquals(RadarShaderPrecision.HIGH, RadarShaderPrecisionPolicy.select(23))
        assertEquals(RadarShaderPrecision.MEDIUM, RadarShaderPrecisionPolicy.select(0))
        assertTrue("sample.r" in RadarShaderSources.fragment)
        assertTrue("sample.g * coloredSource" in RadarShaderSources.fragment)
        assertTrue("mix(1.0, sample.b, coloredSource)" in RadarShaderSources.fragment)
        assertTrue("texColorLut" in RadarShaderSources.fragment)
        assertTrue("contentTexelSize" !in RadarShaderSources.fragment)
        assertTrue("reconstructionRadius" !in RadarShaderSources.fragment)
        assertTrue("cubicWeights" !in RadarShaderSources.fragment)
        assertTrue("texture2D(texRadarFrom, textureCoordinate(p))" in RadarShaderSources.fragment)
        assertTrue("texture2D(texRadarTo, textureCoordinate(p))" in RadarShaderSources.fragment)
        assertTrue("vec2 paletteUv(float value, float snow)" in RadarShaderSources.fragment)
        assertTrue("vec3 sample = mix(fromSample, toSample, time)" in RadarShaderSources.fragment)
        assertTrue("sample.a" !in RadarShaderSources.fragment)
        assertTrue("cleanIntensity" !in RadarShaderSources.fragment)
        assertTrue("uniform mediump float markerMode;" in RadarShaderSources.vertex)
        assertTrue("uniform mediump float markerMode;" in RadarShaderSources.fragment)
        assertTrue("varying highp vec2 uv;" in RadarShaderSources.vertex)
        assertTrue("varying highp vec2 uv;" in RadarShaderSources.fragment)
    }

    @Test
    fun `shader contract rejects exact device reported precision mismatch`() {
        val vertex = "uniform highp float markerMode; varying highp vec2 uv; void main(){}"
        val fragment = "uniform mediump float markerMode; varying mediump vec2 uv; " +
            "uniform lowp sampler2D texRadarFrom; uniform lowp sampler2D texRadarTo; " +
            "uniform lowp sampler2D texVelocity; uniform lowp sampler2D texColorLut; " +
            "uniform mediump float coloredSource; uniform mediump float futureFactor; " +
            "void main(){gl_FragColor=vec4(1.0);}"
        assertTrue(RadarShaderContract.validateGles2(vertex, fragment).isFailure)
    }

    @Test
    fun `shader contract rejects missing fragment uniforms and sampler parameters`() {
        val vertex = "uniform mediump float markerMode; varying mediump vec2 uv; void main(){}"
        val missing = "uniform sampler2D texRadarFrom; uniform sampler2D texRadarTo; " +
            "uniform sampler2D texVelocity; void main(){ gl_FragColor=vec4(1.0); }"
        assertTrue(RadarShaderContract.validateGles2(vertex, missing).isFailure)

        val samplerArgument = "uniform sampler2D texRadarFrom; uniform sampler2D texRadarTo; " +
            "uniform sampler2D texVelocity; uniform float coloredSource; uniform float futureFactor; " +
            "float sample(sampler2D image, vec2 p){return texture2D(image,p).r;} " +
            "void main(){gl_FragColor=vec4(1.0);}"
        assertTrue(RadarShaderContract.validateGles2(vertex, samplerArgument).isFailure)
    }
}
