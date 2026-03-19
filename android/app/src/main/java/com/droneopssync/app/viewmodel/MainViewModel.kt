package com.droneopssync.app.viewmodel

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droneopssync.app.api.ApiClient
import com.droneopssync.app.model.FlightLog
import com.droneopssync.app.model.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

private const val TAG = "MainViewModel"

// Default scan paths — covers DJI Pilot 2, DJI GO 5, and DJI Fly on typical controllers
private val DEFAULT_PATHS = listOf(
    "/storage/emulated/0/DJI/com.dji.industry.pilot/FlightRecord",
    "/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord",
    "/storage/emulated/0/Android/data/com.dji.fly/files/FlightRecord"
)

private const val PREF_SERVER_URL  = "server_url"
private const val PREF_API_KEY     = "api_key"
private const val PREF_LOG_PATHS   = "log_paths"
private const val DEFAULT_SERVER   = ""

class MainViewModel : ViewModel() {

    private val _logs = MutableStateFlow<List<FlightLog>>(emptyList())
    val logs: StateFlow<List<FlightLog>> = _logs

    private val _serverUrl = MutableStateFlow(DEFAULT_SERVER)
    val serverUrl: StateFlow<String> = _serverUrl

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey

    private val _logPathsText = MutableStateFlow(DEFAULT_PATHS.joinToString("\n"))
    val logPathsText: StateFlow<String> = _logPathsText

    private val _statusMessage = MutableStateFlow("Tap SCAN to find flight logs")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading

    private val _serverReachable = MutableStateFlow<Boolean?>(null)
    val serverReachable: StateFlow<Boolean?> = _serverReachable

    /** Detailed connection error for diagnostics (shown below Retry button). */
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError

    fun loadSettings(prefs: SharedPreferences) {
        _serverUrl.value    = prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        _apiKey.value       = prefs.getString(PREF_API_KEY, "") ?: ""
        _logPathsText.value = prefs.getString(PREF_LOG_PATHS, DEFAULT_PATHS.joinToString("\n"))
            ?: DEFAULT_PATHS.joinToString("\n")
    }

    fun saveSettings(
        prefs: SharedPreferences,
        serverUrl: String,
        apiKey: String,
        logPathsText: String
    ) {
        val trimmedUrl = serverUrl.trim()
        val urlChanged = trimmedUrl != _serverUrl.value

        _serverUrl.value    = trimmedUrl
        _apiKey.value       = apiKey.trim()
        _logPathsText.value = logPathsText
        prefs.edit()
            .putString(PREF_SERVER_URL, trimmedUrl)
            .putString(PREF_API_KEY, apiKey.trim())
            .putString(PREF_LOG_PATHS, logPathsText)
            .apply()
        _statusMessage.value = "Settings saved"

        // Invalidate cached Retrofit service so ApiClient picks up the new URL
        if (urlChanged) {
            ApiClient.invalidate()
        }

        // Re-check server health with new settings
        checkServerHealth()
    }

    /**
     * Check server connectivity via GET /api/health.
     * Any non-5xx response (including 404) counts as reachable — it proves
     * the tunnel is up even if the health endpoint isn't wired yet.     */
    fun checkServerHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            _serverReachable.value = null
            _connectionError.value = null
            val url = _serverUrl.value

            if (url.isBlank()) {
                _serverReachable.value = false
                _connectionError.value = "No server URL configured — open Settings"
                return@launch
            }
            if (!url.isValidUrl()) {
                _serverReachable.value = false
                _connectionError.value = "URL must start with http:// or https://"
                _statusMessage.value = "Invalid URL — must start with http:// or https://"
                return@launch
            }

