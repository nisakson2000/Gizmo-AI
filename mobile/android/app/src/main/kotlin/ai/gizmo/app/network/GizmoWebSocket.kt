package ai.gizmo.app.network

import ai.gizmo.app.model.ConnectionState
import ai.gizmo.app.model.ServerEvent
import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import kotlin.math.min

private const val RECONNECT_INITIAL_MS = 1000L
private const val RECONNECT_MAX_MS = 30000L

class GizmoWebSocket(
    private val serverUrl: String,
    private val wsPath: String = "/ws/chat",
    private val onEvent: (ServerEvent) -> Unit,
    private val onBinaryMessage: ((ByteArray) -> Unit)? = null,
    private val onStateChange: (ConnectionState) -> Unit
) {
    private var webSocket: WebSocket? = null
    private var reconnectDelay = RECONNECT_INITIAL_MS
    private var shouldReconnect = true
    private val handler = Handler(Looper.getMainLooper())

    fun connect() {
        if (webSocket != null) return
        onStateChange(ConnectionState.CONNECTING)

        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/') + wsPath

        val request = Request.Builder().url(wsUrl).build()

        webSocket = GizmoApi.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                reconnectDelay = RECONNECT_INITIAL_MS
                onStateChange(ConnectionState.CONNECTED)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val event = ServerEvent.parse(json)
                    onEvent(event)
                } catch (_: Exception) { }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                onBinaryMessage?.invoke(bytes.toByteArray())
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
        image: String? = null,
        regenerate: Boolean = false,
        videoFrames: List<String>? = null,
        videoUrl: String? = null,
        tts: Boolean = false,
        voiceId: String? = null,
        ttsSpeed: Float = 1.0f,
        ttsLanguage: String = "Auto"
    ) {
        val json = JSONObject().apply {
            put("message", message)
            put("thinking", thinking)
            put("tts", tts)
            put("context_length", contextLength)
            put("mode", mode)
            put("regenerate", regenerate)
            if (conversationId != null) put("conversation_id", conversationId)
            if (image != null) put("image", image)
            if (videoFrames != null) {
                val arr = org.json.JSONArray()
                videoFrames.forEach { arr.put(it) }
                put("video_frames", arr)
            }
            if (videoUrl != null) put("video_url", videoUrl)
            if (tts && voiceId != null) put("voice_id", voiceId)
            if (tts) {
                put("tts_speed", ttsSpeed.toDouble())
                put("tts_language", ttsLanguage)
            }
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
        reconnectDelay = min(reconnectDelay * 2, RECONNECT_MAX_MS)
    }
}
