package ai.gizmo.app.network

import ai.gizmo.app.model.ConnectionState
import ai.gizmo.app.model.ServerEvent
import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.min

class GizmoWebSocket(
    private val serverUrl: String,
    private val onEvent: (ServerEvent) -> Unit,
    private val onStateChange: (ConnectionState) -> Unit
) {
    private var webSocket: WebSocket? = null
    private var reconnectDelay = 1000L
    private var shouldReconnect = true
    private val handler = Handler(Looper.getMainLooper())

    fun connect() {
        if (webSocket != null) return
        onStateChange(ConnectionState.CONNECTING)

        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + "/ws/chat"

        val request = Request.Builder().url(wsUrl).build()

        webSocket = GizmoApi.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                reconnectDelay = 1000L
                onStateChange(ConnectionState.CONNECTED)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val event = ServerEvent.parse(json)
                    onEvent(event)
                } catch (_: Exception) {
                    // Ignore malformed messages
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                this@GizmoWebSocket.webSocket = null
                onStateChange(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                this@GizmoWebSocket.webSocket = null
                onStateChange(ConnectionState.DISCONNECTED)
                if (code != 1000) scheduleReconnect()
            }
        })
    }

    fun send(
        message: String,
        thinking: Boolean = false,
        conversationId: String? = null,
        mode: String = "chat",
        contextLength: Int = 32768,
        image: String? = null
    ) {
        val json = JSONObject().apply {
            put("message", message)
            put("thinking", thinking)
            put("tts", false)
            put("context_length", contextLength)
            put("mode", mode)
            put("regenerate", false)
            if (conversationId != null) put("conversation_id", conversationId)
            if (image != null) put("image", image)
        }

        onStateChange(ConnectionState.GENERATING)
        webSocket?.send(json.toString())
    }

    fun stop() {
        shouldReconnect = true
        webSocket?.cancel()
        webSocket = null
        handler.post { connect() }
    }

    fun disconnect() {
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "bye")
        webSocket = null
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        handler.postDelayed({
            if (shouldReconnect && webSocket == null) {
                connect()
            }
        }, reconnectDelay)
        reconnectDelay = min(reconnectDelay * 2, 30000L)
    }
}
