package ai.gizmo.app.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.gizmo.app.R
import ai.gizmo.app.model.Message
import ai.gizmo.app.model.ToolCall
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.BgSecondary
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary
import ai.gizmo.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun MessageList(
    messages: List<Message>,
    streamingContent: String,
    streamingThinking: String,
    streamingToolCalls: List<ToolCall>,
    generating: Boolean,
    serverUrl: String,
    selectedIndex: Int?,
    editingIndex: Int?,
    onSelectMessage: (Int?) -> Unit,
    onEditMessage: (Int, String) -> Unit,
    onStartEdit: (Int) -> Unit,
    onCancelEdit: () -> Unit,
    onCopyMessage: (String) -> Unit,
    onRegenerate: () -> Unit,
    onVariantSwitch: (Int, Int) -> Unit,
    onDownload: (String) -> Unit,
    onViewMedia: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val isAtBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }

    val hasStreamingContent = streamingContent.isNotEmpty() ||
            streamingThinking.isNotEmpty() ||
            streamingToolCalls.isNotEmpty()

    var previousMessageSize by remember { mutableIntStateOf(0) }

    LaunchedEffect(messages.size, streamingContent.length, streamingThinking.length, streamingToolCalls.size) {
        val totalItems = listState.layoutInfo.totalItemsCount
        // "Just loaded" = conversation switched in / opened fresh. Always snap to the
        // last message, like desktop chat apps.
        val justLoaded = previousMessageSize == 0 && messages.isNotEmpty()
        if (totalItems > 0) {
            when {
                justLoaded -> listState.scrollToItem(totalItems - 1)
                isAtBottom || generating -> listState.animateScrollToItem(totalItems - 1)
            }
        }
        previousMessageSize = messages.size
    }

    // When the IME opens/closes the viewport shrinks/grows under us. If the user was
    // already tracking the bottom, keep them pinned there so the last message doesn't
    // slide out of view when the keyboard animates in.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems == 0) return@LaunchedEffect
        val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisibleIndex >= totalItems - 1) {
            listState.animateScrollBy(Float.MAX_VALUE)
        }
    }

    val lastAssistantIndex = messages.indexOfLast { it.role == "assistant" }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            itemsIndexed(messages, key = { _, msg -> msg.id }) { index, message ->
                if (editingIndex == index && message.role == "user") {
                    InlineEditField(
                        originalText = message.content,
                        onSave = { newText -> onEditMessage(index, newText) },
                        onCancel = onCancelEdit
                    )
                } else {
                    MessageBubble(
                        message = message,
                        messageIndex = index,
                        isSelected = selectedIndex == index,
                        isLastAssistant = index == lastAssistantIndex,
                        serverUrl = serverUrl,
                        generating = generating,
                        onSelect = {
                            onSelectMessage(if (selectedIndex == index) null else index)
                        },
                        onEdit = { onStartEdit(index) },
                        onCopy = { copyToClipboard(context, message.displayContent) },
                        onRegenerate = onRegenerate,
                        onVariantSwitch = { dir -> onVariantSwitch(index, dir) },
                        onDownload = onDownload,
                        onViewMedia = onViewMedia
                    )
                }
            }

            if (generating && hasStreamingContent) {
                item(key = "streaming") {
                    StreamingBubble(
                        streamingContent = streamingContent,
                        streamingThinking = streamingThinking,
                        streamingToolCalls = streamingToolCalls
                    )
                }
            }

            if (generating && !hasStreamingContent) {
                item(key = "loading") {
                    ThinkingIndicator()
                }
            }
        }

        if (!isAtBottom && !generating) {
            SmallFloatingActionButton(
                onClick = {
                    scope.launch {
                        val count = listState.layoutInfo.totalItemsCount
                        if (count > 0) listState.animateScrollToItem(count - 1)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                containerColor = BgSecondary,
                contentColor = Accent,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(4.dp)
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom")
            }
        }
    }
}

@Composable
private fun InlineEditField(
    originalText: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    var editText by remember { mutableStateOf(originalText) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = editText,
            onValueChange = { editText = it },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Accent.copy(alpha = 0.5f),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Accent
            ),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel), color = TextSecondary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = { onSave(editText) }) {
                Text(stringResource(R.string.save), color = Accent)
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val dotColor = Accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse
                ), label = "dot$index"
            )
            Box(modifier = Modifier.size(6.dp).alpha(alpha)) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color = dotColor)
                }
            }
            if (index < 2) Spacer(modifier = Modifier.width(4.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = stringResource(R.string.thinking_indicator), color = TextDim, fontSize = 13.sp)
    }
}
