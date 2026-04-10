package ai.gizmo.app.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.gizmo.app.R
import ai.gizmo.app.model.Message
import ai.gizmo.app.model.ToolCall
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.BgSecondary
import ai.gizmo.app.ui.theme.TextDim
import kotlinx.coroutines.launch

@Composable
fun MessageList(
    messages: List<Message>,
    streamingContent: String,
    streamingThinking: String,
    streamingToolCalls: List<ToolCall>,
    generating: Boolean,
    serverUrl: String,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val isAtBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }

    // Total items: messages + streaming bubble (if generating) + loading indicator (if generating but no content yet)
    val hasStreamingContent = streamingContent.isNotEmpty() ||
            streamingThinking.isNotEmpty() ||
            streamingToolCalls.isNotEmpty()

    // Auto-scroll when generating
    LaunchedEffect(messages.size, streamingContent, streamingThinking, streamingToolCalls.size) {
        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems > 0 && (isAtBottom || generating)) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message = message, serverUrl = serverUrl)
            }

            // Streaming content
            if (generating && hasStreamingContent) {
                item(key = "streaming") {
                    StreamingBubble(
                        streamingContent = streamingContent,
                        streamingThinking = streamingThinking,
                        streamingToolCalls = streamingToolCalls
                    )
                }
            }

            // "Gizmo is thinking..." indicator
            if (generating && !hasStreamingContent) {
                item(key = "loading") {
                    ThinkingIndicator()
                }
            }
        }

        // Scroll-to-bottom FAB
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
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Scroll to bottom"
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    val transition = rememberInfiniteTransition(label = "thinking")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .padding(0.dp)
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color = ai.gizmo.app.ui.theme.Accent)
                }
            }
            if (index < 2) Spacer(modifier = Modifier.width(4.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.thinking_indicator),
            color = TextDim,
            fontSize = 13.sp
        )
    }
}