            try {
                Log.d(TAG, "Health check → $url")
                val service = ApiClient.create(url)
                val response = service.health()

                if (response.isSuccessful) {
                    Log.d(TAG, "Health check OK (${response.code()})")
                    _serverReachable.value = true
                    _connectionError.value = null
                    return@launch
                }

                // Any non-5xx response means the server is there and responding
                // (404 = /api/health not wired up yet, 401 = auth required, etc.)
                if (response.code() < 500) {
                    Log.d(TAG, "Health check got ${response.code()} — server is reachable")
                    _serverReachable.value = true
                    _connectionError.value = null
                    return@launch
                }

                // 5xx — server error
                _serverReachable.value = false
                _connectionError.value = "Server returned HTTP ${response.code()}"

            } catch (e: UnknownHostException) {
                Log.e(TAG, "Health check DNS failure", e)
                _serverReachable.value = false
                _connectionError.value = "DNS lookup failed — check the URL or your internet connection"
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "Health check timeout", e)
                _serverReachable.value = false
                _connectionError.value = "Connection timed out — server may be down or URL may be wrong"
            } catch (e: SSLHandshakeException) {
                Log.e(TAG, "Health check TLS handshake failed", e)
                _serverReachable.value = false
                _connectionError.value = "TLS/SSL handshake failed — certificate issue with tunnel"
            } catch (e: SSLException) {
                Log.e(TAG, "Health check SSL error", e)
                _serverReachable.value = false
                _connectionError.value = "SSL error: ${e.message?.take(120)}"
            } catch (e: IOException) {
                Log.e(TAG, "Health check IO error", e)
                _serverReachable.value = false
                _connectionError.value = "Network error: ${e.message?.take(120)}"
            } catch (e: Exception) {
                Log.e(TAG, "Health check unexpected error", e)
                _serverReachable.value = false
                _connectionError.value = "Error: ${e.javaClass.simpleName} — ${e.message?.take(100)}"
            }
        }
    }

    fun scanLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val paths = _logPathsText.value
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val found = mutableListOf<FlightLog>()
            for (pathStr in paths) {
                val dir = File(pathStr)
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles { f ->
                        f.isFile && f.extension.lowercase() in listOf("txt", "log", "csv", "json")
                    }?.forEach { found.add(FlightLog(file = it)) }
                }
            }

            found.sortByDescending { it.file.lastModified() }
            _logs.value = found

            _statusMessage.value = when {
                found.isEmpty() -> "No log files found — check paths in Settings"
                else -> "${found.size} log file(s) found across ${paths.size} path(s)"
            }
        }
    }

    fun uploadAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val pending = _logs.value.filter { it.uploadStatus == UploadStatus.PENDING }
            if (pending.isEmpty()) {
                _statusMessage.value = "Nothing to upload"
                return@launch
            }

            val url = _serverUrl.value
            val key = _apiKey.value
            if (url.isBlank() || key.isBlank()) {
                _statusMessage.value = "Configure DroneOpsCommand URL and API key in Settings"
                return@launch
            }
            if (!url.isValidUrl()) {
                _statusMessage.value = "Invalid URL — must start with http:// or https://"
                return@launch
            }

            _isUploading.value = true
            pending.forEach { setStatus(it, UploadStatus.UPLOADING) }

            try {
                val parts = pending.map { log ->
                    val body = log.file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                    MultipartBody.Part.createFormData("files", log.file.name, body)
                }

                // Retry up to 2 times on transient network failures
                val response = withIoRetry(maxAttempts = 3) {
                    ApiClient.create(url).uploadFlights(key, parts)
                }
                val body = response.body()

                if (response.isSuccessful && body != null) {
                    pending.forEach { setStatus(it, UploadStatus.SYNCED) }

                    _statusMessage.value = buildString {
                        if (body.imported > 0) append("${body.imported} imported")
                        if (body.skipped > 0) {
                            if (isNotEmpty()) append(", ")
                            append("${body.skipped} already on server")
                        }
                        if (body.errors.isNotEmpty()) {
                            if (isNotEmpty()) append(", ")
                            append("${body.errors.size} parse error(s)")
                        }
                        if (isEmpty()) append("Upload complete")
                        val syncedCount = _logs.value.count { it.uploadStatus == UploadStatus.SYNCED }
                        if (syncedCount > 0) append(" — tap Delete to clean up controller")
                    }
                } else {
                    val code = response.code()
                    pending.forEach { setStatus(it, UploadStatus.ERROR) }
                    _statusMessage.value = when (code) {
                        401, 403 -> "Invalid API key — check Settings"
                        404      -> "Upload endpoint not found — update DroneOpsCommand backend"
                        else     -> "Upload failed (HTTP $code)"
                    }
                }
            } catch (e: UnknownHostException) {
                pending.forEach { setStatus(it, UploadStatus.ERROR) }
                _statusMessage.value = "DNS lookup failed — check URL and internet connection"
            } catch (e: SocketTimeoutException) {
                pending.forEach { setStatus(it, UploadStatus.ERROR) }
                _statusMessage.value = "Connection timed out — server may be down"
            } catch (e: SSLException) {
                pending.forEach { setStatus(it, UploadStatus.ERROR) }
                _statusMessage.value = "SSL/TLS error — ${e.message?.take(80)}"
            } catch (e: Exception) {
                pending.forEach { setStatus(it, UploadStatus.ERROR) }
                _statusMessage.value = "Connection error: ${e.message?.take(100)}"
            } finally {
                _isUploading.value = false
            }
        }
    }

    /** Reset ERROR files back to PENDING so user can retry without a full rescan. */
    fun retryFailed() {
        _logs.value = _logs.value.map {
            if (it.uploadStatus == UploadStatus.ERROR) it.copy(uploadStatus = UploadStatus.PENDING)
            else it
        }
        _statusMessage.value = "Error files reset to pending — tap SYNC ALL to retry"
    }

    fun deleteSynced() {
        viewModelScope.launch(Dispatchers.IO) {
            val toDelete = _logs.value.filter { it.uploadStatus == UploadStatus.SYNCED }
            var deleted = 0
            var failed = 0
            for (log in toDelete) {
                if (log.file.delete()) {
                    setStatus(log, UploadStatus.DELETED)
                    deleted++
                } else {
                    failed++
                }
            }
            _statusMessage.value = if (failed == 0) {
                "$deleted file(s) deleted from controller"
            } else {
                "$deleted deleted, $failed could not be removed (may already be gone)"
            }
        }
    }

    private fun setStatus(log: FlightLog, status: UploadStatus) {
        _logs.value = _logs.value.map {
            if (it.file.absolutePath == log.file.absolutePath) it.copy(uploadStatus = status) else it
        }
    }

    /** Retry [block] up to [maxAttempts] times on [IOException], with exponential backoff. */
    private suspend fun <T> withIoRetry(maxAttempts: Int = 3, block: suspend () -> T): T {
        var lastException: IOException? = null
        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "IO attempt $attempt/$maxAttempts failed: ${e.message}")
                if (attempt < maxAttempts) {
                    val backoff = (1L shl (attempt - 1)) * 2000L  // 2s, 4s
                    delay(backoff)
                }
            }
        }
        throw lastException!!
    }

    /** Returns true if this string is a usable http/https URL. */
    private fun String.isValidUrl(): Boolean =
        startsWith("http://") || startsWith("https://")
}
