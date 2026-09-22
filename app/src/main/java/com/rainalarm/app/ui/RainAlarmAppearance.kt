package com.rainalarm.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.rainalarm.app.data.NowCardAppearance

data class RainAlarmPalette(
    val usesDarkMaterialScheme: Boolean,
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val border: Color,
    val danger: Color,
    val selection: Color,
    val mapLabelSurface: Color,
    val mapLabelText: Color,
) {
    fun materialScheme(): ColorScheme = if (usesDarkMaterialScheme) darkColorScheme(
        primary = accent, onPrimary = background, background = background, onBackground = text,
        surface = surface, onSurface = text, surfaceVariant = elevated, onSurfaceVariant = muted,
        outline = border, error = danger,
    ) else lightColorScheme(
        primary = accent, onPrimary = Color.White, background = background, onBackground = text,
        surface = surface, onSurface = text, surfaceVariant = elevated, onSurfaceVariant = muted,
        outline = border, error = danger,
    )
}

val DarkRainPalette = RainAlarmPalette(
    usesDarkMaterialScheme = true,
    background = Color(0xFF0D0D0D), surface = Color(0xFF1A1A1A), elevated = Color(0xFF202126),
    text = Color(0xFFE8E8EA), muted = Color(0xFFA0A0A8), accent = Color(0xFF4FC3F7),
    border = Color(0xFF303138), danger = Color(0xFFEF5350), selection = Color(0xFF17303B),
    mapLabelSurface = Color(0xCC15191D), mapLabelText = Color(0xFFEDEFF1),
)

val LightRainPalette = RainAlarmPalette(
    usesDarkMaterialScheme = false,
    background = Color(0xFFF5F8FA), surface = Color.White, elevated = Color(0xFFEAF1F5),
    text = Color(0xFF132630), muted = Color(0xFF536875), accent = Color(0xFF006D9D),
    border = Color(0xFFC7D5DC), danger = Color(0xFFB3261E), selection = Color(0xFFDDF1FA),
    mapLabelSurface = Color(0xDDF9FCFD), mapLabelText = Color(0xFF17313E),
)

/** Mid-dark card treatment matching the OpenFreeMap Fiord/Slate base colour. */
val SlateRainPalette = RainAlarmPalette(
    usesDarkMaterialScheme = true,
    background = Color(0xFF45516E), surface = Color(0xFF45516E), elevated = Color(0xFF45516E),
    text = Color.White, muted = Color(0xFFD7DCE7), accent = Color(0xFF4FC3F7),
    border = Color(0xFF9EAAC1), danger = Color(0xFFFF8A80), selection = Color(0xFF5A6888),
    mapLabelSurface = Color(0xCC35405A), mapLabelText = Color.White,
)

internal object NowCardPalettePolicy {
    fun resolve(mode: NowCardAppearance, inherited: RainAlarmPalette): RainAlarmPalette = when (mode) {
        NowCardAppearance.FOLLOW_APP -> inherited
        NowCardAppearance.LIGHT -> LightRainPalette
        NowCardAppearance.DARK -> DarkRainPalette
        NowCardAppearance.SLATE -> SlateRainPalette
    }
}

val LocalRainAlarmPalette = compositionLocalOf { DarkRainPalette }
