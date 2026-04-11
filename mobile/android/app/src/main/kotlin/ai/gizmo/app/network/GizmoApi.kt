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
        private val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }

        val client: OkHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val baseUrl = serverUrl.trimEnd('/')

    suspend fun getConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/conversations").build()
            val response = client.newCall(request).execute()
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getConversation(id: String): List<Message>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/conversations/$id").build()
            val response = client.newCall(request).execute()
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
        } catch (_: Exception) {
            null
        }
    }

    suspend fun deleteConversation(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/conversations/$id")
                .delete()
                .build()
            client.newCall(request).execute().isSuccessful
        } catch (_: Exception) {
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
            client.newCall(request).execute().isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    suspend fun searchConversations(query: String): List<Conversation> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/conversations/search?q=${Uri.encode(query)}"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
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
        } catch (_: Exception) {
            emptyList()
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

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val respBody = response.body?.string() ?: return@withContext null
                JSONObject(respBody).optString("data_url").takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
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

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val respBody = response.body?.string() ?: return@withContext null
                val obj = JSONObject(respBody)
                val name = obj.optString("filename", "document")
                val content = obj.optString("content", "")
                if (content.isEmpty()) null else Pair(name, content)
            } catch (_: Exception) {
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
                client.newCall(request).execute().isSuccessful
            } catch (_: Exception) {
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
                val response = client.newCall(request).execute()
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
            } catch (_: Exception) {
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
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val respBody = response.body?.string() ?: return@withContext null
                JSONObject(respBody).optString("text").takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

    suspend fun exportConversation(conversationId: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/conversations/$conversationId/export?format=markdown")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            response.body?.string()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun downloadFile(url: String): Pair<String, ByteArray>? = withContext(Dispatchers.IO) {
        try {
            val fullUrl = if (url.startsWith("/")) "$baseUrl$url" else url
            val request = Request.Builder().url(fullUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val bytes = response.body?.bytes() ?: return@withContext null
            val disposition = response.header("Content-Disposition")
            val filename = disposition?.substringAfter("filename=", "")
                ?.trim('"')?.takeIf { it.isNotEmpty() }
                ?: fullUrl.substringAfterLast("/")
            Pair(filename, bytes)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getVoices(): List<ai.gizmo.app.model.Voice> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/voices").build()
            val response = client.newCall(request).execute()
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
        } catch (_: Exception) {
            emptyList()
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
                client.newCall(request).execute().isSuccessful
            } catch (_: Exception) {
                false
            }
        }

    suspend fun deleteVoice(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/voices/$id").delete().build()
            client.newCall(request).execute().isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    suspend fun previewVoice(id: String, text: String, cacheDir: java.io.File): String? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().put("text", text).toString()
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$baseUrl/api/voices/$id/preview").post(body).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val bytes = response.body?.bytes() ?: return@withContext null
            val file = java.io.File(cacheDir, "preview_$id.wav")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    // Mode Editor endpoints
    suspend fun getModeDetail(name: String): ai.gizmo.app.model.ModeDetail? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/modes/$name").build()
            val response = client.newCall(request).execute()
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
        } catch (_: Exception) { null }
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
                client.newCall(request).execute().isSuccessful
            } catch (_: Exception) { false }
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
                client.newCall(request).execute().isSuccessful
            } catch (_: Exception) { false }
        }

    suspend fun deleteMode(name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/modes/$name").delete().build()
            client.newCall(request).execute().isSuccessful
        } catch (_: Exception) { false }
    }

    // Memory Manager endpoints
    suspend fun getMemories(subdir: String? = null): List<ai.gizmo.app.model.MemoryFile> = withContext(Dispatchers.IO) {
        try {
            val url = if (subdir != null) "$baseUrl/api/memory/list?subdir=$subdir"
                      else "$baseUrl/api/memory/list"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
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
        } catch (_: Exception) { emptyList() }
    }

    suspend fun readMemory(filename: String, subdir: String): ai.gizmo.app.model.MemoryContent? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/memory/read?filename=${Uri.encode(filename)}&subdir=${Uri.encode(subdir)}"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val obj = JSONObject(body)
            ai.gizmo.app.model.MemoryContent(
                filename = obj.optString("filename", filename),
                subdir = obj.optString("subdir", subdir),
                content = obj.optString("content", "")
            )
        } catch (_: Exception) { null }
    }

    suspend fun writeMemory(filename: String, content: String, subdir: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = okhttp3.FormBody.Builder()
                .add("filename", filename)
                .add("content", content)
                .add("subdir", subdir)
                .build()
            val request = Request.Builder().url("$baseUrl/api/memory/write").post(body).build()
            client.newCall(request).execute().isSuccessful
        } catch (_: Exception) { false }
    }

    suspend fun deleteMemory(subdir: String, filename: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/memory/${Uri.encode(subdir)}/${Uri.encode(filename)}")
                .delete().build()
            client.newCall(request).execute().isSuccessful
        } catch (_: Exception) { false }
    }

    suspend fun clearMemories(): Int = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/memory/clear").delete().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext 0
            val body = response.body?.string() ?: return@withContext 0
            JSONObject(body).optInt("deleted", 0)
        } catch (_: Exception) { 0 }
    }

    suspend fun getModes(): List<Mode> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/modes").build()
            val response = client.newCall(request).execute()
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getServiceHealth(): List<ServiceHealth> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/services/health").build()
            val response = client.newCall(request).execute()
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
        } catch (_: Exception) {
            emptyList()
        }
    }
}
