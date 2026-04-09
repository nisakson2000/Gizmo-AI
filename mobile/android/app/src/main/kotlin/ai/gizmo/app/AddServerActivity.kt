package ai.gizmo.app

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.CycleInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class AddServerActivity : AppCompatActivity() {

    private var connectionTested = false
    private var isOnboarding = false
    private var editServerId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_server)

        isOnboarding = intent.getBooleanExtra("onboarding", false)
        editServerId = intent.getStringExtra("edit_server_id")

        val heading = findViewById<TextView>(R.id.heading)
        val nameInput = findViewById<TextInputEditText>(R.id.nameInput)
        val urlInput = findViewById<TextInputEditText>(R.id.urlInput)
        val httpsInfo = findViewById<TextView>(R.id.httpsInfo)
        val btnTest = findViewById<MaterialButton>(R.id.btnTest)
        val statusArea = findViewById<LinearLayout>(R.id.statusArea)
        val statusProgress = findViewById<ProgressBar>(R.id.statusProgress)
        val statusIcon = findViewById<ImageView>(R.id.statusIcon)
        val statusText = findViewById<TextView>(R.id.statusText)
        val helpLink = findViewById<TextView>(R.id.helpLink)
        val btnSave = findViewById<MaterialButton>(R.id.btnSave)

        if (editServerId != null) {
            heading.text = getString(R.string.edit_server)
            val server = ServerManager(this).getServer(editServerId!!)
            if (server != null) {
                nameInput.setText(server.name)
                urlInput.setText(server.url)
            }
        }

        // Show HTTPS info when URL is HTTP
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val url = s.toString().trim().lowercase()
                val showInfo = url.startsWith("http://") ||
                    (url.isNotEmpty() && !url.startsWith("https://") && !url.startsWith("http"))
                if (showInfo && httpsInfo.visibility != View.VISIBLE) {
                    httpsInfo.visibility = View.VISIBLE
                    httpsInfo.alpha = 0f
                    httpsInfo.animate().alpha(1f).setDuration(150).start()
                } else if (!showInfo && httpsInfo.visibility == View.VISIBLE) {
                    httpsInfo.animate().alpha(0f).setDuration(150).withEndAction {
                        httpsInfo.visibility = View.GONE
                    }.start()
                }
                // Reset connection test on URL change
                connectionTested = false
                updateSaveButton(btnSave)
            }
        })

        btnTest.setOnClickListener {
            val rawUrl = urlInput.text.toString().trim()
            if (rawUrl.isEmpty()) return@setOnClickListener
            val url = normalizeUrl(rawUrl)
            urlInput.setText(url)

            // Show testing state
            statusArea.visibility = View.VISIBLE
            statusProgress.visibility = View.VISIBLE
            statusIcon.visibility = View.GONE
            statusText.text = getString(R.string.testing)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnTest.isEnabled = false

            lifecycleScope.launch {
                val success = testConnection(url)
                btnTest.isEnabled = true

                if (success) {
                    statusProgress.visibility = View.GONE
                    statusIcon.visibility = View.VISIBLE
                    statusIcon.setImageResource(android.R.drawable.ic_input_add)
                    statusIcon.setColorFilter(ContextCompat.getColor(this@AddServerActivity, R.color.success))
                    statusText.text = getString(R.string.connected)
                    statusText.setTextColor(ContextCompat.getColor(this@AddServerActivity, R.color.success))
                    connectionTested = true
                    updateSaveButton(btnSave)
                } else {
                    statusProgress.visibility = View.GONE
                    statusIcon.visibility = View.VISIBLE
                    statusIcon.setImageResource(android.R.drawable.ic_delete)
                    statusIcon.setColorFilter(ContextCompat.getColor(this@AddServerActivity, R.color.error))
                    statusText.text = getString(R.string.connection_failed)
                    statusText.setTextColor(ContextCompat.getColor(this@AddServerActivity, R.color.error))
                    // Shake the status area
                    ObjectAnimator.ofFloat(statusArea, "translationX", 0f, 10f, -10f, 10f, -10f, 0f).apply {
                        duration = 200
                        interpolator = CycleInterpolator(1f)
                        start()
                    }
                    connectionTested = false
                    updateSaveButton(btnSave)
                }
            }
        }

        helpLink.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.help_title))
                .setMessage(getString(R.string.help_body))
                .setPositiveButton(getString(R.string.ok), null)
                .show()
        }

        btnSave.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val url = normalizeUrl(urlInput.text.toString().trim())

            if (name.isEmpty() || url.isEmpty()) return@setOnClickListener

            val manager = ServerManager(this)
            val server: Server

            if (editServerId != null) {
                manager.updateServer(editServerId!!, name, url)
                server = manager.getServer(editServerId!!) ?: return@setOnClickListener
            } else {
                server = Server(name = name, url = url)
                manager.addServer(server)
            }

            if (isOnboarding) {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    putExtra("server_id", server.id)
                    putExtra("server_url", server.url)
                    putExtra("server_name", server.name)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            } else {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun updateSaveButton(btn: MaterialButton) {
        btn.isEnabled = connectionTested
        btn.animate().alpha(if (connectionTested) 1f else 0.3f).setDuration(150).start()
    }

    private fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (url.isNotEmpty() && !url.contains("://")) {
            url = "https://$url"
        }
        return url.trimEnd('/')
    }

    private suspend fun testConnection(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"

            // Accept self-signed certs for LAN
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
