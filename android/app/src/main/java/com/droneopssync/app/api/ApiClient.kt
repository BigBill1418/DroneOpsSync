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

    private fun normalizeUrl(url: String): String {
        var base = url.trim()
        if (base.contains("://")) {
            val pathStart = base.indexOf('/', base.indexOf("://") + 3)
            if (pathStart > 0) base = base.substring(0, pathStart)
        }
        if (!base.endsWith("/")) base = "$base/"
        return base
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
