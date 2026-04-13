package ai.gizmo.app.model

import android.content.Context
import android.content.SharedPreferences

class GizmoPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gizmo_settings", Context.MODE_PRIVATE)

    // TTS settings
    var ttsEnabled: Boolean
        get() = prefs.getBoolean("tts_enabled", false)
        set(value) = prefs.edit().putBoolean("tts_enabled", value).apply()

    var ttsVoiceId: String?
        get() = prefs.getString("tts_voice_id", null)
        set(value) = prefs.edit().putString("tts_voice_id", value).apply()

    var ttsSpeed: Float
        get() = prefs.getFloat("tts_speed", 1.0f)
        set(value) = prefs.edit().putFloat("tts_speed", value).apply()

    var ttsLanguage: String
        get() = prefs.getString("tts_language", "Auto") ?: "Auto"
        set(value) = prefs.edit().putString("tts_language", value).apply()

    // Mode
    var selectedMode: String
        get() = prefs.getString("selected_mode", "chat") ?: "chat"
        set(value) = prefs.edit().putString("selected_mode", value).apply()

    // Theme
    var appTheme: String
        get() = prefs.getString("app_theme", "default") ?: "default"
        set(value) = prefs.edit().putString("app_theme", value).apply()

    fun loadAll(): SettingsSnapshot = SettingsSnapshot(
        ttsEnabled = ttsEnabled, ttsVoiceId = ttsVoiceId, ttsSpeed = ttsSpeed,
        ttsLanguage = ttsLanguage, selectedMode = selectedMode, appTheme = appTheme
    )
}

data class SettingsSnapshot(
    val ttsEnabled: Boolean, val ttsVoiceId: String?, val ttsSpeed: Float,
    val ttsLanguage: String, val selectedMode: String, val appTheme: String
)
