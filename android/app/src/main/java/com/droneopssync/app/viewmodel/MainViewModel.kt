package com.droneopssync.app.viewmodel

import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droneopssync.app.BuildConfig
import com.droneopssync.app.api.ApiClient
import com.droneopssync.app.api.GitHubClient
import com.droneopssync.app.model.DiagLevel
import com.droneopssync.app.model.DiagLog
import com.droneopssync.app.model.FlightLog
import com.droneopssync.app.model.UpdateState
import com.droneopssync.app.model.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
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
//   Phones (DJI Fly)  → Android/data/com.dji.fly/files/FlightRecord
//   DJI Smart Controller / Mavic 3 Pro (GO 4 era) → DJI/dji.go.v4/FlightRecord
private val DEFAULT_PATHS = listOf(
    "/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord",
    "/storage/emulated/0/DJI/com.dji.industry.pilot/FlightRecord",
    "/storage/emulated/0/Android/data/com.dji.fly/files/FlightRecord",
    "/storage/emulated/0/DJI/dji.go.v4/FlightRecord"
)

private const val PREF_SERVER_URL = "server_url"
private const val PREF_API_KEY    = "api_key"
private const val PREF_LOG_PATHS  = "log_paths"
private const val DEFAULT_SERVER  = "http://10.50.0.5:3080"

class MainViewModel : ViewModel() {

    // ── Diagnostic log buffer ─────────────────────────────────────────────────
    private val _diagLogs = MutableStateFlow<List<DiagLog>>(emptyList())
    val diagLogs: StateFlow<List<DiagLog>> = _diagLogs

    private fun diag(level: DiagLevel, tag: String, message: String) {
        val entry = DiagLog(level = level, tag = tag, message = message)
        Log.println(
            when (level) { DiagLevel.ERROR -> Log.ERROR; DiagLevel.WARN -> Log.WARN; else -> Log.DEBUG },
            TAG, "[$tag] $message"
        )
        _diagLogs.value = (_diagLogs.value + entry).takeLast(500)
    }

    fun clearDiagLogs() { _diagLogs.value = emptyList() }

