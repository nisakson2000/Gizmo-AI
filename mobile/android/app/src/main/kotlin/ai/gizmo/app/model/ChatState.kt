package ai.gizmo.app.model

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ai.gizmo.app.network.GizmoApi
import ai.gizmo.app.network.GizmoWebSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChatViewModel(
    val serverUrl: String,
    private val serverId: String,
    val serverName: String
) : ViewModel() {

    val messages = mutableStateListOf<Message>()
    val streamingContent = mutableStateOf("")
    val streamingThinking = mutableStateOf("")
    val streamingToolCalls = mutableStateListOf<ToolCall>()
    val connectionState = mutableStateOf(ConnectionState.DISCONNECTED)
    val conversations = mutableStateListOf<Conversation>()
    val activeConversationId = mutableStateOf<String?>(null)
    val generating = mutableStateOf(false)
    val modes = mutableStateListOf<Mode>()
    val selectedMode = mutableStateOf("chat")
    val thinkingEnabled = mutableStateOf(false)
    val contextLength = mutableStateOf(32768)
    val serviceHealth = mutableStateListOf<ServiceHealth>()
    val searchResults = mutableStateListOf<Conversation>()
    val isSearching = mutableStateOf(false)
    val pendingImageUri = mutableStateOf<Uri?>(null)
    val pendingDocumentName = mutableStateOf<String?>(null)

    private var pendingImageDataUrl: String? = null
    private var pendingDocumentContent: String? = null
    private var currentTraceId: String = ""

    private val api = GizmoApi(serverUrl)
    private val webSocket = GizmoWebSocket(
        serverUrl = serverUrl,
        onEvent = ::handleEvent,
        onStateChange = ::handleStateChange
    )

    init {
        webSocket.connect()
        loadConversations()
        loadModes()
    }

    fun sendMessage(text: String) {
        if (text.isBlank() && pendingImageUri.value == null && pendingDocumentName.value == null) return

        val imageDataUrl = pendingImageDataUrl
        val docContent = pendingDocumentContent
        val docName = pendingDocumentName.value

        val fullMessage = if (docContent != null && docName != null) {
            if (text.isNotBlank()) "$text\n\n[Attached: $docName]\n$docContent"
            else "[Attached: $docName]\n$docContent"
        } else {
            text
        }

        val userMessage = Message(
            role = "user",
            content = text,
            imageUrl = pendingImageUri.value?.toString()
        )
        messages.add(userMessage)

        clearAttachment()
        streamingContent.value = ""
        streamingThinking.value = ""
        streamingToolCalls.clear()
        generating.value = true

        webSocket.send(
            message = fullMessage,
            thinking = thinkingEnabled.value,
            conversationId = activeConversationId.value,
            mode = selectedMode.value,
            contextLength = contextLength.value,
            image = imageDataUrl
        )
    }

    fun stopGeneration() {
        webSocket.stop()
        finalizeAssistantMessage()
    }

    fun newChat() {
        activeConversationId.value = null
        messages.clear()
        streamingContent.value = ""
        streamingThinking.value = ""
        streamingToolCalls.clear()
        generating.value = false
        clearAttachment()
    }

    fun loadConversation(conversationId: String) {
        viewModelScope.launch {
            val result = api.getConversation(conversationId)
            if (result != null) {
                messages.clear()
                messages.addAll(result)
                activeConversationId.value = conversationId
                streamingContent.value = ""
                streamingThinking.value = ""
                streamingToolCalls.clear()
                generating.value = false
            }
        }
    }

    fun loadConversations() {
        viewModelScope.launch {
            val result = api.getConversations()
            conversations.clear()
            conversations.addAll(result)
        }
    }

    fun softDeleteConversation(id: String) {
        conversations.removeAll { it.id == id }
        if (activeConversationId.value == id) newChat()
    }

    fun undoDeleteConversation(conversation: Conversation) {
        conversations.add(0, conversation)
    }

    fun confirmDeleteConversation(id: String) {
        viewModelScope.launch { api.deleteConversation(id) }
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        viewModelScope.launch {
            if (api.renameConversation(conversationId, newTitle)) {
                val idx = conversations.indexOfFirst { it.id == conversationId }
                if (idx >= 0) {
                    conversations[idx] = conversations[idx].copy(title = newTitle)
                }
            }
        }
    }

    fun searchConversations(query: String) {
        if (query.isBlank()) {
            isSearching.value = false
            searchResults.clear()
            return
        }
        isSearching.value = true
        viewModelScope.launch {
            val results = api.searchConversations(query)
            searchResults.clear()
            searchResults.addAll(results)
        }
    }

    fun handleImagePick(uri: Uri, contentResolver: ContentResolver) {
        pendingImageUri.value = uri
        pendingDocumentName.value = null
        pendingDocumentContent = null
        viewModelScope.launch {
            val dataUrl = api.uploadImage(uri, contentResolver)
            pendingImageDataUrl = dataUrl
        }
    }

    fun handleDocumentPick(uri: Uri, contentResolver: ContentResolver) {
        pendingImageUri.value = null
        pendingImageDataUrl = null
        viewModelScope.launch {
            val result = api.uploadDocument(uri, contentResolver)
            if (result != null) {
                pendingDocumentName.value = result.first
                pendingDocumentContent = result.second
            }
        }
    }

    fun clearAttachment() {
        pendingImageUri.value = null
        pendingImageDataUrl = null
        pendingDocumentName.value = null
        pendingDocumentContent = null
    }

    fun loadModes() {
        viewModelScope.launch {
            val result = api.getModes()
            modes.clear()
            modes.addAll(result)
        }
    }

    fun loadServiceHealth() {
        viewModelScope.launch {
            val result = api.getServiceHealth()
            serviceHealth.clear()
            serviceHealth.addAll(result)
        }
    }

    private fun handleEvent(event: ServerEvent) {
        viewModelScope.launch(Dispatchers.Main) {
            when (event) {
                is ServerEvent.TraceId -> {
                    currentTraceId = event.traceId
                }
                is ServerEvent.Thinking -> {
                    streamingThinking.value += event.content
                }
                is ServerEvent.Token -> {
                    streamingContent.value += event.content
                }
                is ServerEvent.ToolCall -> {
                    streamingToolCalls.add(ToolCall(event.tool, event.status))
                }
                is ServerEvent.ToolResult -> {
                    val idx = streamingToolCalls.indexOfLast { it.tool == event.tool }
                    if (idx >= 0) {
                        streamingToolCalls[idx] = streamingToolCalls[idx].copy(
                            status = "done",
                            result = event.result
                        )
                    }
                }
                is ServerEvent.Title -> {
                    if (activeConversationId.value == null) {
                        activeConversationId.value = event.conversationId
                    }
                    val idx = conversations.indexOfFirst { it.id == event.conversationId }
                    if (idx >= 0) {
                        conversations[idx] = conversations[idx].copy(title = event.title)
                    } else {
                        conversations.add(0, Conversation(
                            id = event.conversationId,
                            title = event.title
                        ))
                    }
                }
                is ServerEvent.Usage -> { /* Available for future analytics display */ }
                is ServerEvent.Done -> {
                    activeConversationId.value = event.conversationId
                    finalizeAssistantMessage()
                    connectionState.value = ConnectionState.CONNECTED
                    loadConversations()
                }
                is ServerEvent.Error -> {
                    messages.add(Message(
                        role = "assistant",
                        content = "Error: ${event.error}",
                        traceId = event.traceId ?: ""
                    ))
                    generating.value = false
                    connectionState.value = ConnectionState.CONNECTED
                }
                is ServerEvent.Unknown -> { /* Ignore unrecognized event types */ }
            }
        }
    }

    private fun finalizeAssistantMessage() {
        val content = streamingContent.value
        val thinking = streamingThinking.value
        val toolCalls = streamingToolCalls.toList()

        if (content.isNotEmpty() || thinking.isNotEmpty() || toolCalls.isNotEmpty()) {
            messages.add(Message(
                role = "assistant",
                content = content,
                thinking = thinking,
                traceId = currentTraceId,
                toolCalls = toolCalls
            ))
        }

        streamingContent.value = ""
        streamingThinking.value = ""
        streamingToolCalls.clear()
        generating.value = false
    }

    private fun handleStateChange(state: ConnectionState) {
        viewModelScope.launch(Dispatchers.Main) {
            connectionState.value = state
        }
    }

    override fun onCleared() {
        super.onCleared()
        webSocket.disconnect()
    }
}

class ChatViewModelFactory(
    private val serverUrl: String,
    private val serverId: String,
    private val serverName: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(serverUrl, serverId, serverName) as T
    }
}
