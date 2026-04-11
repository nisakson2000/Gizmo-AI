package ai.gizmo.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.gizmo.app.R
import ai.gizmo.app.model.Mode
import ai.gizmo.app.model.ServiceHealth
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.BgSecondary
import ai.gizmo.app.ui.theme.BgTertiary
import ai.gizmo.app.ui.theme.Border
import ai.gizmo.app.ui.theme.ErrorColor
import ai.gizmo.app.ui.theme.Success
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary
import ai.gizmo.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    thinkingEnabled: Boolean,
    onThinkingChanged: (Boolean) -> Unit,
    contextLength: Int,
    onContextLengthChanged: (Int) -> Unit,
    modes: List<Mode>,
    selectedMode: String,
    onModeSelected: (String) -> Unit,
    serviceHealth: List<ServiceHealth>,
    serverName: String,
    serverUrl: String,
    onRefreshHealth: () -> Unit,
    onSwitchServer: () -> Unit,
    onOpenVoiceStudio: () -> Unit,
    onOpenModeEditor: () -> Unit,
    onOpenMemoryManager: () -> Unit,
    ttsEnabled: Boolean,
    onTtsChanged: (Boolean) -> Unit,
    ttsSpeed: Float,
    onTtsSpeedChanged: (Float) -> Unit,
    ttsLanguage: String,
    onTtsLanguageChanged: (String) -> Unit,
    voices: List<ai.gizmo.app.model.Voice>,
    selectedVoiceId: String?,
    onVoiceSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { onRefreshHealth() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BgSecondary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.settings_title),
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Thinking mode
            SectionHeader(stringResource(R.string.thinking_mode))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.thinking_mode),
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = thinkingEnabled,
                    onCheckedChange = onThinkingChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Accent,
                        checkedTrackColor = Accent.copy(alpha = 0.3f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Border)
            Spacer(modifier = Modifier.height(12.dp))

            // Context length
            SectionHeader(stringResource(R.string.context_length))
            Text(
                text = "%,d tokens".format(contextLength),
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Slider(
                value = contextLength.toFloat(),
                onValueChange = { onContextLengthChanged(it.toInt()) },
                valueRange = 2048f..32768f,
                steps = 14,
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Border
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Border)
            Spacer(modifier = Modifier.height(12.dp))

            // Mode selector
            SectionHeader(stringResource(R.string.behavioral_mode))
            Spacer(modifier = Modifier.height(8.dp))
            modes.forEach { mode ->
                val isSelected = mode.name == selectedMode
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onModeSelected(mode.name) }
                        .padding(vertical = 10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.label,
                            color = if (isSelected) Accent else TextPrimary,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                        )
                        if (mode.description.isNotEmpty()) {
                            Text(
                                text = mode.description,
                                color = TextDim,
                                fontSize = 12.sp
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Border)
            Spacer(modifier = Modifier.height(12.dp))

            // Theme selector
            SectionHeader("Theme")
            Spacer(modifier = Modifier.height(8.dp))
            val themes = ai.gizmo.app.ui.theme.ALL_THEMES
            val currentTheme = ai.gizmo.app.ui.theme.ThemeManager.currentThemeKey.value
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                themes.take(5).forEach { t ->
                    ThemeButton(t.key, t.label, t.palette.accent, currentTheme == t.key) {
                        ai.gizmo.app.ui.theme.ThemeManager.setTheme(t.key, null)
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                themes.drop(5).forEach { t ->
                    ThemeButton(t.key, t.label, t.palette.accent, currentTheme == t.key) {
                        ai.gizmo.app.ui.theme.ThemeManager.setTheme(t.key, null)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Border)
            Spacer(modifier = Modifier.height(12.dp))

            // Mode & Memory tools
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenModeEditor) { Text("Mode Editor", color = Accent) }
                TextButton(onClick = onOpenMemoryManager) { Text("Memory Manager", color = Accent) }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Border)
            Spacer(modifier = Modifier.height(12.dp))

            // TTS settings
            SectionHeader("Text-to-Speech")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text("Read responses aloud", color = TextPrimary, modifier = Modifier.weight(1f))
                Switch(
                    checked = ttsEnabled,
                    onCheckedChange = onTtsChanged,
                    colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.3f))
                )
            }
            if (ttsEnabled) {
                // Voice selector
                var showVoiceMenu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { showVoiceMenu = true }.padding(vertical = 8.dp)
                    ) {
                        Text("Voice", color = TextSecondary, modifier = Modifier.weight(1f))
                        Text(
                            voices.find { it.id == selectedVoiceId }?.name ?: "Default",
                            color = Accent
                        )
                    }
                    DropdownMenu(expanded = showVoiceMenu, onDismissRequest = { showVoiceMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Default", color = if (selectedVoiceId == null) Accent else TextPrimary) },
                            onClick = { onVoiceSelected(null); showVoiceMenu = false }
                        )
                        voices.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v.name, color = if (v.id == selectedVoiceId) Accent else TextPrimary) },
                                onClick = { onVoiceSelected(v.id); showVoiceMenu = false }
                            )
                        }
                    }
                }
                // Speed slider
                Text("Speed: ${"%.1f".format(ttsSpeed)}x", color = TextSecondary, fontSize = 13.sp)
                Slider(
                    value = ttsSpeed,
                    onValueChange = onTtsSpeedChanged,
                    valueRange = 0.5f..2.0f,
                    steps = 14,
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Border),
                    modifier = Modifier.fillMaxWidth()
                )
                // Language
                val languages = listOf("Auto", "English", "Chinese", "Japanese", "Korean", "German", "French", "Russian", "Portuguese", "Spanish", "Italian")
                var showLangMenu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { showLangMenu = true }.padding(vertical = 8.dp)
                    ) {
                        Text("Language", color = TextSecondary, modifier = Modifier.weight(1f))
                        Text(ttsLanguage, color = Accent)
                    }
                    DropdownMenu(expanded = showLangMenu, onDismissRequest = { showLangMenu = false }) {
                        languages.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang, color = if (lang == ttsLanguage) Accent else TextPrimary) },
                                onClick = { onTtsLanguageChanged(lang); showLangMenu = false }
                            )
                        }
                    }
                }
            }
            // Voice Studio button
            TextButton(onClick = onOpenVoiceStudio) {
                Text("Voice Studio", color = Accent)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Border)
            Spacer(modifier = Modifier.height(12.dp))

            // Service health
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(stringResource(R.string.service_health))
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onRefreshHealth) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextDim, modifier = Modifier.size(20.dp))
                }
            }
            serviceHealth.forEach { svc ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (svc.status == "healthy") Success else ErrorColor,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = svc.name,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = svc.status,
                        color = if (svc.status == "healthy") Success else ErrorColor,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Border)
            Spacer(modifier = Modifier.height(12.dp))

            // Server info
            SectionHeader(stringResource(R.string.server_info))
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = serverName.ifEmpty { "Server" }, color = TextPrimary)
            Text(text = serverUrl, color = TextDim, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onSwitchServer) {
                Text(stringResource(R.string.switch_server), color = Accent)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp
    )
}

@Composable
private fun ThemeButton(key: String, label: String, swatchColor: androidx.compose.ui.graphics.Color, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) swatchColor.copy(alpha = 0.2f) else BgTertiary,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, swatchColor) else null,
        modifier = Modifier.height(48.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(4.dp)) {
            Surface(shape = CircleShape, color = swatchColor, modifier = Modifier.size(16.dp)) {}
            Text(label, color = TextPrimary, fontSize = 8.sp, maxLines = 1)
        }
    }
}
