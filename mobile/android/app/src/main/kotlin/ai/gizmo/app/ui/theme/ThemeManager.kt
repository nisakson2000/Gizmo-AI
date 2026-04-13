package ai.gizmo.app.ui.theme

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ai.gizmo.app.R
import ai.gizmo.app.model.GizmoPreferences

object ThemeManager {
    val currentThemeKey = mutableStateOf("default")
    val currentPalette = mutableStateOf(DefaultPalette)

    val pixelFont = FontFamily(Font(R.font.pressstart2p))
    val defaultFont = FontFamily.Default

    val fontFamily: FontFamily
        get() = if (currentPalette.value.usePixelFont) pixelFont else defaultFont

    val cornerRadius get() = if (currentPalette.value.sharpCorners) 2.dp else 12.dp

    fun setTheme(key: String, context: Context? = null) {
        if (currentThemeKey.value == key) return
        val theme = ALL_THEMES.find { it.key == key } ?: return
        currentThemeKey.value = key
        currentPalette.value = theme.palette
        context?.let { GizmoPreferences(it).appTheme = key }
    }

    fun loadTheme(context: Context) {
        val key = GizmoPreferences(context).appTheme
        // Bypass the equality check since this is initial load
        val theme = ALL_THEMES.find { it.key == key } ?: return
        currentThemeKey.value = key
        currentPalette.value = theme.palette
    }
}
