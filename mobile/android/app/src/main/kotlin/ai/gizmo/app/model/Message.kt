package ai.gizmo.app.model

import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val thinking: String = "",
    val timestamp: String = "",
    val traceId: String = "",
    val audioUrl: String? = null,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val toolCalls: List<ToolCall> = emptyList()
)

data class ToolCall(
    val tool: String,
    val status: String,
    val result: String = ""
)

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: String = "",
    val updatedAt: String = "",
    val snippet: String = ""
)

data class Mode(
    val name: String,
    val label: String,
    val description: String = "",
    val icon: String = ""
)

data class ServiceHealth(
    val name: String,
    val status: String,
    val error: String? = null
)

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, GENERATING
}
