package com.droneopssync.app.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val TAG = "ApiClient"

    private val sharedClient: OkHttpClient by lazy { buildClient() }

    private var cachedBaseUrl: String? = null
    private var cachedService: DroneOpsSyncService? = null

    @Synchronized
    fun create(serverUrl: String): DroneOpsSyncService {
        val base = normalizeUrl(serverUrl)
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

    @Synchronized
    fun invalidate() {
        cachedBaseUrl = null
        cachedService = null
    }

    /**
     * Normalize a user-entered server URL into a Retrofit-safe baseUrl.
     *
     * - Trims whitespace + trailing slashes.
     * - If no scheme is present, prepends `https://` (mirrors companion v2.62.0
     *   `DEFAULT_SERVER_URL` convention).
     * - Strips any path component — Retrofit's baseUrl must be host-only.
     * - Coerces plaintext `http://` on public hostnames to `https://`. LAN
     *   hosts (loopback, RFC-1918, link-local) are preserved on `http://`
     *   because they often lack TLS locally. The public-URL coercion fixes
     *   the class of failure where CloudFlare's 80→443 redirect returns an
     *   HTML body that Gson cannot parse (ADR-0001, ADR-0002 §4; ported from
     *   DroneOpsCommand companion commit 890b875).
     */
    internal fun normalizeUrl(url: String): String {
        var base = url.trim().trimEnd('/')
        if (base.isEmpty()) return "/"

        if (!base.contains("://")) {
            base = "https://$base"
        }

        val schemeEnd = base.indexOf("://") + 3
        val pathStart = base.indexOf('/', schemeEnd)
        if (pathStart > 0) base = base.substring(0, pathStart)

        if (base.startsWith("http://", ignoreCase = true)) {
            val hostPort = base.substring("http://".length)
            val host = hostPort.substringBefore('/').substringBefore(':')
            if (!isPrivateHost(host)) {
                val upgraded = "https://$hostPort"
                Log.w(TAG, "normalizeUrl: coerced plaintext public URL to HTTPS: $base -> $upgraded")
                base = upgraded
            }
        }

        return "$base/"
    }

    /**
     * Returns true if the supplied host is a loopback, RFC-1918 private-range,
     * or link-local address/name that is reasonable to reach over plaintext
     * HTTP on a trusted LAN. Anything else — including public hostnames — is
     * treated as non-private.
     *
     * Important: the caller must pass a *host* already parsed out of the URL
     * (scheme + path stripped). Raw-URL `startsWith("10.")` checks admit
     * strings like `10.example.com` as "private" and must be avoided.
     */
    private fun isPrivateHost(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.equals("localhost", ignoreCase = true)) return true
        if (host == "::1") return true
        if (host == "127.0.0.1") return true
        // Numeric-only IPv4 literals qualify for the RFC-1918 checks; a name
        // like `10.example.com` is rejected because the final octet is not
        // numeric.
        if (!host.matches(Regex("^[0-9.]+$"))) return false
        if (host.startsWith("127.")) return true
        if (host.startsWith("10.")) return true
        if (host.startsWith("192.168.")) return true
        if (host.startsWith("169.254.")) return true
        if (Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)) return true
        return false
    }

    private fun buildClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor { message -> Log.d(TAG, message) }
        logging.level = HttpLoggingInterceptor.Level.HEADERS

        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(logging)
            .build()
    }
}
