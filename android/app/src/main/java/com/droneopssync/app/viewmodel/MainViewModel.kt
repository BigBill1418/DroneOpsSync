package com.droneopssync.app.viewmodel

import android.content.SharedPreferences
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
        _serverUrl.value    = serverUrl.trim()
        _apiKey.value       = apiKey.trim()
        _logPathsText.value = logPathsText
        prefs.edit()
            .putString(PREF_SERVER_URL, serverUrl.trim())
            .putString(PREF_API_KEY, apiKey.trim())
            .putString(PREF_LOG_PATHS, logPathsText)
            .apply()
        _statusMessage.value = "Settings saved"
    }

    fun checkServerHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            _serverReachable.value = null
            val url = _serverUrl.value
            if (url.isBlank()) {
                _serverReachable.value = false
                return@launch
            }
            try {
                val response = ApiClient.create(url).health()
                _serverReachable.value = response.isSuccessful
            } catch (e: Exception) {
                _serverReachable.value = false
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

            _isUploading.value = true
            // Mark all pending as uploading
            pending.forEach { setStatus(it, UploadStatus.UPLOADING) }

            try {
                val parts = pending.map { log ->
                    val body = log.file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                    MultipartBody.Part.createFormData("files", log.file.name, body)
                }

                val response = ApiClient.create(url).uploadFlights(key, parts)
                val body = response.body()

                if (response.isSuccessful && body != null) {
                    // Build a set of filenames that had errors
                    val errorNames = body.errors.map { extractFilename(it) }.toSet()

                    pending.forEach { log ->
                        setStatus(log, if (log.name in errorNames) UploadStatus.ERROR else {
                            // If skipped > 0 and not in errors, it's a duplicate
                            // We can't map per-file to imported vs skipped without filename info,
                            // so we use the simpler rule: error → ERROR, else → SYNCED
                            // (server's skipped count means it already had these files)
                            UploadStatus.SYNCED
                        })
                    }

                    _statusMessage.value = buildString {
                        if (body.imported > 0) append("${body.imported} imported")
                        if (body.skipped > 0) {
                            if (isNotEmpty()) append(", ")
                            append("${body.skipped} already on server")
                        }
                        if (body.errors.isNotEmpty()) {
                            if (isNotEmpty()) append(", ")
                            append("${body.errors.size} failed")
                        }
                        if (isEmpty()) append("Upload complete")
                        val syncedCount = _logs.value.count { it.uploadStatus == UploadStatus.SYNCED }
                        if (syncedCount > 0) append(" — tap Delete to clean up controller")
                    }
                } else {
                    val code = response.code()
                    pending.forEach { setStatus(it, UploadStatus.ERROR) }
                    _statusMessage.value = when (code) {
                        403 -> "Invalid API key — check Settings"
                        401 -> "Unauthorized — check API key in Settings"
                        else -> "Upload failed (HTTP $code)"
                    }
                }
            } catch (e: Exception) {
                pending.forEach { setStatus(it, UploadStatus.ERROR) }
                _statusMessage.value = "Connection error: ${e.message?.take(80)}"
            }

            _isUploading.value = false
        }
    }

    fun deleteSynced() {
        viewModelScope.launch(Dispatchers.IO) {
            val toDelete = _logs.value.filter { it.uploadStatus == UploadStatus.SYNCED }
            var deleted = 0
            for (log in toDelete) {
                if (log.file.delete()) {
                    setStatus(log, UploadStatus.DELETED)
                    deleted++
                }
            }
            _statusMessage.value = "$deleted file(s) deleted from controller"
        }
    }

    private fun setStatus(log: FlightLog, status: UploadStatus) {
        _logs.value = _logs.value.map {
            if (it.file.absolutePath == log.file.absolutePath) it.copy(uploadStatus = status) else it
        }
    }

    /** Pull a bare filename out of an error string that may be a path or message. */
    private fun extractFilename(error: String): String =
        error.substringAfterLast('/').substringAfterLast('\\').trim()
}
