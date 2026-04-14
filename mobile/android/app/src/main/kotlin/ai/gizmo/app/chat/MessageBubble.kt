package ai.gizmo.app.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.widget.VideoView
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ai.gizmo.app.model.Message
import ai.gizmo.app.model.ToolCall
import ai.gizmo.app.ui.components.ThinkingBlock
import ai.gizmo.app.ui.components.ToolCallCard
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.BgTertiary
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary
import ai.gizmo.app.ui.theme.TextSecondary
import ai.gizmo.app.ui.theme.UserMsg
import coil3.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown

private val mediaUrlRegex = Regex("""\[([^\]]+)]\((/api/media/[^\)]+)\)""")

@Composable
fun MessageBubble(
    message: Message,
    messageIndex: Int,
    isSelected: Boolean,
    isLastAssistant: Boolean,
    serverUrl: String,
    generating: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onVariantSwitch: (Int) -> Unit,
    onDownload: (String) -> Unit,
    onViewMedia: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (message.role == "user") {
        UserBubble(message, serverUrl, isSelected, onSelect, onEdit, onCopy, onVariantSwitch, onViewMedia, modifier)
    } else {
        AssistantBubble(message, serverUrl, isSelected, isLastAssistant, generating,
            onSelect, onCopy, onRegenerate, onVariantSwitch, onDownload, onViewMedia, modifier)
    }
}

@Composable
private fun UserBubble(
    message: Message,
    serverUrl: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onVariantSwitch: (Int) -> Unit,
    onViewMedia: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            onClick = onSelect,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            color = UserMsg,
            modifier = Modifier.widthIn(max = screenWidth * 0.8f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!message.imageUrl.isNullOrEmpty()) {
                    val fullImageUrl = resolveMediaUrl(message.imageUrl, serverUrl)
                    AsyncImage(
                        model = fullImageUrl,
                        contentDescription = "Attached image",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .padding(bottom = 8.dp)
                            .clickable { onViewMedia(fullImageUrl) }
                    )
                }
                if (!message.videoUrl.isNullOrEmpty()) {
                    AttachmentChip("Video attachment", resolveMediaUrl(message.videoUrl, serverUrl), onViewMedia)
                }
                if (!message.audioUrl.isNullOrEmpty()) {
                    AttachmentChip("Audio attachment", resolveMediaUrl(message.audioUrl, serverUrl), onViewMedia)
                }
                if (message.displayContent.isNotEmpty()) {
                    Text(text = message.displayContent, color = TextPrimary)
                }
            }
        }

        // Variant navigation
        if (message.variants.size > 1) {
            VariantNav(message.currentVariantIndex, message.variants.size, onVariantSwitch)
        }

        // Action row
        if (isSelected) {
            Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionChip("Edit", Icons.Default.Edit, onEdit)
                ActionChip("Copy", Icons.Default.ContentCopy, onCopy)
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    message: Message,
    serverUrl: String,
    isSelected: Boolean,
    isLastAssistant: Boolean,
    generating: Boolean,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onVariantSwitch: (Int) -> Unit,
    onDownload: (String) -> Unit,
    onViewMedia: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Thinking block
        if (message.displayThinking.isNotEmpty()) {
            ThinkingBlock(thinking = message.displayThinking)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Tool calls
        message.displayToolCalls.forEach { toolCall ->
            ToolCallCard(toolCall = toolCall)
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Video player
        if (!message.videoUrl.isNullOrEmpty()) {
            val fullUrl = resolveMediaUrl(message.videoUrl, serverUrl)
            Box(modifier = Modifier.clickable { onViewMedia(fullUrl) }) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoPath(fullUrl)
                            setOnPreparedListener { mp -> mp.isLooping = true; start() }
                            setOnErrorListener { _, _, _ -> true }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Content rendered as markdown — memoized to avoid re-parsing on revisit
        if (message.displayContent.isNotEmpty()) {
            val content = remember(message.id, message.currentVariantIndex) { message.displayContent }
            Markdown(content = content, modifier = Modifier.fillMaxWidth())

            // Detect download links (/api/media/ URLs)
            mediaUrlRegex.findAll(message.displayContent).forEach { match ->
                val label = match.groupValues[1]
                val url = match.groupValues[2]
                DownloadChip(label, url, onDownload)
            }
        }

        // Variant navigation
        if (message.variants.size > 1) {
            VariantNav(message.currentVariantIndex, message.variants.size, onVariantSwitch)
        }

        // Action row
        if (isSelected && !generating) {
            Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionChip("Copy", Icons.Default.ContentCopy, onCopy)
                if (isLastAssistant) {
                    ActionChip("Regenerate", Icons.Default.Refresh, onRegenerate)
                }
            }
        }
    }
}

@Composable
fun StreamingBubble(
    streamingContent: String,
    streamingThinking: String,
    streamingToolCalls: List<ToolCall>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        if (streamingThinking.isNotEmpty()) {
            ThinkingBlock(thinking = streamingThinking, isStreaming = true)
            Spacer(modifier = Modifier.height(8.dp))
        }
        streamingToolCalls.forEach { toolCall ->
            ToolCallCard(toolCall = toolCall)
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (streamingContent.isNotEmpty()) {
            Markdown(content = streamingContent, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = BgTertiary
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(icon, contentDescription = label, tint = TextSecondary, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(label, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun VariantNav(currentIndex: Int, total: Int, onSwitch: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp)
    ) {
        IconButton(onClick = { onSwitch(-1) }, enabled = currentIndex > 0, modifier = Modifier.size(28.dp)) {
            Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Previous", tint = if (currentIndex > 0) Accent else TextDim, modifier = Modifier.size(18.dp))
        }
        Text("${currentIndex + 1}/$total", color = TextDim, fontSize = 12.sp)
        IconButton(onClick = { onSwitch(1) }, enabled = currentIndex < total - 1, modifier = Modifier.size(28.dp)) {
            Icon(Icons.AutoMirrored.Filled.NavigateNext, "Next", tint = if (currentIndex < total - 1) Accent else TextDim, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DownloadChip(label: String, url: String, onDownload: (String) -> Unit) {
    Surface(
        onClick = { onDownload(url) },
        shape = RoundedCornerShape(8.dp),
        color = BgTertiary,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Download, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, color = TextPrimary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AttachmentChip(label: String, url: String, onViewMedia: (String) -> Unit) {
    Surface(
        onClick = { onViewMedia(url) },
        shape = RoundedCornerShape(8.dp),
        color = BgTertiary,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Download, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, color = TextPrimary, fontSize = 13.sp)
        }
    }
}

fun copyToClipboard(context: Context, text: String, label: String = "Copied to clipboard") {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("gizmo", text))
    Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
}
