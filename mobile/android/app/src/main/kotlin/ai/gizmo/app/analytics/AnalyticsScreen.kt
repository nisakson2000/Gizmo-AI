package ai.gizmo.app.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.gizmo.app.model.AnalyticsSummary
import ai.gizmo.app.model.ConversationUsage
import ai.gizmo.app.model.DailyUsage
import ai.gizmo.app.model.ModeUsage
import ai.gizmo.app.model.formatTokens
import ai.gizmo.app.network.GizmoApi
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.AccentDim
import ai.gizmo.app.ui.theme.BgPrimary
import ai.gizmo.app.ui.theme.BgSecondary
import ai.gizmo.app.ui.theme.BgTertiary
import ai.gizmo.app.ui.theme.Border
import ai.gizmo.app.ui.theme.ErrorColor
import ai.gizmo.app.ui.theme.Success
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary
import ai.gizmo.app.ui.theme.TextSecondary
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
fun AnalyticsScreen(api: GizmoApi, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(AnalyticsSummary()) }
    var dailyData by remember { mutableStateOf<List<DailyUsage>>(emptyList()) }
    var topConvos by remember { mutableStateOf<List<ConversationUsage>>(emptyList()) }
    var modeUsage by remember { mutableStateOf<List<ModeUsage>>(emptyList()) }
    var selectedDays by remember { mutableIntStateOf(30) }

    LaunchedEffect(Unit) {
        val s = scope.async { api.getAnalyticsSummary() }
        val c = scope.async { api.getAnalyticsConversations() }
        val m = scope.async { api.getAnalyticsModes() }
        summary = s.await(); topConvos = c.await(); modeUsage = m.await()
    }

    LaunchedEffect(selectedDays) {
        dailyData = api.getAnalyticsDaily(selectedDays)
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // Summary cards
        item {
            Text("Analytics", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryCard("Total Tokens", formatTokens(summary.totalTokens), "${formatTokens(summary.totalPromptTokens)} in / ${formatTokens(summary.totalCompletionTokens)} out", modifier = Modifier.weight(1f))
                SummaryCard("Messages", "${summary.totalMessages}", "${summary.totalConversations} conversations", modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val avgText = if (summary.avgResponseMs > 1000) "%.1fs".format(summary.avgResponseMs / 1000.0) else "${summary.avgResponseMs}ms"
                SummaryCard("Avg Response", avgText, "+${summary.avgContextMs}ms context", modifier = Modifier.weight(1f))
                val savings = summary.providers.maxByOrNull { it.estimatedCostUsd }?.estimatedCostUsd ?: 0.0
                SummaryCard("Cloud Equiv.", "$%.2f".format(savings), "vs Claude Opus 4", modifier = Modifier.weight(1f), valueColor = Success)
            }
        }

        // Time range selector
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader("Daily Token Usage")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                listOf(7, 30, 90).forEach { d ->
                    FilterChip(selected = selectedDays == d, onClick = { selectedDays = d },
                        label = { Text("${d}d", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = BgPrimary, containerColor = BgTertiary, labelColor = TextPrimary))
                }
            }
        }

        // Bar chart
        item {
            if (dailyData.isNotEmpty()) {
                TokenBarChart(dailyData, modifier = Modifier.fillMaxWidth().height(160.dp).padding(vertical = 8.dp))
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text("No data", color = TextDim)
                }
            }
        }

        // Cloud cost comparison
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader("Cloud Cost Comparison")
            val sorted = summary.providers.sortedByDescending { it.estimatedCostUsd }
            val maxCost = sorted.firstOrNull()?.estimatedCostUsd ?: 1.0
            sorted.forEach { provider ->
                val pct = provider.estimatedCostUsd / maxCost
                val barColor = when { pct > 0.8 -> ErrorColor; pct > 0.3 -> Accent; else -> Success }
                BarRow(provider.provider, "$%.2f".format(provider.estimatedCostUsd), pct.toFloat(), barColor)
            }
            BarRow("Your cost (local)", "$0.00", 0f, Success)
        }

        // Top conversations
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader("Top Conversations")
        }
        val maxConvoTokens = topConvos.maxOfOrNull { it.totalTokens } ?: 1L
        items(topConvos.take(20)) { conv ->
            BarRow(conv.title.take(30), formatTokens(conv.totalTokens), (conv.totalTokens.toFloat() / maxConvoTokens), Accent)
        }

        // Mode usage
        item {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader("Usage by Mode")
        }
        val maxModeTokens = modeUsage.maxOfOrNull { it.totalTokens } ?: 1L
        items(modeUsage) { mu ->
            BarRow(mu.mode.replaceFirstChar { it.uppercase() }, "${formatTokens(mu.totalTokens)} / ${mu.messages} msgs", (mu.totalTokens.toFloat() / maxModeTokens), Accent)
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier, valueColor: androidx.compose.ui.graphics.Color = TextPrimary) {
    Surface(shape = RoundedCornerShape(12.dp), color = BgSecondary, modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, color = TextDim, fontSize = 11.sp)
            Text(value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun BarRow(label: String, value: String, fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.4f))
        Box(modifier = Modifier.weight(0.4f).height(12.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color.copy(alpha = 0.15f), size = size)
                if (fraction > 0) drawRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(value, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(0.2f))
    }
}

@Composable
private fun TokenBarChart(data: List<DailyUsage>, modifier: Modifier = Modifier) {
    val accentColor = Accent
    val accentDimColor = AccentDim
    val maxTokens = data.maxOfOrNull { it.totalTokens }?.toFloat()?.coerceAtLeast(1f) ?: 1f

    Canvas(modifier = modifier) {
        val barWidth = size.width / data.size.coerceAtLeast(1) * 0.7f
        val gap = size.width / data.size.coerceAtLeast(1) * 0.3f

        data.forEachIndexed { i, day ->
            val x = i * (barWidth + gap) + gap / 2
            val promptH = (day.promptTokens.toFloat() / maxTokens) * size.height
            val compH = (day.completionTokens.toFloat() / maxTokens) * size.height

            // Prompt bar (bottom, dark accent)
            drawRect(accentDimColor, topLeft = Offset(x, size.height - promptH - compH), size = Size(barWidth, promptH))
            // Completion bar (top, accent at 40%)
            drawRect(accentColor.copy(alpha = 0.4f), topLeft = Offset(x, size.height - compH), size = Size(barWidth, compH))
        }
    }
}
