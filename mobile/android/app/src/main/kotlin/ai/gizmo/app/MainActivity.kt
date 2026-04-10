package ai.gizmo.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import ai.gizmo.app.chat.ChatScreen
import ai.gizmo.app.model.ChatViewModel
import ai.gizmo.app.model.ChatViewModelFactory
import ai.gizmo.app.ui.theme.GizmoTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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

        lifecycleScope.launch {
            val healthy = checkServerHealth(finalUrl)
            if (healthy) {
                val viewModel = ViewModelProvider(
                    this@MainActivity,
                    ChatViewModelFactory(finalUrl, finalId, finalName)
                )[ChatViewModel::class.java]

                setContent {
                    GizmoTheme {
                        ChatScreen(
                            viewModel = viewModel,
                            onSwitchServer = {
                                startActivity(Intent(this@MainActivity, ServerListActivity::class.java))
                                finish()
                            }
                        )
                    }
                }
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
