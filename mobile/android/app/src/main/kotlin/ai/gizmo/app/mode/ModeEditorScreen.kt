package ai.gizmo.app.mode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.gizmo.app.model.Mode
import ai.gizmo.app.model.ModeDetail
import ai.gizmo.app.network.GizmoApi
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.BgPrimary
import ai.gizmo.app.ui.theme.BgSecondary
import ai.gizmo.app.ui.theme.BgTertiary
import ai.gizmo.app.ui.theme.Border
import ai.gizmo.app.ui.theme.ErrorColor
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary
import ai.gizmo.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private val BUILTIN_MODES = setOf("chat", "brainstorm", "coder", "research", "planner", "roleplay")
private val MODE_NAME_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,48}[a-z0-9]$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeEditorScreen(
    api: GizmoApi,
    onModesChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val modes = remember { mutableStateListOf<Mode>() }
    var selectedMode by remember { mutableStateOf<ModeDetail?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Editor fields
    var editName by remember { mutableStateOf("") }
    var editLabel by remember { mutableStateOf("") }
    var editDescription by remember { mutableStateOf("") }
    var editPrompt by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }

    fun loadModes() {
        scope.launch {
            modes.clear()
            modes.addAll(api.getModes())
        }
    }

    fun selectMode(name: String) {
        scope.launch {
            val detail = api.getModeDetail(name)
            if (detail != null) {
                selectedMode = detail
                isCreating = false
                editLabel = detail.label
                editDescription = detail.description
                editPrompt = detail.systemPrompt
            }
        }
    }

    LaunchedEffect(Unit) { loadModes() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mode Editor", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary)
            )
        },
        containerColor = BgPrimary
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Mode list (top section)
            LazyColumn(modifier = Modifier.weight(0.35f).fillMaxWidth()) {
                items(modes, key = { it.name }) { mode ->
                    val isSelected = selectedMode?.name == mode.name && !isCreating
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) BgTertiary else BgSecondary,
                        border = if (isSelected) BorderStroke(1.dp, Accent) else null,
                        onClick = { selectMode(mode.name) }
                    ) {
                        Row(modifier = Modifier.padding(12.dp)) {
                            Text(mode.label, color = TextPrimary, modifier = Modifier.weight(1f))
                            if (mode.name !in BUILTIN_MODES) {
                                Text("custom", color = TextDim, fontSize = 12.sp)
                            }
                        }
                    }
                }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCreating) BgTertiary else BgSecondary,
                        border = if (isCreating) BorderStroke(1.dp, Accent) else null,
                        onClick = {
                            isCreating = true
                            selectedMode = null
                            editName = ""; editLabel = ""; editDescription = ""; editPrompt = ""
                            nameError = null
                        }
                    ) {
                        Row(modifier = Modifier.padding(12.dp)) {
                            Icon(Icons.Default.Add, null, tint = Accent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("New Mode", color = Accent)
                        }
                    }
                }
            }

            HorizontalDivider(color = Border)

            // Editor (bottom section)
            Column(
                modifier = Modifier
                    .weight(0.65f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                if (isCreating) {
                    // Create mode
                    Text("Create Mode", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editName, onValueChange = {
                            editName = it.lowercase().replace(" ", "-")
                            nameError = if (it.isNotEmpty() && !MODE_NAME_REGEX.matches(editName))
                                "Lowercase, numbers, hyphens only (2-50 chars)" else null
                        },
                        label = { Text("Name") },
                        isError = nameError != null,
                        supportingText = nameError?.let { { Text(it, color = ErrorColor) } },
                        singleLine = true,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editLabel, onValueChange = { editLabel = it },
                        label = { Text("Label") }, singleLine = true,
                        colors = fieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editDescription, onValueChange = { editDescription = it },
                        label = { Text("Description") }, singleLine = true,
                        colors = fieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editPrompt, onValueChange = { editPrompt = it },
                        label = { Text("System Prompt") },
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        maxLines = 20
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { isCreating = false }) {
                            Text("Cancel", color = TextSecondary)
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    if (api.createMode(editName, editLabel, editDescription, editPrompt)) {
                                        loadModes(); onModesChanged()
                                        selectMode(editName)
                                    }
                                }
                            },
                            enabled = editName.length >= 2 && editLabel.isNotBlank() && nameError == null
                        ) {
                            Text("Create", color = if (editName.length >= 2 && editLabel.isNotBlank() && nameError == null) Accent else TextDim)
                        }
                    }
                } else if (selectedMode != null) {
                    val mode = selectedMode!!
                    Text("Edit: ${mode.label}", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editLabel, onValueChange = { editLabel = it },
                        label = { Text("Label") }, singleLine = true,
                        colors = fieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editDescription, onValueChange = { editDescription = it },
                        label = { Text("Description") }, singleLine = true,
                        colors = fieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editPrompt, onValueChange = { editPrompt = it },
                        label = { Text("System Prompt") },
                        placeholder = if (mode.name == "chat") {{ Text("Empty = default behavior", color = TextDim) }} else null,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        maxLines = 20
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val hasChanges = editLabel != mode.label || editDescription != mode.description || editPrompt != mode.systemPrompt
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            editLabel = mode.label; editDescription = mode.description; editPrompt = mode.systemPrompt
                        }) { Text("Reset", color = TextSecondary) }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val l = if (editLabel != mode.label) editLabel else null
                                    val d = if (editDescription != mode.description) editDescription else null
                                    val p = if (editPrompt != mode.systemPrompt) editPrompt else null
                                    if (api.updateMode(mode.name, l, d, p)) {
                                        loadModes(); onModesChanged()
                                        selectMode(mode.name)
                                    }
                                }
                            },
                            enabled = hasChanges
                        ) { Text("Save", color = if (hasChanges) Accent else TextDim) }
                        if (mode.name !in BUILTIN_MODES) {
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = { showDeleteConfirm = true }) {
                                Icon(Icons.Default.Delete, null, tint = ErrorColor)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete", color = ErrorColor)
                            }
                        }
                    }
                } else {
                    Text("Select a mode or create a new one", color = TextDim, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }

    if (showDeleteConfirm && selectedMode != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Mode") },
            text = { Text("Delete \"${selectedMode!!.label}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        if (api.deleteMode(selectedMode!!.name)) {
                            selectedMode = null
                            loadModes(); onModesChanged()
                        }
                    }
                }) { Text("Delete", color = ErrorColor) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = TextSecondary) } },
            containerColor = BgSecondary
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent, unfocusedBorderColor = Border,
    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
    cursorColor = Accent, focusedLabelColor = Accent, unfocusedLabelColor = TextSecondary
)
