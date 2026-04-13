package ai.gizmo.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import ai.gizmo.app.chat.ChatScreen
import ai.gizmo.app.model.ChatViewModel
import ai.gizmo.app.model.ChatViewModelFactory
import ai.gizmo.app.ui.theme.Accent
import ai.gizmo.app.ui.theme.BgPrimary
import ai.gizmo.app.ui.theme.GizmoTheme
import ai.gizmo.app.ui.theme.ThemeManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var isLoading by mutableStateOf(true)
    private var chatViewModel: ChatViewModel? = null
    private var switchServerCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.loadTheme(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        var serverUrl = intent.getStringExtra(Server.EXTRA_URL) ?: ""
        var serverId = intent.getStringExtra(Server.EXTRA_ID) ?: ""
        var serverName = intent.getStringExtra(Server.EXTRA_NAME) ?: ""

        if (serverUrl.isEmpty()) {
            val default = ServerManager(this).getDefault()
            if (default != null) {
                serverUrl = default.url
                serverId = default.id
                serverName = default.name
            } else {
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }
        }

        val finalUrl = serverUrl
        val finalId = serverId
        val finalName = serverName

        switchServerCallback = {
            startActivity(Intent(this, ServerListActivity::class.java))
            finish()
        }

        // Show UI immediately — loading indicator first, then chat when ready
        setContent {
            GizmoTheme {
                val vm = chatViewModel
                if (isLoading || vm == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                } else {
                    ChatScreen(viewModel = vm, onSwitchServer = { switchServerCallback?.invoke() })
                }
            }
        }

        lifecycleScope.launch {
            val healthy = checkServerHealth(finalUrl)
            if (healthy) {
                chatViewModel = ViewModelProvider(
                    this@MainActivity,
                    ChatViewModelFactory(finalUrl, finalId, finalName)
                )[ChatViewModel::class.java]
                isLoading = false
            } else {
                startActivity(Intent(this@MainActivity, ErrorActivity::class.java).apply {
                    putExtra(Server.EXTRA_ID, finalId)
                    putExtra(Server.EXTRA_URL, finalUrl)
                    putExtra(Server.EXTRA_NAME, finalName)
                })
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                finish()
            }
        }
    }
}
