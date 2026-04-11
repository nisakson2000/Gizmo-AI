package ai.gizmo.app.memory

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.gizmo.app.model.MemoryFile
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

private val CATEGORIES = listOf("All", "facts", "notes", "conversations")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagerScreen(api: GizmoApi, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val memories = remember { mutableStateListOf<MemoryFile>() }
    var selectedFilter by remember { mutableStateOf("All") }
    var expandedFile by remember { mutableStateOf<String?>(null) }
    var expandedContent by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun loadMemories() {
        scope.launch {
            val subdir = if (selectedFilter == "All") null else selectedFilter
            memories.clear()
            memories.addAll(api.getMemories(subdir))
        }
    }

    LaunchedEffect(selectedFilter) { loadMemories() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory Manager", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, "Add Memory", tint = Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BgPrimary
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Filter chips
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CATEGORIES.forEach { cat ->
                    val label = if (cat == "All") "All" else cat.replaceFirstChar { it.uppercase() }
                    FilterChip(
                        selected = selectedFilter == cat,
                        onClick = { selectedFilter = cat },
                        label = { Text(label, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Accent,
                            selectedLabelColor = BgPrimary,
                            containerColor = BgTertiary,
                            labelColor = TextPrimary
                        )
                    )
                }
            }

            if (memories.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("No memories", color = TextDim, fontSize = 16.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(memories, key = { "${it.subdir}/${it.filename}" }) { mem ->
                        val key = "${mem.subdir}/${mem.filename}"
                        val isExpanded = expandedFile == key
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = BgSecondary
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${mem.subdir}/${mem.filename}", color = TextPrimary, fontSize = 14.sp)
                                        Text("${mem.size} bytes", color = TextDim, fontSize = 12.sp)
                                    }
                                    IconButton(onClick = {
                                        if (isExpanded) {
                                            expandedFile = null
                                        } else {
                                            expandedFile = key
                                            scope.launch {
                                                val content = api.readMemory(mem.filename, mem.subdir)
                                                expandedContent = content?.content ?: "(failed to load)"
                                            }
                                        }
                                    }) {
                                        Icon(
                                            if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            "Toggle", tint = TextSecondary
                                        )
                                    }
                                    IconButton(onClick = {
                                        val deleted = mem.copy()
                                        memories.removeAll { it.filename == mem.filename && it.subdir == mem.subdir }
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                "Memory deleted", "Undo", duration = SnackbarDuration.Short
                                            )
                                            when (result) {
                                                SnackbarResult.ActionPerformed -> { memories.add(deleted); loadMemories() }
                                                SnackbarResult.Dismissed -> api.deleteMemory(deleted.subdir, deleted.filename)
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Default.Delete, "Delete", tint = ErrorColor.copy(alpha = 0.7f))
                                    }
                                }
                                if (isExpanded) {
                                    HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 8.dp))
                                    Text(
                                        expandedContent, color = TextSecondary,
                                        fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // Clear all button
                TextButton(
                    onClick = { showClearConfirm = true },
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(Icons.Default.Delete, null, tint = ErrorColor)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear All Memories", color = ErrorColor)
                }
            }
        }
    }

    // Add memory dialog
    if (showAddDialog) {
        var addFilename by remember { mutableStateOf("") }
        var addCategory by remember { mutableStateOf("facts") }
        var addContent by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Memory") },
            text = {
                Column {
                    OutlinedTextField(
                        value = addFilename, onValueChange = { addFilename = it },
                        label = { Text("Filename") }, placeholder = { Text("e.g. favorite_color") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("facts", "notes", "conversations").forEach { cat ->
                            FilterChip(
                                selected = addCategory == cat,
                                onClick = { addCategory = cat },
                                label = { Text(cat.replaceFirstChar { it.uppercase() }, fontSize = 13.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Accent, selectedLabelColor = BgPrimary,
                                    containerColor = BgTertiary, labelColor = TextPrimary
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addContent, onValueChange = { addContent = it },
                        label = { Text("Content") },
                        modifier = Modifier.height(120.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAddDialog = false
                        scope.launch {
                            if (api.writeMemory(addFilename, addContent, addCategory)) loadMemories()
                        }
                    },
                    enabled = addFilename.isNotBlank() && addContent.isNotBlank()
                ) { Text("Save", color = if (addFilename.isNotBlank() && addContent.isNotBlank()) Accent else TextDim) }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel", color = TextSecondary) } },
            containerColor = BgSecondary
        )
    }

    // Clear all confirmation
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear All Memories") },
            text = { Text("Delete all memories? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch {
                        val count = api.clearMemories()
                        loadMemories()
                        snackbarHostState.showSnackbar("Deleted $count memories")
                    }
                }) { Text("Delete All", color = ErrorColor) }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel", color = TextSecondary) } },
            containerColor = BgSecondary
        )
    }
}
