package ai.gizmo.app.model

import org.json.JSONObject

sealed class ServerEvent {
    data class TraceId(val traceId: String) : ServerEvent()
    data class Thinking(val content: String) : ServerEvent()
    data class Token(val content: String) : ServerEvent()
    data class ToolCall(val tool: String, val status: String) : ServerEvent()
    data class ToolResult(val tool: String, val result: String) : ServerEvent()
    data class Title(val title: String, val conversationId: String) : ServerEvent()
    data class Usage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int) : ServerEvent()
    data class Done(val traceId: String, val conversationId: String) : ServerEvent()
    data class Error(val error: String, val traceId: String?) : ServerEvent()
    data class Unknown(val type: String) : ServerEvent()

    companion object {
        fun parse(json: JSONObject): ServerEvent {
            return when (val type = json.optString("type", "")) {
                "trace_id" -> TraceId(json.optString("trace_id", ""))
                "thinking" -> Thinking(json.optString("content", ""))
                "token" -> Token(json.optString("content", ""))
                "tool_call" -> ToolCall(
                    tool = json.optString("tool", ""),
                    status = json.optString("status", "")
                )
                "tool_result" -> ToolResult(
                    tool = json.optString("tool", ""),
                    result = json.optString("result", "")
                )
                "title" -> Title(
                    title = json.optString("title", ""),
                    conversationId = json.optString("conversation_id", "")
                )
                "usage" -> Usage(
                    promptTokens = json.optInt("prompt_tokens", 0),
                    completionTokens = json.optInt("completion_tokens", 0),
                    totalTokens = json.optInt("total_tokens", 0)
                )
                "done" -> Done(
                    traceId = json.optString("trace_id", ""),
                    conversationId = json.optString("conversation_id", "")
                )
                "error" -> Error(
                    error = json.optString("error", ""),
                    traceId = json.optString("trace_id").takeIf { it.isNotEmpty() }
                )
                else -> Unknown(type)
            }
        }
    }
}
