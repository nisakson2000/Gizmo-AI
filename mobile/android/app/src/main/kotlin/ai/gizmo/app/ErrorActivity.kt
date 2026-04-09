package ai.gizmo.app

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.CycleInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class ErrorActivity : AppCompatActivity() {

    private var serverUrl: String = ""
    private var serverId: String = ""
    private var serverName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error)

        serverUrl = intent.getStringExtra(Server.EXTRA_URL) ?: ""
        serverId = intent.getStringExtra(Server.EXTRA_ID) ?: ""
        serverName = intent.getStringExtra(Server.EXTRA_NAME) ?: ""

        val serverInfo = findViewById<TextView>(R.id.serverInfo)
        val btnRetry = findViewById<MaterialButton>(R.id.btnRetry)
        val btnVpn = findViewById<MaterialButton>(R.id.btnVpn)
        val btnSwitch = findViewById<MaterialButton>(R.id.btnSwitch)

        serverInfo.text = if (serverName.isNotEmpty()) "$serverName\n$serverUrl" else serverUrl

        btnRetry.setOnClickListener {
            val originalText = btnRetry.text
            btnRetry.text = ""
            btnRetry.isEnabled = false

            lifecycleScope.launch {
                val healthy = checkServerHealth(serverUrl)
                btnRetry.isEnabled = true
                btnRetry.text = originalText

                if (healthy) {
                    startActivity(Intent(this@ErrorActivity, MainActivity::class.java).apply {
                        putExtra(Server.EXTRA_ID, serverId)
                        putExtra(Server.EXTRA_URL, serverUrl)
                        putExtra(Server.EXTRA_NAME, serverName)
                    })
                    @Suppress("DEPRECATION")
                    overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                    finish()
                } else {
                    ObjectAnimator.ofFloat(btnRetry, "translationX", 0f, 10f, -10f, 10f, -10f, 0f).apply {
                        duration = 200
                        interpolator = CycleInterpolator(1f)
                        start()
                    }
                }
            }
        }

        btnVpn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }

        btnSwitch.setOnClickListener {
            startActivity(Intent(this, ServerListActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }

        if (ServerManager(this).getServers().size <= 1) {
            btnSwitch.visibility = View.GONE
        }
    }
}
