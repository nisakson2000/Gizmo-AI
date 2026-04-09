package ai.gizmo.app

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var loadingOverlay: LinearLayout

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private var serverUrl: String = ""
    private var serverId: String = ""
    private var serverName: String = ""

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data?.clipData != null) {
                Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
            } else if (data?.data != null) {
                arrayOf(data.data!!)
            } else {
                null
            }
        } else {
            null
        }
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingPermissionRequest?.let { req ->
            if (granted) {
                req.grant(req.resources)
            } else {
                req.deny()
            }
            pendingPermissionRequest = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        // Apply system bar insets to webview
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // Read server info from intent
        serverUrl = intent.getStringExtra("server_url") ?: ""
        serverId = intent.getStringExtra("server_id") ?: ""
        serverName = intent.getStringExtra("server_name") ?: ""

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

        setupWebView()
        setupSwipeRefresh()
        setupBackHandler()

        // Health check then load
        lifecycleScope.launch {
            val healthy = checkHealth(serverUrl)
            if (healthy) {
                webView.loadUrl(serverUrl)
            } else {
                launchError()
            }
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            userAgentString = "$userAgentString GizmoAI/1.0"
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(GizmoBridge(this), "GizmoBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                // Crossfade out loading overlay
                if (loadingOverlay.alpha > 0f) {
                    loadingOverlay.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction { loadingOverlay.visibility = android.view.View.GONE }
                        .start()
                }
                // Inject blob download bridge
                injectBlobDownloadBridge()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    launchError()
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                // Accept self-signed certs for LAN access
                handler.proceed()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Keep navigation within our server
                if (url.startsWith(serverUrl)) return false
                // Open external links in browser
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) { }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = android.view.View.VISIBLE
                    progressBar.setProgressCompat(newProgress, true)
                } else {
                    progressBar.setProgressCompat(100, true)
                    progressBar.postDelayed({
                        progressBar.visibility = android.view.View.GONE
                    }, 200)
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = callback
                try {
                    val intent = params.createIntent()
                    fileChooserLauncher.launch(intent)
                } catch (_: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val resources = request.resources
                if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    pendingPermissionRequest = request
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    request.deny()
                }
            }
        }

        webView.setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, contentLength ->
            if (url.startsWith("blob:")) return@DownloadListener // Handled by JS bridge

            try {
                val request = DownloadManager.Request(Uri.parse(url))
                val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                request.setTitle(filename)
                request.setMimeType(mimeType)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)

                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
            } catch (_: Exception) { }
        })
    }

    private fun injectBlobDownloadBridge() {
        val js = """
            (function() {
                if (window._gizmoBlobBridgeInstalled) return;
                window._gizmoBlobBridgeInstalled = true;

                document.addEventListener('click', function(e) {
                    var el = e.target.closest('a[href^="blob:"]');
                    if (!el) return;
                    e.preventDefault();

                    var url = el.href;
                    var filename = el.download || 'download';

                    fetch(url).then(function(r) { return r.blob(); }).then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1];
                            GizmoBridge.saveBase64(filename, blob.type || 'application/octet-stream', base64);
                        };
                        reader.readAsDataURL(blob);
                    });
                }, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(getColor(R.color.accent))
        swipeRefresh.setProgressBackgroundColorSchemeColor(getColor(R.color.bg_secondary))
        swipeRefresh.setOnRefreshListener { webView.reload() }

        // Only enable swipe refresh when WebView is scrolled to top
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            swipeRefresh.isEnabled = scrollY == 0
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else if (ServerManager(this@MainActivity).getServers().size > 1) {
                    startActivity(Intent(this@MainActivity, ServerListActivity::class.java))
                    finish()
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setMessage(getString(R.string.exit_title))
                        .setPositiveButton(getString(R.string.exit)) { _, _ -> finish() }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                }
            }
        })
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

    private fun launchError() {
        startActivity(Intent(this, ErrorActivity::class.java).apply {
            putExtra("server_id", serverId)
            putExtra("server_url", serverUrl)
            putExtra("server_name", serverName)
        })
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }
}
