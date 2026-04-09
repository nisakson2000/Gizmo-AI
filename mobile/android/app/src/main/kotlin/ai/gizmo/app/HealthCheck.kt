package ai.gizmo.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private val trustAllFactory by lazy {
    val trustAll = arrayOf(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })
    val sc = SSLContext.getInstance("TLS")
    sc.init(null, trustAll, java.security.SecureRandom())
    sc.socketFactory
}

private val trustAllVerifier = HostnameVerifier { _, _ -> true }

suspend fun checkServerHealth(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val url = URL("$baseUrl/health")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = trustAllFactory
            conn.hostnameVerifier = trustAllVerifier
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
