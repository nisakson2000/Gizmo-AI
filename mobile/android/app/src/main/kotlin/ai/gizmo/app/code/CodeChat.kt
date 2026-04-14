package ai.gizmo.app.code

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.gizmo.app.model.Message
import ai.gizmo.app.model.ServerEvent
import ai.gizmo.app.model.ToolCall
import ai.gizmo.app.network.GizmoWebSocket
import ai.gizmo.app.ui.components.ThinkingBlock
import ai.gizmo.app.ui.components.ToolCallCard
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.BgPrimary
import ai.gizmo.app.ui.theme.BgSecondary
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary
import com.mikepenz.markdown.m3.Markdown
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeChat(serverUrl: String, code: String, language: String, onDismiss: () -> Unit) {
    val messages = remember { mutableStateListOf<Message>() }
    var inputText by remember { mutableStateOf("") }
    var streamingContent by remember { mutableStateOf("") }
    var streamingThinking by remember { mutableStateOf("") }
    val streamingTools = remember { mutableStateListOf<ToolCall>() }
    var generating by remember { mutableStateOf(false) }

    val ws = remember {
        GizmoWebSocket(serverUrl = serverUrl, wsPath = "/ws/code-chat",
            onEvent = { event ->
                when (event) {
                    is ServerEvent.Token -> streamingContent += event.content
                    is ServerEvent.Thinking -> streamingThinking += event.content
                    is ServerEvent.ToolCall -> streamingTools.add(ToolCall(event.tool, event.status))
                    is ServerEvent.ToolResult -> {
                        val idx = streamingTools.indexOfFirst { it.tool == event.tool && it.status == "running" }
                        if (idx >= 0) streamingTools[idx] = streamingTools[idx].copy(status = "done", result = event.result)
                    }
                    is ServerEvent.Done -> {
                        if (streamingContent.isNotEmpty() || streamingThinking.isNotEmpty()) {
                            messages.add(Message(role = "assistant", content = streamingContent, thinking = streamingThinking, toolCalls = streamingTools.toList()))
                        }
                        streamingContent = ""; streamingThinking = ""; streamingTools.clear(); generating = false
                    }
                    else -> {}
                }
            },
            onStateChange = {}
        )
    }

    DisposableEffect(Unit) { ws.connect(); onDispose { ws.disconnect() } }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Ask Gizmo", color = TextPrimary) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary))
        }, containerColor = BgPrimary
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                items(messages, key = { it.id }) { msg ->
                    if (msg.role == "user") {
                        Text(msg.content, color = Accent, modifier = Modifier.padding(vertical = 4.dp))
                    } else {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            if (msg.thinking.isNotEmpty()) ThinkingBlock(thinking = msg.thinking)
                            msg.toolCalls.forEach { ToolCallCard(toolCall = it) }
                            if (msg.content.isNotEmpty()) Markdown(content = msg.content, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (generating) {
                    item {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            if (streamingThinking.isNotEmpty()) ThinkingBlock(thinking = streamingThinking, isStreaming = true)
                            streamingTools.forEach { ToolCallCard(toolCall = it) }
                            if (streamingContent.isNotEmpty()) Markdown(content = streamingContent, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(value = inputText, onValueChange = { inputText = it },
                    placeholder = { Text("Ask about code...", color = TextDim) },
                    colors = TextFieldDefaults.colors(focusedContainerColor = BgSecondary, unfocusedContainerColor = BgSecondary, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f), singleLine = true)
                IconButton(onClick = {
                    if (inputText.isNotBlank()) {
                        messages.add(Message(role = "user", content = inputText))
                        // Send message with code context
                        val payload = JSONObject().apply {
                            put("message", inputText); put("code", code); put("language", language)
                        }
                        ws.send(message = inputText)
                        inputText = ""; generating = true
                        streamingContent = ""; streamingThinking = ""; streamingTools.clear()
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Accent) }
            }
        }
    }
}
