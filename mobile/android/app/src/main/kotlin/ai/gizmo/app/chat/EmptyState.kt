package ai.gizmo.app.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.gizmo.app.R
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.BgSecondary
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary
import ai.gizmo.app.ui.theme.TextSecondary

data class Suggestion(
    val labelRes: Int,
    val descRes: Int,
    val promptRes: Int,
    val icon: ImageVector,
    val special: String? = null
)

private val suggestions = listOf(
    Suggestion(R.string.vision, R.string.desc_vision, R.string.suggestion_vision, Icons.Default.Visibility, special = "image"),
    Suggestion(R.string.video, R.string.desc_video, R.string.suggestion_video, Icons.Default.VideoFile, special = "video"),
    Suggestion(R.string.audio, R.string.desc_audio, R.string.suggestion_audio, Icons.Default.AudioFile, special = "audio"),
    Suggestion(R.string.search, R.string.desc_search, R.string.suggestion_search, Icons.Default.Search),
    Suggestion(R.string.reason, R.string.desc_reason, R.string.suggestion_reason, Icons.Default.Psychology),
    Suggestion(R.string.code, R.string.desc_code, R.string.suggestion_code, Icons.Default.Code),
    Suggestion(R.string.voice_studio, R.string.desc_voice_studio, R.string.suggestion_voice_studio, Icons.Default.RecordVoiceOver, special = "voice_studio"),
    Suggestion(R.string.files, R.string.desc_files, R.string.suggestion_files, Icons.Default.Description, special = "document")
)

@Composable
fun EmptyState(
    onSuggestionClick: (String) -> Unit,
    onPickImage: (() -> Unit)? = null,
    onPickVideo: (() -> Unit)? = null,
    onPickAudio: (() -> Unit)? = null,
    onPickDocument: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.app_name),
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.powered_by),
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.fully_local),
            color = TextDim,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(32.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(suggestions) { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    onClick = { prompt ->
                        when (suggestion.special) {
                            "image" -> onPickImage?.invoke() ?: onSuggestionClick(prompt)
                            "video" -> onPickVideo?.invoke() ?: onSuggestionClick(prompt)
                            "audio" -> onPickAudio?.invoke() ?: onSuggestionClick(prompt)
                            "document" -> onPickDocument?.invoke() ?: onSuggestionClick(prompt)
                            "voice_studio" -> Toast.makeText(context, context.getString(R.string.coming_soon), Toast.LENGTH_SHORT).show()
                            else -> onSuggestionClick(prompt)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: Suggestion, onClick: (String) -> Unit) {
    val prompt = stringResource(suggestion.promptRes)
    Surface(
        onClick = { onClick(prompt) },
        shape = RoundedCornerShape(12.dp),
        color = BgSecondary
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = suggestion.icon,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(suggestion.labelRes),
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(suggestion.descRes),
                color = TextDim,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp
            )
        }
    }
}
