package ai.gizmo.app.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.gizmo.app.R
import ai.gizmo.app.model.Conversation
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.BgHover
import ai.gizmo.app.ui.theme.BgSecondary
import ai.gizmo.app.ui.theme.Border
import ai.gizmo.app.ui.theme.ErrorColor
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary
import ai.gizmo.app.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Composable
fun ConversationDrawer(
    conversations: List<Conversation>,
    activeConversationId: String?,
    searchResults: List<Conversation>,
    isSearching: Boolean,
    onNewChat: () -> Unit,
    onConversationClick: (Conversation) -> Unit,
    onDeleteConversation: (Conversation) -> Unit,
    onRenameConversation: (Conversation, String) -> Unit,
    onExportConversation: (Conversation) -> Unit,
    onSearch: (String) -> Unit,
    onSettingsClick: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }

    ModalDrawerSheet(
        drawerContainerColor = BgSecondary,
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // New Chat button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNewChat() }
                    .padding(vertical = 12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Accent)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    stringResource(R.string.new_chat),
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }

            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearch(it)
                },
                placeholder = {
                    Text(stringResource(R.string.search_conversations), color = TextDim, fontSize = 14.sp)
                },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextDim) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Accent
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Conversation list
            val displayList = if (isSearching) searchResults else conversations
            val grouped = remember(displayList.toList()) { groupByDate(displayList) }

            LazyColumn(modifier = Modifier.weight(1f)) {
                grouped.forEach { (label, convos) ->
                    item {
                        Text(
                            text = label,
                            color = TextDim,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(convos, key = { it.id }) { conv ->
                        ConversationItem(
                            conversation = conv,
                            isActive = conv.id == activeConversationId,
                            onClick = { onConversationClick(conv) },
                            onDelete = { onDeleteConversation(conv) },
                            onRename = {
                                renameTarget = conv
                                renameText = conv.title
                            },
                            onExport = { onExportConversation(conv) }
                        )
                    }
                }
            }

            HorizontalDivider(color = Border)
            Spacer(modifier = Modifier.height(8.dp))

            // Settings link
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSettingsClick() }
                    .padding(vertical = 8.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = TextSecondary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.settings_title), color = TextSecondary)
            }
        }
    }

    // Rename dialog
    renameTarget?.let { conv ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        cursorColor = Accent
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameConversation(conv, renameText)
                    renameTarget = null
                }) {
                    Text(stringResource(R.string.save), color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.cancel), color = TextSecondary)
                }
            },
            containerColor = BgSecondary
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(
                if (isActive) Modifier.padding(0.dp) else Modifier.padding(0.dp)
            )
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Text(
            text = conversation.title,
            color = if (isActive) Accent else TextPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onExport, modifier = Modifier.padding(0.dp)) {
            Icon(Icons.Default.FileDownload, contentDescription = "Export", tint = TextDim, modifier = Modifier.padding(0.dp))
        }
        IconButton(onClick = onRename, modifier = Modifier.padding(0.dp)) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.rename), tint = TextDim, modifier = Modifier.padding(0.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.padding(0.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = ErrorColor.copy(alpha = 0.7f),
                modifier = Modifier.padding(0.dp)
            )
        }
    }
}

private fun groupByDate(conversations: List<Conversation>): List<Pair<String, List<Conversation>>> {
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    val weekAgo = today.minusDays(7)

    val todayList = mutableListOf<Conversation>()
    val yesterdayList = mutableListOf<Conversation>()
    val weekList = mutableListOf<Conversation>()
    val olderList = mutableListOf<Conversation>()

    conversations.forEach { conv ->
        val date = parseDate(conv.updatedAt.ifEmpty { conv.createdAt })
        when {
            date == null -> olderList.add(conv)
            date == today -> todayList.add(conv)
            date == yesterday -> yesterdayList.add(conv)
            date.isAfter(weekAgo) -> weekList.add(conv)
            else -> olderList.add(conv)
        }
    }

    // Sort each group by most recent first
    val byRecent = Comparator<Conversation> { a, b ->
        (b.updatedAt.ifEmpty { b.createdAt }).compareTo(a.updatedAt.ifEmpty { a.createdAt })
    }
    return buildList {
        if (todayList.isNotEmpty()) add("Today" to todayList.sortedWith(byRecent))
        if (yesterdayList.isNotEmpty()) add("Yesterday" to yesterdayList.sortedWith(byRecent))
        if (weekList.isNotEmpty()) add("Previous 7 Days" to weekList.sortedWith(byRecent))
        if (olderList.isNotEmpty()) add("Older" to olderList.sortedWith(byRecent))
    }
}

private fun parseDate(dateStr: String): LocalDate? {
    if (dateStr.isBlank()) return null
    return try {
        OffsetDateTime.parse(dateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDate()
    } catch (_: DateTimeParseException) {
        try {
            LocalDate.parse(dateStr.take(10))
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
