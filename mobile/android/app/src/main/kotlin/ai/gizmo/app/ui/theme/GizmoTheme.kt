package ai.gizmo.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

val LocalGizmoPalette = compositionLocalOf { DefaultPalette }

// Compose-safe color accessors — read from CompositionLocal, tracked for recomposition
val BgPrimary: Color @Composable get() = LocalGizmoPalette.current.bgPrimary
val BgSecondary: Color @Composable get() = LocalGizmoPalette.current.bgSecondary
val BgTertiary: Color @Composable get() = LocalGizmoPalette.current.bgTertiary
val BgHover: Color @Composable get() = LocalGizmoPalette.current.bgHover
val Border: Color @Composable get() = LocalGizmoPalette.current.border
val Accent: Color @Composable get() = LocalGizmoPalette.current.accent
val AccentDim: Color @Composable get() = LocalGizmoPalette.current.accentDim
val TextPrimary: Color @Composable get() = LocalGizmoPalette.current.textPrimary
val TextSecondary: Color @Composable get() = LocalGizmoPalette.current.textSecondary
val TextDim: Color @Composable get() = LocalGizmoPalette.current.textDim
val ThinkingBg: Color @Composable get() = LocalGizmoPalette.current.thinkingBg
val ThinkingBorder: Color @Composable get() = LocalGizmoPalette.current.thinkingBorder
val Success: Color @Composable get() = LocalGizmoPalette.current.success
val ErrorColor: Color @Composable get() = LocalGizmoPalette.current.error
val UserMsg: Color @Composable get() = LocalGizmoPalette.current.userMsg

@Composable
fun GizmoTheme(content: @Composable () -> Unit) {
    val palette by ThemeManager.currentPalette

    val colorScheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.accent, onPrimary = palette.bgPrimary,
            primaryContainer = palette.accentDim, secondary = palette.accent,
            surface = palette.bgPrimary, surfaceContainer = palette.bgSecondary,
            surfaceContainerHigh = palette.bgTertiary,
            onSurface = palette.textPrimary, onSurfaceVariant = palette.textSecondary,
            background = palette.bgPrimary, onBackground = palette.textPrimary,
            error = palette.error, onError = palette.textPrimary,
            outline = palette.border, outlineVariant = palette.border
        )
    } else {
        darkColorScheme(
            primary = palette.accent, onPrimary = palette.bgPrimary,
            primaryContainer = palette.accentDim, secondary = palette.accent,
            surface = palette.bgPrimary, surfaceContainer = palette.bgSecondary,
            surfaceContainerHigh = palette.bgTertiary,
            onSurface = palette.textPrimary, onSurfaceVariant = palette.textSecondary,
            background = palette.bgPrimary, onBackground = palette.textPrimary,
            error = palette.error, onError = palette.textPrimary,
            outline = palette.border, outlineVariant = palette.border
        )
    }

    CompositionLocalProvider(LocalGizmoPalette provides palette) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
