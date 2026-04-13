package ai.gizmo.app.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.gizmo.app.model.TrackerNote
import ai.gizmo.app.model.TrackerTask
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

private val PRIORITY_COLORS = mapOf(
    "urgent" to Color(0xFFE06060), "high" to Color(0xFFE09040),
    "medium" to Color(0xFFD4A574), "low" to Color(0xFF666666)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(api: GizmoApi, serverUrl: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tasks = remember { mutableStateListOf<TrackerTask>() }
    val notes = remember { mutableStateListOf<TrackerNote>() }
    var statusFilter by remember { mutableStateOf<String?>(null) }
    var priorityFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var quickAddText by remember { mutableStateOf("") }
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var selectedNoteId by remember { mutableStateOf<String?>(null) }
    var showChat by remember { mutableStateOf(false) }

    fun loadTasks() { scope.launch { tasks.clear(); tasks.addAll(api.getTasks(statusFilter, priorityFilter)) } }
    fun loadNotes() { scope.launch { notes.clear(); notes.addAll(api.getNotes(search = searchQuery.takeIf { it.isNotBlank() })) } }

    LaunchedEffect(selectedTab, statusFilter, priorityFilter) {
        if (selectedTab == 0) loadTasks() else loadNotes()
    }

    // Detail screens with system back button support
    selectedTaskId?.let { id ->
        BackHandler { selectedTaskId = null; loadTasks() }
        TaskDetail(api = api, taskId = id, onDismiss = { selectedTaskId = null; loadTasks() })
        return
    }
    selectedNoteId?.let { id ->
        BackHandler { selectedNoteId = null; loadNotes() }
        NoteEditor(api = api, noteId = id, onDismiss = { selectedNoteId = null; loadNotes() })
        return
    }
    if (showChat) {
        BackHandler { showChat = false }
        TrackerChat(serverUrl = serverUrl, onRefresh = { loadTasks(); loadNotes() }, onDismiss = { showChat = false })
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showChat = true }, containerColor = Accent, contentColor = BgPrimary) {
                Icon(Icons.AutoMirrored.Filled.Chat, "Ask Gizmo")
            }
        },
        containerColor = BgPrimary,
        modifier = modifier
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = BgSecondary,
                contentColor = Accent,
                indicator = {}
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    text = { Text("Tasks", color = if (selectedTab == 0) Accent else TextDim) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    text = { Text("Notes", color = if (selectedTab == 1) Accent else TextDim) })
            }

            when (selectedTab) {
                0 -> {
                    // Filters
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val statuses = listOf(null to "All", "todo" to "Todo", "in_progress" to "In Progress", "done" to "Done", "blocked" to "Blocked")
                        items(statuses) { (value, label) ->
                            FilterChip(
                                selected = statusFilter == value, onClick = { statusFilter = value },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = BgPrimary, containerColor = BgTertiary, labelColor = TextPrimary)
                            )
                        }
                    }

                    // Task list
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        val filtered = if (searchQuery.isBlank()) tasks else tasks.filter {
                            it.title.contains(searchQuery, true) || it.description.contains(searchQuery, true)
                        }
                        items(filtered, key = { it.id }) { task ->
                            TaskItem(task = task, onClick = { selectedTaskId = task.id }, onDelete = {
                                tasks.removeAll { it.id == task.id }
                                scope.launch {
                                    api.deleteTask(task.id)
                                    snackbar.showSnackbar("Task deleted", duration = SnackbarDuration.Short)
                                }
                            })
                        }
                    }

                    // Quick add
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = quickAddText, onValueChange = { quickAddText = it },
                            placeholder = { Text("Add a task...", color = TextDim) },
                            colors = TextFieldDefaults.colors(focusedContainerColor = BgSecondary, unfocusedContainerColor = BgSecondary, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f), singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = {
                            if (quickAddText.isNotBlank()) {
                                val text = quickAddText; quickAddText = ""
                                scope.launch { api.createTask(text); loadTasks() }
                            }
                        }) { Icon(Icons.AutoMirrored.Filled.Send, "Add", tint = Accent) }
                    }
                }
                1 -> {
                    // Search + Add
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery, onValueChange = { searchQuery = it; loadNotes() },
                            placeholder = { Text("Search notes...", color = TextDim) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = TextDim) },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Border, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent),
                            shape = RoundedCornerShape(12.dp), singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            scope.launch {
                                val note = api.createNote("New Note")
                                if (note != null) { loadNotes(); selectedNoteId = note.id }
                            }
                        }) { Icon(Icons.Default.Add, "Add Note", tint = Accent) }
                    }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        val sorted = notes.sortedByDescending { it.pinned }
                        items(sorted, key = { it.id }) { note ->
                            NoteItem(note = note, onClick = { selectedNoteId = note.id }, onDelete = {
                                notes.removeAll { it.id == note.id }
                                scope.launch {
                                    api.deleteNote(note.id)
                                    snackbar.showSnackbar("Note deleted", duration = SnackbarDuration.Short)
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskItem(task: TrackerTask, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp), color = BgSecondary
    ) {
        Row(modifier = Modifier.clickable { onClick() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(4.dp, 36.dp).background(PRIORITY_COLORS[task.priority] ?: TextDim, RoundedCornerShape(2.dp)))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (task.dueDate != null) Text(task.dueDate, color = TextDim, fontSize = 11.sp)
                    task.tags.take(2).forEach { tag ->
                        Surface(shape = RoundedCornerShape(4.dp), color = BgTertiary) {
                            Text(tag, color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                    if (task.subtasks.isNotEmpty()) Text("+${task.subtasks.size}", color = TextDim, fontSize = 11.sp)
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = ErrorColor.copy(alpha = 0.5f), modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun NoteItem(note: TrackerNote, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp), color = BgSecondary
    ) {
        Row(modifier = Modifier.clickable { onClick() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (note.pinned) { Icon(Icons.Default.PushPin, null, tint = Accent, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(8.dp)) }
            Column(modifier = Modifier.weight(1f)) {
                Text(note.title, color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (note.content.isNotEmpty()) Text(note.content, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = ErrorColor.copy(alpha = 0.5f), modifier = Modifier.size(18.dp)) }
        }
    }
}
