package ai.gizmo.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import ai.gizmo.app.R
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.BgSecondary
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary

data class NavTab(
    val labelRes: Int,
    val icon: ImageVector,
    val index: Int
)

val navTabs = listOf(
    NavTab(R.string.tab_chat, Icons.AutoMirrored.Filled.Chat, 0),
    NavTab(R.string.tab_tasks, Icons.AutoMirrored.Filled.Assignment, 1),
    NavTab(R.string.tab_code, Icons.Default.Code, 2),
    NavTab(R.string.tab_stats, Icons.Default.BarChart, 3)
)

@Composable
fun BottomNav(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    if (imeBottom > 0) return // Hide when keyboard is open

    NavigationBar(containerColor = BgSecondary) {
        navTabs.forEach { tab ->
            val selected = selectedTab == tab.index
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab.index) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = stringResource(tab.labelRes)
                    )
                },
                label = { Text(stringResource(tab.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Accent,
                    selectedTextColor = Accent,
                    unselectedIconColor = TextDim,
                    unselectedTextColor = TextDim,
                    indicatorColor = BgSecondary
                )
            )
        }
    }
}
