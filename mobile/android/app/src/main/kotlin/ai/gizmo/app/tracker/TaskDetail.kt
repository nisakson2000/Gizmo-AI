package ai.gizmo.app.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Saves must outlive the TaskDetail composable — otherwise dismissing the dialog within
// the debounce window cancels the in-flight save and the edit is lost.
private val taskSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetail(api: GizmoApi, taskId: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf<TrackerTask?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("todo") }
    var priority by remember { mutableStateOf("medium") }
    var recurrence by remember { mutableStateOf("none") }
    var tagText by remember { mutableStateOf("") }
    val tags = remember { mutableStateListOf<String>() }
    val subtasks = remember { mutableStateListOf<TrackerTask>() }
    var subtaskTitle by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun save(fields: Map<String, Any>) {
        saveJob?.cancel()
        saveJob = taskSaveScope.launch { delay(800); api.updateTask(taskId, fields) }
    }

    LaunchedEffect(taskId) {
        val t = api.getTask(taskId) ?: return@LaunchedEffect
        task = t; title = t.title; description = t.description; status = t.status
        priority = t.priority; recurrence = t.recurrence
        tags.clear(); tags.addAll(t.tags)
        subtasks.clear(); subtasks.addAll(t.subtasks)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task", color = TextPrimary) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary)
            )
        }, containerColor = BgPrimary
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it; save(mapOf("title" to it)) },
                label = { Text("Title") }, singleLine = true, colors = fc(), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(value = description, onValueChange = { description = it; save(mapOf("description" to it)) },
                label = { Text("Description") }, colors = fc(), modifier = Modifier.fillMaxWidth().height(100.dp), maxLines = 6)
            Spacer(modifier = Modifier.height(12.dp))

            Text("Status", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                listOf("todo", "in_progress", "done", "blocked").forEach { s ->
                    FilterChip(selected = status == s, onClick = { status = s; save(mapOf("status" to s)) },
                        label = { Text(s.replace("_", " ").replaceFirstChar { it.uppercase() }, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = BgPrimary, containerColor = BgTertiary, labelColor = TextPrimary))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Text("Priority", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                listOf("urgent" to Color(0xFFE06060), "high" to Color(0xFFE09040), "medium" to Accent, "low" to TextDim).forEach { (p, c) ->
                    FilterChip(selected = priority == p, onClick = { priority = p; save(mapOf("priority" to p)) },
                        label = { Text(p.replaceFirstChar { it.uppercase() }, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c, selectedLabelColor = BgPrimary, containerColor = BgTertiary, labelColor = TextPrimary))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Text("Recurrence", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                listOf("none", "daily", "weekly", "monthly").forEach { r ->
                    FilterChip(selected = recurrence == r, onClick = { recurrence = r; save(mapOf("recurrence" to r)) },
                        label = { Text(r.replaceFirstChar { it.uppercase() }, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = BgPrimary, containerColor = BgTertiary, labelColor = TextPrimary))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Text("Tags", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = tagText, onValueChange = { tagText = it }, placeholder = { Text("Add tag") },
                    singleLine = true, colors = fc(), modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    if (tagText.isNotBlank()) { tags.add(tagText.trim()); tagText = ""; save(mapOf("tags" to tags.toList())) }
                }) { Icon(Icons.Default.Add, "Add", tint = Accent) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                tags.forEach { tag ->
                    Surface(shape = RoundedCornerShape(12.dp), color = BgTertiary, onClick = { tags.remove(tag); save(mapOf("tags" to tags.toList())) }) {
                        Text("$tag ×", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Text("Subtasks", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            subtasks.forEach { sub ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    IconButton(onClick = { scope.launch { api.completeTask(sub.id); val t = api.getTask(taskId); if (t != null) { subtasks.clear(); subtasks.addAll(t.subtasks) } } }, modifier = Modifier.padding(0.dp)) {
                        Icon(Icons.Default.Check, "Complete", tint = if (sub.status == "done") Accent else TextDim, modifier = Modifier.padding(0.dp))
                    }
                    Text(sub.title, color = if (sub.status == "done") TextDim else TextPrimary, modifier = Modifier.weight(1f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = subtaskTitle, onValueChange = { subtaskTitle = it }, placeholder = { Text("Add subtask") },
                    singleLine = true, colors = fc(), modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    if (subtaskTitle.isNotBlank()) {
                        val t = subtaskTitle; subtaskTitle = ""
                        scope.launch { api.createTask(t, parentId = taskId); val full = api.getTask(taskId); if (full != null) { subtasks.clear(); subtasks.addAll(full.subtasks) } }
                    }
                }) { Icon(Icons.Default.Add, "Add", tint = Accent) }
            }

            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Default.Delete, null, tint = ErrorColor); Spacer(modifier = Modifier.width(4.dp))
                Text("Delete Task", color = ErrorColor)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Task") }, text = { Text("Delete \"$title\"?") },
            confirmButton = { TextButton(onClick = { showDeleteConfirm = false; scope.launch { api.deleteTask(taskId); onDismiss() } }) { Text("Delete", color = ErrorColor) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = TextSecondary) } },
            containerColor = BgSecondary)
    }
}

@Composable private fun fc() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent, unfocusedBorderColor = Border, focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary, cursorColor = Accent, focusedLabelColor = Accent, unfocusedLabelColor = TextSecondary)
