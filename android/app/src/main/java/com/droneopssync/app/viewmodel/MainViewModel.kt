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
import java.security.MessageDigest

// Default scan paths — covers both DJI Pilot 2 and DJI GO 5 controllers
private val DEFAULT_PATHS = listOf(
    "/storage/emulated/0/DJI/com.dji.industry.pilot/FlightRecord",
    "/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord"
)

private const val PREF_SERVER_URL = "server_url"
private const val PREF_LOG_PATHS = "log_paths"
private const val DEFAULT_SERVER = "http://192.168.50.20:7474"

class MainViewModel : ViewModel() {

    private val _logs = MutableStateFlow<List<FlightLog>>(emptyList())
    val logs: StateFlow<List<FlightLog>> = _logs

    private val _serverUrl = MutableStateFlow(DEFAULT_SERVER)
    val serverUrl: StateFlow<String> = _serverUrl

    // Newline-separated list of paths shown/edited in Settings
    private val _logPathsText = MutableStateFlow(DEFAULT_PATHS.joinToString("\n"))
    val logPathsText: StateFlow<String> = _logPathsText

    private val _statusMessage = MutableStateFlow("Tap scan to find flight logs")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading

    private val _serverReachable = MutableStateFlow<Boolean?>(null)
    val serverReachable: StateFlow<Boolean?> = _serverReachable

    fun loadSettings(prefs: SharedPreferences) {
        _serverUrl.value = prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        _logPathsText.value = prefs.getString(PREF_LOG_PATHS, DEFAULT_PATHS.joinToString("\n"))
            ?: DEFAULT_PATHS.joinToString("\n")
    }

    fun saveSettings(prefs: SharedPreferences, serverUrl: String, logPathsText: String) {
        _serverUrl.value = serverUrl.trim()
        _logPathsText.value = logPathsText
        prefs.edit()
            .putString(PREF_SERVER_URL, serverUrl.trim())
            .putString(PREF_LOG_PATHS, logPathsText)
            .apply()
        _statusMessage.value = "Settings saved"
    }

    fun checkServerHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            _serverReachable.value = null
            try {
                val response = ApiClient.create(_serverUrl.value).health()
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

            _isUploading.value = true
            val service = ApiClient.create(_serverUrl.value)
            var successCount = 0
            var failCount = 0

            for (log in pending) {
                setStatus(log, UploadStatus.UPLOADING)
                try {
                    val localChecksum = sha256(log.file)
                    val requestBody = log.file.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                    val part = MultipartBody.Part.createFormData("file", log.file.name, requestBody)

                    val response = service.uploadFile(part)
                    val body = response.body()

                    if (response.isSuccessful && body != null && body.checksum == localChecksum) {
                        setStatus(log, UploadStatus.VERIFIED)
                        successCount++
                    } else {
                        setStatus(log, UploadStatus.ERROR)
                        failCount++
                    }
                } catch (e: Exception) {
                    setStatus(log, UploadStatus.ERROR)
                    failCount++
                }
            }

            val verifiedCount = _logs.value.count { it.uploadStatus == UploadStatus.VERIFIED }
            _statusMessage.value = buildString {
                append("$successCount uploaded & verified")
                if (failCount > 0) append(", $failCount failed")
                if (verifiedCount > 0) append(" — tap Delete to remove from controller")
            }
            _isUploading.value = false
        }
    }

    fun deleteVerified() {
        viewModelScope.launch(Dispatchers.IO) {
            val toDelete = _logs.value.filter { it.uploadStatus == UploadStatus.VERIFIED }
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
