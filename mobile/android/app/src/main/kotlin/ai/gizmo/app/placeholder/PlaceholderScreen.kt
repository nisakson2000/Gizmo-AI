package ai.gizmo.app.placeholder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.gizmo.app.R
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextSecondary

@Composable
fun PlaceholderScreen(tabName: String, modifier: Modifier = Modifier) {
    val icon = when (tabName) {
        "Tasks" -> Icons.AutoMirrored.Filled.Assignment
        "Code" -> Icons.Default.Code
        "Stats" -> Icons.Default.BarChart
        else -> Icons.Default.Code
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tabName,
            tint = TextDim,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = tabName, color = TextSecondary, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.coming_soon),
            color = TextDim,
            fontSize = 14.sp
        )
    }
}
