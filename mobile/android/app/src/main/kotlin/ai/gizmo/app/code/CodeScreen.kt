package ai.gizmo.app.code

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ai.gizmo.app.chat.copyToClipboard
import ai.gizmo.app.model.ALL_LANGUAGES
import ai.gizmo.app.model.ExecutionResult
import ai.gizmo.app.model.PREVIEW_LANGUAGES
import ai.gizmo.app.network.GizmoApi
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.BgPrimary
import ai.gizmo.app.ui.theme.BgSecondary
import ai.gizmo.app.ui.theme.BgTertiary
import ai.gizmo.app.ui.theme.Border
import ai.gizmo.app.ui.theme.ErrorColor
import ai.gizmo.app.ui.theme.Success
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary
import ai.gizmo.app.ui.theme.TextSecondary
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CodeScreen(api: GizmoApi, serverUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("gizmo_code", android.content.Context.MODE_PRIVATE) }
    var language by remember { mutableStateOf("python") }
    var code by remember { mutableStateOf(prefs.getString("code_python", "") ?: "") }
    var result by remember { mutableStateOf<ExecutionResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var timeout by remember { mutableIntStateOf(10) }
    var stdin by remember { mutableStateOf("") }
    var showLangMenu by remember { mutableStateOf(false) }
    var showTimeoutMenu by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun saveCode() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(2000); prefs.edit().putString("code_$language", code).apply() }
    }

    // Load saved code when language changes
    LaunchedEffect(language) {
        code = prefs.getString("code_$language", "") ?: ""
        result = null
    }

    // Full-screen dialog overlay for code chat
    if (showChat) {
        Dialog(
            onDismissRequest = { showChat = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            CodeChat(serverUrl = serverUrl, code = code, language = language, onDismiss = { showChat = false })
        }
    }

    Box(modifier = modifier.fillMaxSize().imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Toolbar
            Row(
                modifier = Modifier.fillMaxWidth().background(BgSecondary).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Language selector
                Box {
                    FilterChip(selected = true, onClick = { showLangMenu = true },
                        label = { Text(language, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = BgPrimary))
                    DropdownMenu(expanded = showLangMenu, onDismissRequest = { showLangMenu = false }) {
                        ALL_LANGUAGES.forEach { lang ->
                            DropdownMenuItem(text = { Text(lang, color = if (lang == language) Accent else TextPrimary) },
                                onClick = { language = lang; showLangMenu = false })
                        }
                    }
                }
                // Timeout
                Box {
                    FilterChip(selected = false, onClick = { showTimeoutMenu = true },
                        label = { Text("${timeout}s", fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = BgTertiary, labelColor = TextPrimary))
                    DropdownMenu(expanded = showTimeoutMenu, onDismissRequest = { showTimeoutMenu = false }) {
                        listOf(5, 10, 20, 30).forEach { t ->
                            DropdownMenuItem(text = { Text("${t}s") }, onClick = { timeout = t; showTimeoutMenu = false })
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                // Copy
                IconButton(onClick = { copyToClipboard(context, code) }) {
                    Icon(Icons.Default.ContentCopy, "Copy", tint = TextSecondary)
                }
                // Run
                IconButton(onClick = {
                    if (!running) {
                        running = true; result = null
                        scope.launch {
                            result = api.runCode(code, language, timeout, stdin)
                            running = false
                        }
                    }
                }, enabled = !running) {
                    Icon(Icons.Default.PlayArrow, "Run", tint = if (running) TextDim else Accent)
                }
            }

            // Editor
            TextField(
                value = code, onValueChange = { code = it; saveCode() },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = TextPrimary),
                colors = TextFieldDefaults.colors(focusedContainerColor = BgPrimary, unfocusedContainerColor = BgPrimary,
                    cursorColor = Accent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text("Write code here...", color = TextDim, fontFamily = FontFamily.Monospace) }
            )

            // Stdin (collapsible)
            if (language !in PREVIEW_LANGUAGES) {
                OutlinedTextField(
                    value = stdin, onValueChange = { stdin = it },
                    label = { Text("stdin") }, singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Border, unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            HorizontalDivider(color = Border)

            // Output
            result?.let { res ->
                if (language in PREVIEW_LANGUAGES) {
                    // Render preview
                    val html = when (language) {
                        "html" -> code
                        "css" -> "<style>$code</style><p>CSS Preview</p>"
                        "svg" -> code
                        "markdown" -> "<pre>$code</pre>"
                        else -> code
                    }
                    AndroidView(
                        factory = { ctx -> WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            @Suppress("DEPRECATION")
                            settings.allowFileAccessFromFileURLs = false
                            @Suppress("DEPRECATION")
                            settings.allowUniversalAccessFromFileURLs = false
                            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                        }},
                        update = { it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) },
                        modifier = Modifier.fillMaxWidth().weight(0.5f)
                    )
                } else {
                    Column(modifier = Modifier.weight(0.4f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Exit: ${res.exitCode}",
                                color = if (res.exitCode == 0) Success else ErrorColor,
                                fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            if (res.timedOut) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(shape = RoundedCornerShape(4.dp), color = ErrorColor.copy(alpha = 0.2f)) {
                                    Text("TIMED OUT", color = ErrorColor, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { copyToClipboard(context, res.stdout + res.stderr) }) {
                                Icon(Icons.Default.ContentCopy, "Copy output", tint = TextDim)
                            }
                        }
                        if (res.stdout.isNotEmpty()) {
                            Text(res.stdout, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                        if (res.stderr.isNotEmpty()) {
                            Text(res.stderr, color = ErrorColor, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                }
            } ?: run {
                if (!running) {
                    Box(modifier = Modifier.weight(0.3f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Run code to see output", color = TextDim)
                    }
                } else {
                    Box(modifier = Modifier.weight(0.3f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Running...", color = Accent)
                    }
                }
            }
        }

        // Ask AI FAB
        FloatingActionButton(
            onClick = { showChat = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = Accent, contentColor = BgPrimary
        ) { Icon(Icons.AutoMirrored.Filled.Chat, "Ask Gizmo") }
    }
}
