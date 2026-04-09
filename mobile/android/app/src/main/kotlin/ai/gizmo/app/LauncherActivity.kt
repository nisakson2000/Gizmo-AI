package ai.gizmo.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val manager = ServerManager(this)

        if (manager.isFirstLaunch()) {
            manager.importDefaults()
            manager.setFirstLaunchDone()
        }

        val servers = manager.getServers()
        val intent = when {
            servers.isEmpty() -> Intent(this, OnboardingActivity::class.java)
            servers.size == 1 -> Intent(this, MainActivity::class.java).putServerExtras(servers[0])
            else -> Intent(this, ServerListActivity::class.java)
        }

        startActivity(intent)
        finish()
    }
}
