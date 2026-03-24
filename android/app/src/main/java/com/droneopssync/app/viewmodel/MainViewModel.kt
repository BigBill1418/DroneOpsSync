package com.droneopssync.app.viewmodel

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droneopssync.app.api.ApiClient
import com.droneopssync.app.model.FlightLog
import com.droneopssync.app.model.UploadStatus
import kotlinx.coroutines.Dispatchers
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

private const val TAG = "MainViewModel"

// Scan paths for each controller/device in this fleet:
//   DJI RC 2          → Android/data/dji.go.v5/files/FlightRecord
//   DJI RC Pro        → Android/data/dji.go.v5/files/FlightRecord
//   Bill's S25 Ultra  → Android/data/dji.go.v5/files/FlightRecord
//   DJI RC Plus       → DJI/com.dji.industry.pilot/FlightRecord
//   DJI RC Plus 2     → DJI/com.dji.industry.pilot/FlightRecord
private val DEFAULT_PATHS = listOf(
    "/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord",
    "/storage/emulated/0/DJI/com.dji.industry.pilot/FlightRecord"
)

private const val PREF_SERVER_URL = "server_url"
private const val PREF_API_KEY    = "api_key"
private const val PREF_LOG_PATHS  = "log_paths"
private const val DEFAULT_SERVER  = "http://192.168.50.20:3080"

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

        if (urlChanged) ApiClient.invalidate()
        checkServerHealth()
    }

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
                val response = ApiClient.create(url).health()
                _serverReachable.value = response.code() < 500
                if (response.code() >= 500) {
                    _connectionError.value = "Server returned HTTP ${response.code()}"
                }
            } catch (e: UnknownHostException) {
                _serverReachable.value = false
                _connectionError.value = "DNS lookup failed — check the URL"
            } catch (e: SocketTimeoutException) {
                _serverReachable.value = false
                _connectionError.value = "Connection timed out — is the server running?"
            } catch (e: IOException) {
                Log.e(TAG, "Health check failed", e)
                _serverReachable.value = false
                _connectionError.value = "Network error: ${e.message?.take(120)}"
            }
        }
    }

    fun scanLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val paths = _logPathsText.value
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val found         = mutableListOf<FlightLog>()
            val missingPaths  = mutableListOf<String>()
            val matchedPaths  = mutableListOf<String>()
            val validExts     = setOf("txt") // DJI flight records are .txt only

            for (pathStr in paths) {
                val dir = File(pathStr)
                Log.d(TAG, "scanLogs: checking $pathStr → exists=${dir.exists()} isDir=${dir.isDirectory}")
                if (dir.exists() && dir.isDirectory) {
                    matchedPaths += pathStr
                    // Walk all subdirectories — DJI organises records into date sub-folders
                    dir.walkTopDown()
                        .filter { f -> f.isFile && f.extension.lowercase() in validExts }
                        .forEach {
                            Log.d(TAG, "  found: ${it.absolutePath}")
                            found.add(FlightLog(file = it))
                        }
                } else {
                    missingPaths += pathStr
                    Log.w(TAG, "scanLogs: path not found → $pathStr")
                }
            }

            found.sortByDescending { it.file.lastModified() }
            _logs.value = found

            _statusMessage.value = when {
                paths.isEmpty() ->
                    "No scan paths configured — add paths in Settings"
                found.isEmpty() && matchedPaths.isEmpty() ->
                    "None of the ${paths.size} configured path(s) exist on this device — check Settings"
                found.isEmpty() ->
                    "Directories found but no log files inside — DJI app may store logs elsewhere"
                else ->
                    "${found.size} log file(s) found" +
                        if (missingPaths.isNotEmpty()) " (${missingPaths.size} path(s) missing)" else ""
            }
            Log.d(TAG, "scanLogs done: found=${found.size} matched=${matchedPaths.size} missing=${missingPaths.size}")
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
                _statusMessage.value = "Configure server URL and API key in Settings"
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

                val response = ApiClient.create(url).uploadFlights(key, parts)
                val body = response.body()

                Log.d(TAG, "Upload response: HTTP ${response.code()}")
                if (body != null) {
                    Log.d(TAG, "  imported=${body.imported} skipped=${body.skipped} errors=${body.errors}")
                } else {
                    Log.w(TAG, "  response body is null (raw: ${response.errorBody()?.string()?.take(500)})")
                }

                if (response.isSuccessful && body != null) {
                    // Mark files based on what the server actually did
                    if (body.errors.isNotEmpty() && body.imported == 0) {
                        // Server couldn't parse any files
                        pending.forEach { setStatus(it, UploadStatus.ERROR) }
                    } else if (body.errors.isNotEmpty()) {
                        // Partial success — some imported, some failed
                        pending.forEach { setStatus(it, UploadStatus.SYNCED) }
                    } else if (body.imported == 0 && body.skipped > 0) {
                        // All files already on server
                        pending.forEach { setStatus(it, UploadStatus.DUPLICATE) }
                    } else {
                        pending.forEach { setStatus(it, UploadStatus.SYNCED) }
                    }

                    _statusMessage.value = buildString {
                        if (body.imported > 0) append("${body.imported} imported")
                        if (body.skipped > 0) {
                            if (isNotEmpty()) append(", ")
                            append("${body.skipped} already on server")
                        }
                        if (body.errors.isNotEmpty()) {
                            if (isNotEmpty()) append(", ")
                            append("${body.errors.size} parse error(s)")
                            // Show first error for diagnostics
                            append(": ${body.errors.first().take(80)}")
                        }
                        if (isEmpty()) append("Upload sent but server reported 0 imported — check server logs")
                        val syncedCount = _logs.value.count { it.uploadStatus == UploadStatus.SYNCED }
                        if (syncedCount > 0) append(" — tap Delete to clean up controller")
                    }
                } else {
                    pending.forEach { setStatus(it, UploadStatus.ERROR) }
                    _statusMessage.value = when (response.code()) {
                        401, 403 -> "Invalid API key — check Settings"
                        404      -> "Upload endpoint not found — check server URL"
                        else     -> "Upload failed (HTTP ${response.code()})"
                    }
                }
            } catch (e: UnknownHostException) {
                pending.forEach { setStatus(it, UploadStatus.ERROR) }
                _statusMessage.value = "DNS lookup failed — check URL"
            } catch (e: SocketTimeoutException) {
                pending.forEach { setStatus(it, UploadStatus.ERROR) }
                _statusMessage.value = "Connection timed out — server may be down"
            } catch (e: Exception) {
                pending.forEach { setStatus(it, UploadStatus.ERROR) }
                _statusMessage.value = "Connection error: ${e.message?.take(100)}"
            } finally {
                _isUploading.value = false
            }
        }
    }

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

    private fun String.isValidUrl(): Boolean =
        startsWith("http://") || startsWith("https://")
}
