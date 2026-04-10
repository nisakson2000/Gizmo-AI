package ai.gizmo.app.chat

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.gizmo.app.R
import ai.gizmo.app.model.Mode
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.AccentDim
import ai.gizmo.app.ui.theme.BgPrimary
import ai.gizmo.app.ui.theme.BgSecondary
import ai.gizmo.app.ui.theme.BgTertiary
import ai.gizmo.app.ui.theme.Border
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary
import ai.gizmo.app.ui.theme.TextSecondary

@Composable
fun ChatInput(
    generating: Boolean,
    thinkingEnabled: Boolean,
    onThinkingToggle: (Boolean) -> Unit,
    selectedMode: String,
    modes: List<Mode>,
    onModeSelected: (String) -> Unit,
    pendingImageUri: Uri?,
    pendingDocumentName: String?,
    onClearAttachment: () -> Unit,
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showModeMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BgPrimary)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // Pending attachment preview
        if (pendingImageUri != null || pendingDocumentName != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = BgTertiary,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (pendingImageUri != null) Icons.Default.Image
                        else Icons.Default.Description,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = pendingDocumentName ?: stringResource(R.string.image_label),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cancel),
                        tint = TextDim,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onClearAttachment() }
                    )
                }
            }
        }

        // Input row
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Attachment button with dropdown
            Box {
                IconButton(onClick = { showAttachMenu = true }) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = stringResource(R.string.files),
                        tint = TextSecondary
                    )
                }
                DropdownMenu(
                    expanded = showAttachMenu,
                    onDismissRequest = { showAttachMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.image_label)) },
                        leadingIcon = { Icon(Icons.Default.Image, null) },
                        onClick = {
                            showAttachMenu = false
                            onPickImage()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.document_label)) },
                        leadingIcon = { Icon(Icons.Default.Description, null) },
                        onClick = {
                            showAttachMenu = false
                            onPickDocument()
                        }
                    )
                }
            }

            // Text field
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        stringResource(R.string.message_placeholder),
                        color = TextDim
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = BgSecondary,
                    unfocusedContainerColor = BgSecondary,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Accent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 120.dp)
            )

            // Mic button (placeholder)
            IconButton(onClick = {
                Toast.makeText(context, context.getString(R.string.coming_soon), Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.Mic, contentDescription = "Voice", tint = TextDim)
            }

            // Send / Stop button
            if (generating) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = stringResource(R.string.stop),
                        tint = Accent
                    )
                }
            } else {
                val hasContent = text.isNotBlank() || pendingImageUri != null || pendingDocumentName != null
                IconButton(
                    onClick = {
                        if (hasContent) {
                            onSend(text)
                            text = ""
                        }
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (hasContent) Accent else TextDim
                    )
                }
            }
        }

        // Toolbar: Think pill + Mode selector
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = thinkingEnabled,
                onClick = { onThinkingToggle(!thinkingEnabled) },
                label = { Text(stringResource(R.string.think), fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Accent,
                    selectedLabelColor = BgPrimary,
                    containerColor = Color.Transparent,
                    labelColor = TextSecondary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = if (thinkingEnabled) Accent else Border,
                    selectedBorderColor = Accent,
                    enabled = true,
                    selected = thinkingEnabled
                )
            )

            Box {
                val modeLabel = modes.find { it.name == selectedMode }?.label
                    ?: selectedMode.replaceFirstChar { it.uppercase() }
                FilterChip(
                    selected = false,
                    onClick = { showModeMenu = true },
                    label = { Text(modeLabel, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = TextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = Border,
                        selectedBorderColor = Border,
                        enabled = true,
                        selected = false
                    )
                )
                DropdownMenu(
                    expanded = showModeMenu,
                    onDismissRequest = { showModeMenu = false }
                ) {
                    modes.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    mode.label,
                                    color = if (mode.name == selectedMode) Accent else TextPrimary
                                )
                            },
                            onClick = {
                                onModeSelected(mode.name)
                                showModeMenu = false
                            }
                        )
                    }
                }
            }
        }
    }
}

fun setSuggestionText(text: String, setter: (String) -> Unit) {
    setter(text)
}
