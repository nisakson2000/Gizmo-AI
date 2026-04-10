package ai.gizmo.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import ai.gizmo.app.model.ConnectionState
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.ErrorColor
import ai.gizmo.app.ui.theme.Success

@Composable
fun ConnectionIndicator(state: ConnectionState, modifier: Modifier = Modifier) {
    val color = when (state) {
        ConnectionState.CONNECTED -> Success
        ConnectionState.GENERATING, ConnectionState.CONNECTING -> Accent
        ConnectionState.DISCONNECTED -> ErrorColor
    }

    val shouldPulse = state == ConnectionState.GENERATING || state == ConnectionState.CONNECTING

    if (shouldPulse) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
        Box(
            modifier = modifier
                .size(8.dp)
                .alpha(alpha)
                .background(color, CircleShape)
        )
    } else {
        Box(
            modifier = modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
    }
}
