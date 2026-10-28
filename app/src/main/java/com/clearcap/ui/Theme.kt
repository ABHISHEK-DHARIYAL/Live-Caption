package com.clearcap.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens taken from VoiceFlow's index.css (zinc neutrals, green accent, 8/12 px radii) and
 * OpenWhispr's compact 6-10 px radii. Dark mode is the primary look, like both desktop apps.
 */
@Immutable
class Tokens(
    val surface: Color, val elevated: Color, val border: Color, val muted: Color,
    val accent: Color, val accentSoft: Color, val danger: Color, val dangerSoft: Color, val warnSoft: Color,
)

val LocalTokens = staticCompositionLocalOf {
    Tokens(Color.White, Color.White, Color.Gray, Color.Gray, Color.Green, Color.Green, Color.Red, Color.Red, Color.Yellow)
}

private val Accent400 = Color(0xFF4ADE80)
private val Accent500 = Color(0xFF22C55E)
private val Accent600 = Color(0xFF16A34A)

private val LightTokens = Tokens(
    surface = Color(0xFFFFFFFF), elevated = Color(0xFFF4F4F5), border = Color(0x1A09090B), muted = Color(0xFF71717A),
    accent = Accent600, accentSoft = Color(0xFFDCFCE7), danger = Color(0xFFEF4444), dangerSoft = Color(0xFFFEE2E2),
    warnSoft = Color(0xFFFEF3C7),
)
private val DarkTokens = Tokens(
    surface = Color(0xFF111113), elevated = Color(0xFF1A1A1D), border = Color(0x0FFFFFFF), muted = Color(0xFFA1A1AA),
    accent = Accent500, accentSoft = Color(0x2622C55E), danger = Color(0xFFF87171), dangerSoft = Color(0x26EF4444),
    warnSoft = Color(0x26F59E0B),
)

private val Light = lightColorScheme(
    primary = Accent600, onPrimary = Color.White, background = Color(0xFFFAFAFA), onBackground = Color(0xFF09090B),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF09090B), surfaceVariant = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFF71717A), outline = Color(0x3309090B), error = Color(0xFFEF4444),
    secondaryContainer = Color(0xFFDCFCE7), onSecondaryContainer = Color(0xFF14532D),
)
private val Dark = darkColorScheme(
    primary = Accent500, onPrimary = Color(0xFF09090B), background = Color(0xFF09090B), onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF111113), onSurface = Color(0xFFFAFAFA), surfaceVariant = Color(0xFF1A1A1D),
    onSurfaceVariant = Color(0xFFA1A1AA), outline = Color(0x33FFFFFF), error = Color(0xFFF87171),
    secondaryContainer = Color(0x2622C55E), onSecondaryContainer = Accent400,
)

@Composable
fun ClearcapTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalTokens provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            shapes = Shapes(
                small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp),
            ),
            content = content,
        )
    }
}
