package ai.gizmo.app.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import ai.gizmo.app.model.Message
import ai.gizmo.app.model.ToolCall
import ai.gizmo.app.ui.components.ThinkingBlock
import ai.gizmo.app.ui.components.ToolCallCard
import ai.gizmo.app.ui.theme.TextPrimary
import ai.gizmo.app.ui.theme.UserMsg
import coil3.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown

@Composable
fun MessageBubble(message: Message, serverUrl: String, modifier: Modifier = Modifier) {
    if (message.role == "user") {
        UserBubble(message, modifier)
    } else {
        AssistantBubble(message, serverUrl, modifier)
    }
}

@Composable
private fun UserBubble(message: Message, modifier: Modifier = Modifier) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            color = UserMsg,
            modifier = Modifier.widthIn(max = screenWidth * 0.8f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!message.imageUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = message.imageUrl,
                        contentDescription = "Attached image",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .padding(bottom = 8.dp)
                    )
                }
                if (message.content.isNotEmpty()) {
                    Text(
                        text = message.content,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(message: Message, serverUrl: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Thinking block
        if (message.thinking.isNotEmpty()) {
            ThinkingBlock(thinking = message.thinking)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Tool calls
        message.toolCalls.forEach { toolCall ->
            ToolCallCard(toolCall = toolCall)
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Content rendered as markdown
        if (message.content.isNotEmpty()) {
            Markdown(
                content = message.content,
                modifier = Modifier.fillMaxWidth()
            )
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
        // Streaming thinking
        if (streamingThinking.isNotEmpty()) {
            ThinkingBlock(thinking = streamingThinking, isStreaming = true)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Streaming tool calls
        streamingToolCalls.forEach { toolCall ->
            ToolCallCard(toolCall = toolCall)
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Streaming content
        if (streamingContent.isNotEmpty()) {
            Markdown(
                content = streamingContent,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
