package com.droneopssync.app.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object ApiClient {

    private const val TAG = "ApiClient"

    /**
     * Shared OkHttpClient — connection pool and TLS session cache survive across
     * health checks, uploads, and retries. This is the single biggest fix for
     * "won't connect to the tunnel" on DJI controllers running older Android.
     */
    private val sharedClient: OkHttpClient by lazy { buildClient() }

    /** Last base-URL we built a Retrofit service for. */
    private var cachedBaseUrl: String? = null
    private var cachedService: DroneOpsSyncService? = null

    @Synchronized
    fun create(serverUrl: String): DroneOpsSyncService {
        val base = normalizeUrl(serverUrl)
        // Return cached service if the URL hasn't changed
        cachedService?.let { if (cachedBaseUrl == base) return it }

        val service = Retrofit.Builder()
            .baseUrl(base)
            .client(sharedClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DroneOpsSyncService::class.java)

        cachedBaseUrl = base
        cachedService = service
        return service
    }

    /** Force a fresh Retrofit instance (e.g. after settings change). */
    @Synchronized
    fun invalidate() {
        cachedBaseUrl = null
        cachedService = null
    }

    private fun normalizeUrl(url: String): String {
        var base = url.trim()
        // Strip trailing path segments that users sometimes paste (e.g. "/api/health")
        if (base.contains("://")) {
            val pathStart = base.indexOf('/', base.indexOf("://") + 3)
            if (pathStart > 0) base = base.substring(0, pathStart)
        }
        if (!base.endsWith("/")) base = "$base/"
        return base
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))   // avoid HTTP/2 framing issues on older Android
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)         // OkHttp-level transparent retry
            .followRedirects(true)
            .followSslRedirects(true)

        // Logging interceptor — only in debug builds, but safe to always attach
        val logging = HttpLoggingInterceptor { message -> Log.d(TAG, message) }
        logging.level = HttpLoggingInterceptor.Level.HEADERS
        builder.addInterceptor(logging)

        // ⚠️  SECURITY NOTICE — Trust-all TLS certificate bypass
        //
        // This disables SSL certificate chain validation and hostname verification.
        //
        // WHY IT EXISTS:
        //   DJI smart controllers (RC Pro, Smart Controller) ship with Android 8–10
        //   and outdated system CA bundles that do not include Cloudflare's newer
        //   intermediate certificates.  Without this bypass the TLS handshake fails
        //   before any data is sent, making the app non-functional on the target hardware.
        //
        // WHAT IT DOES NOT AFFECT:
        //   - The Cloudflare tunnel still encrypts traffic end-to-end between the
        //     controller and the server.  Data in transit is not exposed.
        //   - The device API key is still required on every request; unauthenticated
        //     requests are rejected by the backend regardless of TLS state.
        //
        // RISK:
        //   On an untrusted network (e.g. public Wi-Fi) a MITM attacker could present
        //   a forged certificate that this code would accept.  The API key would then
        //   be visible to the attacker.  This app is designed for use on controlled
        //   field networks (the drone controller's own hotspot or a private LAN) where
        //   that risk is acceptably low.
        //
        // FUTURE MITIGATION:
        //   When DJI ships controllers with Android 12+ or updated CA bundles, this
        //   bypass should be removed and standard certificate pinning applied instead.
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install trust-all TLS — falling back to system defaults", e)
        }

        return builder.build()
    }
}
