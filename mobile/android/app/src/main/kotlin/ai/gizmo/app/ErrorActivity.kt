package ai.gizmo.app

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.CycleInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class ErrorActivity : AppCompatActivity() {

    private var serverUrl: String = ""
    private var serverId: String = ""
    private var serverName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error)

        serverUrl = intent.getStringExtra("server_url") ?: ""
        serverId = intent.getStringExtra("server_id") ?: ""
        serverName = intent.getStringExtra("server_name") ?: ""

        val serverInfo = findViewById<TextView>(R.id.serverInfo)
        val btnRetry = findViewById<MaterialButton>(R.id.btnRetry)
        val btnVpn = findViewById<MaterialButton>(R.id.btnVpn)
        val btnSwitch = findViewById<MaterialButton>(R.id.btnSwitch)

        serverInfo.text = if (serverName.isNotEmpty()) "$serverName\n$serverUrl" else serverUrl

        btnRetry.setOnClickListener {
            val originalText = btnRetry.text
            btnRetry.text = ""
            btnRetry.isEnabled = false

            // Show inline spinner
            val spinner = ProgressBar(this).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.bg_primary))
            }

            lifecycleScope.launch {
                val healthy = checkHealth(serverUrl)
                btnRetry.isEnabled = true
                btnRetry.text = originalText

                if (healthy) {
                    startActivity(Intent(this@ErrorActivity, MainActivity::class.java).apply {
                        putExtra("server_id", serverId)
                        putExtra("server_url", serverUrl)
                        putExtra("server_name", serverName)
                    })
                    @Suppress("DEPRECATION")
                    overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                    finish()
                } else {
                    // Shake the button
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

        // Hide "Switch Server" if only one server
        if (ServerManager(this).getServers().size <= 1) {
            btnSwitch.visibility = View.GONE
        }
    }

    private suspend fun checkHealth(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn is javax.net.ssl.HttpsURLConnection) {
                val trustAll = arrayOf(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sc = javax.net.ssl.SSLContext.getInstance("TLS")
                sc.init(null, trustAll, java.security.SecureRandom())
                conn.sslSocketFactory = sc.socketFactory
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }

            try {
                val code = conn.responseCode
                if (code != 200) return@withContext false
                val body = conn.inputStream.bufferedReader().readText()
                body.contains("ok", ignoreCase = true)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }
}
