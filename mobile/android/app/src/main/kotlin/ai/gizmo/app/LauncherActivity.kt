package ai.gizmo.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = ServerManager(this)

        if (manager.isFirstLaunch()) {
            manager.importDefaults()
            manager.setFirstLaunchDone()
        }

        val servers = manager.getServers()
        val intent = when {
            servers.isEmpty() -> Intent(this, OnboardingActivity::class.java)
            servers.size == 1 -> Intent(this, MainActivity::class.java).apply {
                putExtra("server_id", servers[0].id)
                putExtra("server_url", servers[0].url)
                putExtra("server_name", servers[0].name)
            }
            else -> Intent(this, ServerListActivity::class.java)
        }

        startActivity(intent)
        finish()
    }
}
