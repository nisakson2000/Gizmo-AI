package ai.gizmo.app.chat

import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage

private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg")
private val VIDEO_EXTENSIONS = listOf(".mp4", ".webm", ".mov", ".avi", ".mkv")

/** Reusable full-screen dialog properties for all overlay screens. */
val FullScreenDialogProperties = androidx.compose.ui.window.DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false
)

/** Resolve a potentially relative URL (starting with /) against a server base URL. */
fun resolveMediaUrl(url: String, serverUrl: String): String =
    if (url.startsWith("/")) "$serverUrl$url" else url

@Composable
fun MediaViewerDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lowerUrl = url.lowercase()
    val isImage = IMAGE_EXTENSIONS.any { lowerUrl.contains(it) } ||
        lowerUrl.contains("/api/media/") && !VIDEO_EXTENSIONS.any { lowerUrl.contains(it) }
    val isVideo = VIDEO_EXTENSIONS.any { lowerUrl.contains(it) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = FullScreenDialogProperties
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            when {
                isImage -> ImageViewer(url = url)
                isVideo -> VideoViewer(url = url)
                else -> {
                    LaunchedEffect(url) {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) { }
                        onDismiss()
                    }
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(28.dp))
            }

            IconButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) { }
                },
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open externally", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ImageViewer(url: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    AsyncImage(
        model = url,
        contentDescription = "Full size image",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                    offset = Offset(x = offset.x + pan.x, y = offset.y + pan.y)
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
    )
}

@Composable
private fun VideoViewer(url: String) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(url)
                setOnPreparedListener { it.isLooping = true; start() }
                setOnErrorListener { _, _, _ -> true }
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier.fillMaxSize()
    )
}
