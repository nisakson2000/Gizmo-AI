package ai.gizmo.app.network

import ai.gizmo.app.model.Conversation
import ai.gizmo.app.model.Message
import ai.gizmo.app.model.Mode
import ai.gizmo.app.model.ServiceHealth
import ai.gizmo.app.model.ToolCall
import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class VideoUploadResult(
    val filename: String,
    val frames: List<String>,
    val videoUrl: String
)

class GizmoApi(private val serverUrl: String) {

    companion object {
        private const val MAX_IMAGE_SIZE = 50L * 1024 * 1024 // 50MB
        private const val MAX_DOC_SIZE = 50L * 1024 * 1024
        private const val MAX_VIDEO_SIZE = 500L * 1024 * 1024

        private val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        private val trustAllSslContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllManager), SecureRandom())
        }

        private var _trustAll = true
        var client: OkHttpClient = buildClient(true)
            private set

        fun rebuildClient(trustAll: Boolean) {
            if (_trustAll == trustAll) return
            _trustAll = trustAll
            client = buildClient(trustAll)
        }

        private fun buildClient(trustAll: Boolean): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
            if (trustAll) {
                builder.sslSocketFactory(trustAllSslContext.socketFactory, trustAllManager)
                builder.hostnameVerifier { _, _ -> true }
            }
            return builder.build()
        }

        fun sanitizeFilename(name: String): String =
            name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .replace("..", "_")
                .take(255)

        fun validateServerUrl(url: String): Boolean {
            val trimmed = url.trim().lowercase()
            return trimmed.startsWith("http://") || trimmed.startsWith("https://")
        }
    }

    private val baseUrl = serverUrl.trimEnd('/')

    suspend fun getConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/conversations").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Conversation(
                    id = obj.getString("id"),
                    title = obj.optString("title", "Untitled"),
                    createdAt = obj.optString("created_at", ""),
                    updatedAt = obj.optString("updated_at", "")
                )
            }
            }
        } catch (e: Exception) {
            android.util.Log.d("GizmoApi", "getConversations: ${e.message}"); emptyList()
        }
    }

    suspend fun getConversation(id: String): List<Message>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/conversations/$id").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val obj = JSONObject(body)
            val msgs = obj.getJSONArray("messages")
            (0 until msgs.length()).map { i ->
                val m = msgs.getJSONObject(i)
                val toolCallsArr = m.optJSONArray("tool_calls")
                val toolCalls = if (toolCallsArr != null) {
                    (0 until toolCallsArr.length()).map { j ->
                        val tc = toolCallsArr.getJSONObject(j)
                        ToolCall(
                            tool = tc.optString("tool", ""),
                            status = tc.optString("status", "done"),
                            result = tc.optString("result", "")
                        )
                    }
                } else emptyList()

                Message(
                    role = m.getString("role"),
                    content = m.optString("content", ""),
                    thinking = m.optString("thinking", ""),
                    timestamp = m.optString("timestamp", ""),
                    imageUrl = m.optString("image_url").takeIf { it.isNotEmpty() },
                    audioUrl = m.optString("audio_url").takeIf { it.isNotEmpty() },
                    videoUrl = m.optString("video_url").takeIf { it.isNotEmpty() },
                    toolCalls = toolCalls
                )
            }
            }
        } catch (e: Exception) {
            android.util.Log.d("GizmoApi", "getConversation: ${e.message}")
            null
        }
    }

    suspend fun deleteConversation(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/conversations/$id")
                .delete()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            android.util.Log.d("GizmoApi", "deleteConversation: ${e.message}")
            false
        }
    }

    suspend fun renameConversation(id: String, title: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().put("title", title).toString()
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/conversations/$id")
                .patch(body)
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            android.util.Log.d("GizmoApi", "renameConversation: ${e.message}")
            false
        }
    }

    suspend fun searchConversations(query: String): List<Conversation> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/conversations/search?q=${Uri.encode(query)}"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Conversation(
                    id = obj.getString("id"),
                    title = obj.optString("title", "Untitled"),
                    updatedAt = obj.optString("updated_at", ""),
                    snippet = obj.optString("snippet", "")
                )
            }
            }
        } catch (e: Exception) {
            android.util.Log.d("GizmoApi", "searchConversations: ${e.message}"); emptyList()
        }
    }

    suspend fun uploadImage(uri: Uri, contentResolver: ContentResolver): String? =
        withContext(Dispatchers.IO) {
            try {
                val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
                val filename = "upload.${mimeType.substringAfter("/", "jpg")}"

                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", filename,
                        streamingBody(uri, contentResolver, mimeType.toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url("$baseUrl/api/upload-image")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val respBody = response.body?.string() ?: return@withContext null
                JSONObject(respBody).optString("data_url").takeIf { it.isNotEmpty() }
                }
            } catch (e: Exception) {
                android.util.Log.d("GizmoApi", "uploadImage: ${e.message}")
                null
            }
        }

    suspend fun uploadDocument(uri: Uri, contentResolver: ContentResolver): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
                val bytes = inputStream.readBytes()
                inputStream.close()

                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val filename = "document"

                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", filename,
                        bytes.toRequestBody(mimeType.toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url("$baseUrl/api/upload")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val respBody = response.body?.string() ?: return@withContext null
                val obj = JSONObject(respBody)
                val name = obj.optString("filename", "document")
                val content = obj.optString("content", "")
                if (content.isEmpty()) null else Pair(name, content)
                }
            } catch (e: Exception) {
                android.util.Log.d("GizmoApi", "uploadDocument: ${e.message}")
                null
            }
        }

    suspend fun deleteMessagesFrom(conversationId: String, index: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/api/conversations/$conversationId/messages-from/$index")
                    .delete()
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                android.util.Log.d("GizmoApi", "deleteMessagesFrom: ${e.message}")
                false
            }
        }

    private fun streamingBody(uri: Uri, contentResolver: ContentResolver, mime: MediaType): RequestBody {
        return object : RequestBody() {
            override fun contentType() = mime
            override fun writeTo(sink: BufferedSink) {
                contentResolver.openInputStream(uri)?.use { sink.writeAll(it.source()) }
            }
        }
    }

    suspend fun uploadVideo(uri: Uri, contentResolver: ContentResolver): VideoUploadResult? =
        withContext(Dispatchers.IO) {
            try {
                val mimeType = contentResolver.getType(uri) ?: "video/mp4"
                val filename = "upload.${mimeType.substringAfter("/", "mp4")}"
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename, streamingBody(uri, contentResolver, mimeType.toMediaType()))
                    .build()
                val request = Request.Builder().url("$baseUrl/api/upload-video").post(body).build()
                client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val respBody = response.body?.string() ?: return@withContext null
                val obj = JSONObject(respBody)
                val framesArr = obj.optJSONArray("frames")
                val frames = if (framesArr != null) {
                    (0 until framesArr.length()).map { framesArr.getString(it) }
                } else emptyList()
                VideoUploadResult(
                    filename = obj.optString("filename", ""),
                    frames = frames,
                    videoUrl = obj.optString("video_url", "")
                )
                }
            } catch (e: Exception) {
                android.util.Log.d("GizmoApi", "uploadVideo: ${e.message}")
                null
            }
        }

    suspend fun transcribeAudio(uri: Uri, contentResolver: ContentResolver): String? =
        withContext(Dispatchers.IO) {
            try {
                val mimeType = contentResolver.getType(uri) ?: "audio/wav"
                val filename = "audio.${mimeType.substringAfter("/", "wav")}"
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename, streamingBody(uri, contentResolver, mimeType.toMediaType()))
                    .build()
                val request = Request.Builder().url("$baseUrl/api/transcribe").post(body).build()
                client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val respBody = response.body?.string() ?: return@withContext null
                JSONObject(respBody).optString("text").takeIf { it.isNotEmpty() }
                }
            } catch (e: Exception) {
                android.util.Log.d("GizmoApi", "transcribeAudio: ${e.message}")
                null
            }
        }

    suspend fun exportConversation(conversationId: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/conversations/$conversationId/export?format=markdown")
                .build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            response.body?.string()
            }
        } catch (e: Exception) {
            android.util.Log.d("GizmoApi", "exportConversation: ${e.message}")
            null
        }
    }

    suspend fun downloadFile(url: String): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
        try {
            // Only allow /api/ paths or same-origin URLs
            if (!url.startsWith("/api/") && !url.startsWith(baseUrl)) return@withContext null
            val fullUrl = if (url.startsWith("/")) "$baseUrl$url" else url
            val request = Request.Builder().url(fullUrl).build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val bytes = response.body?.bytes() ?: return@withContext null
            val disposition = response.header("Content-Disposition")
            val rawFilename = disposition?.substringAfter("filename=", "")
                ?.trim('"')?.takeIf { it.isNotEmpty() }
                ?: fullUrl.substringAfterLast("/")
            Pair(sanitizeFilename(rawFilename), bytes)
            }
        } catch (e: Exception) {
            android.util.Log.d("GizmoApi", "downloadFile: ${e.message}")
            null
        }
    }

    suspend fun getVoices(): List<ai.gizmo.app.model.Voice> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/voices").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ai.gizmo.app.model.Voice(
                    id = obj.getString("id"),
                    name = obj.optString("name", ""),
                    filename = obj.optString("filename", ""),
                    size = obj.optLong("size", 0),
                    transcript = obj.optString("transcript").takeIf { it.isNotEmpty() }
                )
            }
            }
        } catch (e: Exception) {
            android.util.Log.d("GizmoApi", "getVoices: ${e.message}"); emptyList()
        }
    }

    suspend fun uploadVoice(uri: Uri, name: String, maxDuration: Int, contentResolver: ContentResolver): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val mimeType = contentResolver.getType(uri) ?: "audio/wav"
                val filename = "voice.${mimeType.substringAfter("/", "wav")}"
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename, streamingBody(uri, contentResolver, mimeType.toMediaType()))
                    .addFormDataPart("name", name)
                    .addFormDataPart("max_duration", maxDuration.toString())
                    .build()
                val request = Request.Builder().url("$baseUrl/api/voices").post(body).build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                android.util.Log.d("GizmoApi", "uploadVoice: ${e.message}")
                false
            }
        }

    suspend fun deleteVoice(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/voices/$id").delete().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            android.util.Log.d("GizmoApi", "deleteVoice: ${e.message}")
            false
        }
    }

    suspend fun previewVoice(id: String, text: String, cacheDir: java.io.File): String? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().put("text", text).toString()
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$baseUrl/api/voices/$id/preview").post(body).build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val bytes = response.body?.bytes() ?: return@withContext null
            val file = java.io.File(cacheDir, "preview_$id.wav")
            file.writeBytes(bytes)
            file.absolutePath
            }
        } catch (e: Exception) {
            android.util.Log.d("GizmoApi", "previewVoice: ${e.message}")
            null
        }
    }

    // Mode Editor endpoints
    suspend fun getModeDetail(name: String): ai.gizmo.app.model.ModeDetail? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/modes/$name").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val obj = JSONObject(body)
            ai.gizmo.app.model.ModeDetail(
                name = obj.getString("name"),
                label = obj.optString("label", ""),
                description = obj.optString("description", ""),
                icon = obj.optString("icon", ""),
                order = obj.optInt("order", 0),
                systemPrompt = obj.optString("system_prompt", "")
            )
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "getModeDetail: ${e.message}"); null }
    }

    suspend fun createMode(name: String, label: String, description: String, systemPrompt: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("name", name)
                    put("label", label)
                    put("description", description)
                    put("system_prompt", systemPrompt)
                }.toString()
                val request = Request.Builder()
                    .url("$baseUrl/api/modes")
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) { android.util.Log.d("GizmoApi", "createMode: ${e.message}"); false }
        }

    suspend fun updateMode(name: String, label: String?, description: String?, systemPrompt: String?): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    if (label != null) put("label", label)
                    if (description != null) put("description", description)
                    if (systemPrompt != null) put("system_prompt", systemPrompt)
                }.toString()
                val request = Request.Builder()
                    .url("$baseUrl/api/modes/$name")
                    .put(json.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) { android.util.Log.d("GizmoApi", "updateMode: ${e.message}"); false }
        }

    suspend fun deleteMode(name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/modes/$name").delete().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "deleteMode: ${e.message}"); false }
    }

    // Memory Manager endpoints
    suspend fun getMemories(subdir: String? = null): List<ai.gizmo.app.model.MemoryFile> = withContext(Dispatchers.IO) {
        try {
            val url = if (subdir != null) "$baseUrl/api/memory/list?subdir=$subdir"
                      else "$baseUrl/api/memory/list"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ai.gizmo.app.model.MemoryFile(
                    filename = obj.getString("filename"),
                    subdir = obj.optString("subdir", ""),
                    size = obj.optLong("size", 0),
                    modified = obj.optString("modified", "")
                )
            }
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "getMemories: ${e.message}"); emptyList() }
    }

    suspend fun readMemory(filename: String, subdir: String): ai.gizmo.app.model.MemoryContent? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/memory/read?filename=${Uri.encode(filename)}&subdir=${Uri.encode(subdir)}"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val obj = JSONObject(body)
            ai.gizmo.app.model.MemoryContent(
                filename = obj.optString("filename", filename),
                subdir = obj.optString("subdir", subdir),
                content = obj.optString("content", "")
            )
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "readMemory: ${e.message}"); null }
    }

    suspend fun writeMemory(filename: String, content: String, subdir: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = okhttp3.FormBody.Builder()
                .add("filename", filename)
                .add("content", content)
                .add("subdir", subdir)
                .build()
            val request = Request.Builder().url("$baseUrl/api/memory/write").post(body).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "writeMemory: ${e.message}"); false }
    }

    suspend fun deleteMemory(subdir: String, filename: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/memory/${Uri.encode(subdir)}/${Uri.encode(filename)}")
                .delete().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "deleteMemory: ${e.message}"); false }
    }

    suspend fun clearMemories(): Int = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/memory/clear").delete().build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext 0
            val body = response.body?.string() ?: return@withContext 0
            JSONObject(body).optInt("deleted", 0)
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "clearMemories: ${e.message}"); 0 }
    }

    suspend fun getModes(): List<Mode> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/modes").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Mode(
                    name = obj.getString("name"),
                    label = obj.optString("label", obj.getString("name")),
                    description = obj.optString("description", ""),
                    icon = obj.optString("icon", "")
                )
            }
            }
        } catch (e: Exception) {
            android.util.Log.d("GizmoApi", "getModes: ${e.message}"); emptyList()
        }
    }

    suspend fun getServiceHealth(): List<ServiceHealth> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/services/health").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val obj = JSONObject(body)
            obj.keys().asSequence().map { key ->
                val svc = obj.getJSONObject(key)
                ServiceHealth(
                    name = key,
                    status = svc.optString("status", "unknown"),
                    error = svc.optString("error").takeIf { it.isNotEmpty() }
                )
            }.toList()
            }
        } catch (e: Exception) {
            android.util.Log.d("GizmoApi", "getServiceHealth: ${e.message}"); emptyList()
        }
    }

    // --- Tracker API ---

    private fun parseTask(obj: JSONObject): ai.gizmo.app.model.TrackerTask {
        val tagsArr = obj.optJSONArray("tags")
        val tags = if (tagsArr != null) (0 until tagsArr.length()).map { tagsArr.getString(it) } else emptyList()
        val subtasksArr = obj.optJSONArray("subtasks")
        val subtasks = if (subtasksArr != null) (0 until subtasksArr.length()).map { parseTask(subtasksArr.getJSONObject(it)) } else emptyList()
        return ai.gizmo.app.model.TrackerTask(
            id = obj.optString("id", ""), title = obj.optString("title", ""),
            description = obj.optString("description", ""), status = obj.optString("status", "todo"),
            priority = obj.optString("priority", "medium"),
            dueDate = obj.optString("due_date").takeIf { it.isNotEmpty() },
            tags = tags, parentId = obj.optString("parent_id").takeIf { it.isNotEmpty() },
            recurrence = obj.optString("recurrence", "none"),
            createdAt = obj.optString("created_at", ""), updatedAt = obj.optString("updated_at", ""),
            completedAt = obj.optString("completed_at").takeIf { it.isNotEmpty() }, subtasks = subtasks
        )
    }

    private fun parseNote(obj: JSONObject): ai.gizmo.app.model.TrackerNote {
        val tagsArr = obj.optJSONArray("tags")
        val tags = if (tagsArr != null) (0 until tagsArr.length()).map { tagsArr.getString(it) } else emptyList()
        return ai.gizmo.app.model.TrackerNote(
            id = obj.optString("id", ""), title = obj.optString("title", ""),
            content = obj.optString("content", ""), tags = tags,
            pinned = obj.optBoolean("pinned", false),
            createdAt = obj.optString("created_at", ""), updatedAt = obj.optString("updated_at", "")
        )
    }

    suspend fun getTasks(status: String? = null, priority: String? = null, tag: String? = null): List<ai.gizmo.app.model.TrackerTask> = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf<String>()
            if (status != null) params.add("status=$status")
            if (priority != null) params.add("priority=$priority")
            if (tag != null) params.add("tag=${Uri.encode(tag)}")
            val qs = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
            val request = Request.Builder().url("$baseUrl/api/tracker/tasks$qs").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val obj = JSONObject(body)
            val arr = obj.getJSONArray("tasks")
            (0 until arr.length()).map { parseTask(arr.getJSONObject(it)) }
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "getTasks: ${e.message}"); emptyList() }
    }

    suspend fun getTask(id: String): ai.gizmo.app.model.TrackerTask? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/tracker/tasks/$id").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            parseTask(JSONObject(body).getJSONObject("task"))
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "getTask: ${e.message}"); null }
    }

    suspend fun createTask(title: String, priority: String = "medium", parentId: String? = null): ai.gizmo.app.model.TrackerTask? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("title", title); put("priority", priority)
                if (parentId != null) put("parent_id", parentId)
            }.toString()
            val request = Request.Builder().url("$baseUrl/api/tracker/tasks")
                .post(json.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            parseTask(JSONObject(body).getJSONObject("task"))
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "createTask: ${e.message}"); null }
    }

    suspend fun updateTask(id: String, fields: Map<String, Any>): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(fields).toString()
            val request = Request.Builder().url("$baseUrl/api/tracker/tasks/$id")
                .patch(json.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "updateTask: ${e.message}"); false }
    }

    suspend fun completeTask(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/tracker/tasks/$id/complete")
                .patch("{}".toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "completeTask: ${e.message}"); false }
    }

    suspend fun deleteTask(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/tracker/tasks/$id").delete().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "deleteTask: ${e.message}"); false }
    }

    suspend fun getNotes(tag: String? = null, search: String? = null): List<ai.gizmo.app.model.TrackerNote> = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf<String>()
            if (tag != null) params.add("tag=${Uri.encode(tag)}")
            if (search != null) params.add("search=${Uri.encode(search)}")
            val qs = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
            val request = Request.Builder().url("$baseUrl/api/tracker/notes$qs").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val obj = JSONObject(body)
            val arr = obj.getJSONArray("notes")
            (0 until arr.length()).map { parseNote(arr.getJSONObject(it)) }
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "getNotes: ${e.message}"); emptyList() }
    }

    suspend fun createNote(title: String, content: String = ""): ai.gizmo.app.model.TrackerNote? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply { put("title", title); put("content", content) }.toString()
            val request = Request.Builder().url("$baseUrl/api/tracker/notes")
                .post(json.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            parseNote(JSONObject(body).getJSONObject("note"))
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "createNote: ${e.message}"); null }
    }

    suspend fun updateNote(id: String, fields: Map<String, Any>): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(fields).toString()
            val request = Request.Builder().url("$baseUrl/api/tracker/notes/$id")
                .patch(json.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "updateNote: ${e.message}"); false }
    }

    suspend fun deleteNote(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/tracker/notes/$id").delete().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "deleteNote: ${e.message}"); false }
    }

    suspend fun getTags(): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/tracker/tags").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONObject(body).getJSONArray("tags")
            (0 until arr.length()).map { arr.getString(it) }
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "getTags: ${e.message}"); emptyList() }
    }

    // --- Analytics API ---

    suspend fun getAnalyticsSummary(): ai.gizmo.app.model.AnalyticsSummary = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/analytics/summary").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext ai.gizmo.app.model.AnalyticsSummary()
            val body = response.body?.string() ?: return@withContext ai.gizmo.app.model.AnalyticsSummary()
            val obj = JSONObject(body)
            val provArr = obj.optJSONArray("providers")
            val providers = if (provArr != null) (0 until provArr.length()).map { i ->
                val p = provArr.getJSONObject(i)
                ai.gizmo.app.model.ProviderCost(p.getString("provider"), p.getDouble("input_price_per_1m"), p.getDouble("output_price_per_1m"), p.getDouble("estimated_cost_usd"))
            } else emptyList()
            ai.gizmo.app.model.AnalyticsSummary(
                totalPromptTokens = obj.optLong("total_prompt_tokens"), totalCompletionTokens = obj.optLong("total_completion_tokens"),
                totalTokens = obj.optLong("total_tokens"), totalMessages = obj.optInt("total_messages"),
                totalConversations = obj.optInt("total_conversations"), avgResponseMs = obj.optLong("avg_response_ms"),
                avgContextMs = obj.optLong("avg_context_ms"), estimatedSavingsUsd = obj.optDouble("estimated_savings_usd", 0.0),
                providers = providers
            )
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "getAnalyticsSummary: ${e.message}"); ai.gizmo.app.model.AnalyticsSummary() }
    }

    suspend fun getAnalyticsDaily(days: Int = 30): List<ai.gizmo.app.model.DailyUsage> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/analytics/daily?days=$days").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                ai.gizmo.app.model.DailyUsage(d.getString("date"), d.optLong("prompt_tokens"), d.optLong("completion_tokens"), d.optLong("total_tokens"), d.optInt("messages"))
            }
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "getAnalyticsDaily: ${e.message}"); emptyList() }
    }

    suspend fun getAnalyticsConversations(): List<ai.gizmo.app.model.ConversationUsage> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/analytics/conversations").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                ai.gizmo.app.model.ConversationUsage(c.getString("conversation_id"), c.optString("title", ""), c.optLong("prompt_tokens"), c.optLong("completion_tokens"), c.optLong("total_tokens"), c.optInt("messages"))
            }
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "getAnalyticsConversations: ${e.message}"); emptyList() }
    }

    suspend fun getAnalyticsModes(): List<ai.gizmo.app.model.ModeUsage> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/analytics/modes").build()
            client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                ai.gizmo.app.model.ModeUsage(m.getString("mode"), m.optLong("total_tokens"), m.optInt("messages"))
            }
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "getAnalyticsModes: ${e.message}"); emptyList() }
    }

    // --- Code Playground API ---

    suspend fun runCode(code: String, language: String, timeout: Int = 10, stdin: String = ""): ai.gizmo.app.model.ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("code", code); put("language", language); put("timeout", timeout)
                if (stdin.isNotEmpty()) put("stdin", stdin)
            }.toString()
            val request = Request.Builder().url("$baseUrl/api/run-code")
                .post(json.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return@withContext ai.gizmo.app.model.ExecutionResult()
            val obj = JSONObject(body)
            val filesArr = obj.optJSONArray("output_files")
            val files = if (filesArr != null) {
                (0 until filesArr.length()).map { i ->
                    val f = filesArr.getJSONObject(i)
                    ai.gizmo.app.model.OutputFile(f.getString("filename"), f.getString("url"))
                }
            } else emptyList()
            ai.gizmo.app.model.ExecutionResult(
                stdout = obj.optString("stdout", ""),
                stderr = obj.optString("stderr", ""),
                exitCode = obj.optInt("exit_code", 0),
                timedOut = obj.optBoolean("timed_out", false),
                outputFiles = files
            )
            }
        } catch (e: Exception) { android.util.Log.d("GizmoApi", "runCode: ${e.message}"); ai.gizmo.app.model.ExecutionResult(stderr = "Request failed") }
    }
}