    // ── Flight logs ───────────────────────────────────────────────────────────
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

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    fun loadSettings(prefs: SharedPreferences) {
        _serverUrl.value = prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        _apiKey.value    = prefs.getString(PREF_API_KEY, "") ?: ""

        // Log storage permission state so it's immediately visible in Diagnostics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val granted = Environment.isExternalStorageManager()
            if (granted) {
                diag(DiagLevel.INFO, "PERM", "MANAGE_EXTERNAL_STORAGE: GRANTED")
            } else {
                diag(DiagLevel.ERROR, "PERM", "MANAGE_EXTERNAL_STORAGE: NOT GRANTED — scan will fail on Android 11+; open Settings and grant 'All files access' to this app")
            }
        } else {
            diag(DiagLevel.INFO, "PERM", "Android <11 — legacy storage (no MANAGE_EXTERNAL_STORAGE needed)")
        }

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
                diag(DiagLevel.WARN, "HEALTH", "No server URL configured")
                return@launch
            }
            if (!url.isValidUrl()) {
                _serverReachable.value = false
                _connectionError.value = "URL must start with http:// or https://"
                _statusMessage.value = "Invalid URL — must start with http:// or https://"
                diag(DiagLevel.WARN, "HEALTH", "Invalid URL: $url")
                return@launch
            }

            diag(DiagLevel.INFO, "HEALTH", "GET $url/health")
            try {
                val response = ApiClient.create(url).health()
                val ok = response.code() < 500
                _serverReachable.value = ok
                if (!ok) {
                    val err = "Server returned HTTP ${response.code()}"
                    _connectionError.value = err
                    diag(DiagLevel.ERROR, "HEALTH", err)
                } else {
                    diag(DiagLevel.INFO, "HEALTH", "HTTP ${response.code()} — server reachable")
                }
            } catch (e: UnknownHostException) {
                _serverReachable.value = false
                _connectionError.value = "DNS lookup failed — check the URL"
                diag(DiagLevel.ERROR, "HEALTH", "UnknownHostException: ${e.message}")
            } catch (e: SocketTimeoutException) {
                _serverReachable.value = false
                _connectionError.value = "Connection timed out — is the server running?"
                diag(DiagLevel.ERROR, "HEALTH", "SocketTimeoutException: ${e.message}")
            } catch (e: IOException) {
                Log.e(TAG, "Health check failed", e)
                _serverReachable.value = false
                _connectionError.value = "Network error: ${e.message?.take(120)}"
                diag(DiagLevel.ERROR, "HEALTH", "IOException: ${e.message}")
            }
        }
    }

    fun scanLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val paths = _logPathsText.value
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val found        = mutableListOf<FlightLog>()
            val missingPaths = mutableListOf<String>()

            diag(DiagLevel.INFO, "SCAN", "Scanning ${paths.size} path(s)")
            for (pathStr in paths) {
                val dir = File(pathStr)
                diag(DiagLevel.INFO, "SCAN", "$pathStr — exists=${dir.exists()} isDir=${dir.isDirectory}")
                Log.d(TAG, "scanLogs: checking $pathStr → exists=${dir.exists()} isDir=${dir.isDirectory}")
                if (dir.exists() && dir.isDirectory) {
                    val hits = dir.listFiles { f ->
                        f.isFile && f.extension.lowercase() in listOf("txt", "log", "csv", "json")
                    }?.toList() ?: emptyList()
                    hits.forEach {
                        Log.d(TAG, "  found: ${it.absolutePath}")
                        found.add(FlightLog(file = it))
                    }
                    diag(DiagLevel.INFO, "SCAN", "  ${hits.size} file(s) found")
                    hits.forEach { diag(DiagLevel.INFO, "SCAN", "  ${it.name}  (${it.length()} B)") }
                } else {
                    missingPaths += pathStr
                    diag(DiagLevel.WARN, "SCAN", "Path not found: $pathStr")
                }
            }

            found.sortByDescending { it.file.lastModified() }
            _logs.value = found

            _statusMessage.value = when {
                found.isEmpty() -> "No log files found — check paths in Settings"
                else -> "${found.size} log file(s) found across ${paths.size - missingPaths.size} path(s)"
            }
            Log.d(TAG, "scanLogs done: found=${found.size}")
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

            diag(DiagLevel.INFO, "UPLOAD", "Starting upload: ${pending.size} file(s) → $url")
            pending.forEach { diag(DiagLevel.INFO, "UPLOAD", "  ${it.file.name}  (${it.sizeFormatted})") }

            var totalImported = 0
            var totalSkipped  = 0
            var totalErrors   = 0
            // Set true on auth failure or network error so remaining files are
            // marked ERROR immediately rather than attempting doomed requests.
            var aborted = false

            try {
                for (log in pending) {
                    if (aborted) {
                        setStatus(log, UploadStatus.ERROR)
                        totalErrors++
                        continue
                    }
                    try {
                        // Strip characters that break multipart/form-data parsers in the
                        // Content-Disposition filename param (e.g. "[", "]" in DJI names).
                        val safeName = log.file.name.replace(Regex("[\\[\\](){}]"), "_")
                        diag(DiagLevel.INFO, "UPLOAD", "${log.file.name} → \"$safeName\"  (${log.sizeFormatted})")
                        val part = MultipartBody.Part.createFormData(
                            "files", safeName,
                            log.file.asRequestBody("text/plain".toMediaTypeOrNull())
                        )

                        val response = ApiClient.create(url).uploadFlights(key, listOf(part))
                        val body = response.body()

                        diag(DiagLevel.INFO, "UPLOAD", "  HTTP ${response.code()}")
                        if (body != null) {
                            diag(DiagLevel.INFO, "UPLOAD", "  imported=${body.imported}  skipped=${body.skipped}  errors=${body.errors.size}")
                            body.errors.forEachIndexed { i, err -> diag(DiagLevel.ERROR, "UPLOAD", "  error[$i]: $err") }
                        } else {
                            val rawErr = response.errorBody()?.string()?.take(500) ?: "(no body)"
                            diag(DiagLevel.ERROR, "UPLOAD", "  body null — raw: $rawErr")
                        }

                        when {
                            !response.isSuccessful -> {
                                setStatus(log, UploadStatus.ERROR)
                                totalErrors++
                                // Auth failures are permanent — no point retrying remaining files
                                if (response.code() in listOf(401, 403)) aborted = true
                            }
                            body == null -> {
                                setStatus(log, UploadStatus.ERROR)
                                totalErrors++
                            }
                            body.errors.isNotEmpty() -> {
                                setStatus(log, UploadStatus.ERROR)
                                totalErrors++
                            }
                            body.skipped > 0 -> {
                                setStatus(log, UploadStatus.DUPLICATE)
                                totalSkipped++
                            }
                            else -> {
                                setStatus(log, UploadStatus.SYNCED)
                                totalImported += body.imported
                            }
                        }
                    } catch (e: UnknownHostException) {
                        setStatus(log, UploadStatus.ERROR)
                        totalErrors++
                        diag(DiagLevel.ERROR, "UPLOAD", "UnknownHostException: ${e.message}")
                        aborted = true
                    } catch (e: SocketTimeoutException) {
                        setStatus(log, UploadStatus.ERROR)
                        totalErrors++
                        diag(DiagLevel.ERROR, "UPLOAD", "SocketTimeoutException: ${e.message}")
                        aborted = true
                    } catch (e: Exception) {
                        setStatus(log, UploadStatus.ERROR)
                        totalErrors++
                        diag(DiagLevel.ERROR, "UPLOAD", "${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            } finally {
                _isUploading.value = false
            }

            _statusMessage.value = buildString {
                if (totalImported > 0) append("$totalImported imported")
                if (totalSkipped > 0) {
                    if (isNotEmpty()) append(", ")
                    append("$totalSkipped already on server")
                }
                if (totalErrors > 0) {
                    if (isNotEmpty()) append(", ")
                    append("$totalErrors error(s) — tap Retry")
                }
                if (isEmpty()) append("Upload complete — check Diagnostics")
                val deletableCount = _logs.value.count {
                    it.uploadStatus == UploadStatus.SYNCED || it.uploadStatus == UploadStatus.DUPLICATE
                }
                if (deletableCount > 0) append(" — tap Delete to clean up controller")
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
            val toDelete = _logs.value.filter {
                it.uploadStatus == UploadStatus.SYNCED || it.uploadStatus == UploadStatus.DUPLICATE
            }
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

    fun checkForUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            _updateState.value = UpdateState.Checking
            try {
                val response = GitHubClient.service.getLatestRelease("BigBill1418", "DroneOpsSync")
                val release = response.body()
                if (!response.isSuccessful || release == null) {
                    _updateState.value = UpdateState.Idle
                    return@launch
                }
                // Strip leading 'v' from tag if present
                val remoteVersion = release.tagName.trimStart('v')
                val currentVersion = BuildConfig.VERSION_NAME
                if (remoteVersion == currentVersion) {
                    _updateState.value = UpdateState.UpToDate
                    return@launch
                }
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                if (apkAsset == null) {
                    _updateState.value = UpdateState.Idle
                    return@launch
                }
                _updateState.value = UpdateState.Available(
                    version = remoteVersion,
                    downloadUrl = apkAsset.downloadUrl,
                    sizeBytes = apkAsset.size
                )
            } catch (e: Exception) {
                _updateState.value = UpdateState.Idle
                diag(DiagLevel.WARN, "OTA", "Update check failed: ${e.message}")
            }
        }
    }

    fun downloadUpdate(cacheDir: File, onReadyToInstall: (String) -> Unit) {
        val state = _updateState.value
        if (state !is UpdateState.Available) return
        viewModelScope.launch(Dispatchers.IO) {
            _updateState.value = UpdateState.Downloading(0)
            val apkFile = File(cacheDir, "droneopssync-update.apk")
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(state.downloadUrl).build()
                val response = client.newCall(request).execute()
                val body = response.body ?: throw IOException("Empty response body")
                val totalBytes = body.contentLength()
                apkFile.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val progress = ((downloaded * 100) / totalBytes).toInt()
                                _updateState.value = UpdateState.Downloading(progress)
                            }
                        }
                    }
                }
                _updateState.value = UpdateState.ReadyToInstall(apkFile.absolutePath)
                onReadyToInstall(apkFile.absolutePath)
            } catch (e: Exception) {
                apkFile.delete()
                _updateState.value = UpdateState.Available(
                    version = state.version,
                    downloadUrl = state.downloadUrl,
                    sizeBytes = state.sizeBytes
                )
                diag(DiagLevel.ERROR, "OTA", "Download failed: ${e.message}")
            }
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateState.Idle
    }

    fun defaultPathsText(): String = DEFAULT_PATHS.joinToString("\n")

    private fun String.isValidUrl(): Boolean =
        startsWith("http://") || startsWith("https://")
}
