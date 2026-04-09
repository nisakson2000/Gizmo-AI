package ai.gizmo.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast

class GizmoBridge(private val context: Context) {

    @JavascriptInterface
    fun saveBase64(filename: String, mimeType: String, base64Data: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val resolver = context.contentResolver

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Failed to create download entry")

            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(bytes)
            }

            (context as? android.app.Activity)?.runOnUiThread {
                Toast.makeText(context, "Saved to Downloads: $filename", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            (context as? android.app.Activity)?.runOnUiThread {
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
