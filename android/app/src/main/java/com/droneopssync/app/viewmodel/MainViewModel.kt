package com.droneopssync.app.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droneopssync.app.BuildConfig
import com.droneopssync.app.api.ApiClient
import com.droneopssync.app.api.GitHubClient
import com.droneopssync.app.model.DiagLevel
import com.droneopssync.app.model.DiagLog
import com.droneopssync.app.model.FlightLog
import com.droneopssync.app.model.PerFileStatus
import com.droneopssync.app.model.SyncRecord
import com.droneopssync.app.model.UpdateState
import com.droneopssync.app.model.UploadStatus
import com.droneopssync.app.upload.FileOutcome
import com.droneopssync.app.upload.classifyUploadOutcome
import com.droneopssync.app.upload.reducePerFileState
import com.droneopssync.app.storage.FlightLogSource
import com.droneopssync.app.storage.LegacyFileSource
import com.droneopssync.app.storage.SafTreeSource
import com.droneopssync.app.storage.isDeleteEligible
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

private const val PREF_SERVER_URL          = "server_url"
private const val PREF_API_KEY             = "api_key"
private const val PREF_LOG_PATHS           = "log_paths"
private const val PREF_SYNC_HISTORY        = "sync_history"
private const val PREF_AUTO_SYNC           = "auto_sync_enabled"
// ADR-0005: persisted SAF tree URI granted by the operator. Mirrors
// MainActivity.PREF_SAF_FLIGHT_LOG_URI; kept private here so the
// activity-side onboarding writes it and the ViewModel reads it from
// the same key.
private const val PREF_SAF_FLIGHT_LOG_URI  = "saf_flight_log_uri"
// Pre-baked public URL for the primary instance (mirrors companion v2.62.0's
// DEFAULT_SERVER_URL). Previous default was a stale internal 10.x LAN IP that
// only resolved on HSH-HQ's WireGuard mesh and has been dead since the
// 2026-04-20 migration to BOS-HQ. See ADR-0001.
private const val DEFAULT_SERVER    = "https://droneops.barnardhq.com"
private const val MAX_HISTORY       = 100

class MainViewModel : ViewModel() {

    // SharedPreferences reference stored on first loadSettings call
    private var prefs: SharedPreferences? = null

    // ── ADR-0005 SAF support ─────────────────────────────────────────────────
    // Application Context, captured once via loadSettings(prefs, context).
    // Needed because SafTreeSource has to resolve content URIs and write
    // staging files to cacheDir. Application Context only — never an
    // Activity Context — to avoid leaks.
    private var appContext: Context? = null

    // The tree URI the operator granted via the SAF picker. Persisted to
    // SharedPreferences[PREF_SAF_FLIGHT_LOG_URI]. Null on first launch,
    // on Android <=10 (where the picker is never shown), and after a
    // failed/revoked grant detection.
    private val _safTreeUri = MutableStateFlow<Uri?>(null)
    val safTreeUri: StateFlow<Uri?> = _safTreeUri

    // True when the OS would block our File-based scan AND we don't yet
    // have a SAF grant. Drives the home-screen banner. Only ever true on
    // Android 11+ — Android <=10 has no scoped-storage lockdown and
    // Samsung's permissive MES on 11+ keeps Legacy working without SAF.
    //
    // The toggle from "needs grant" → "no longer needs grant" is driven
    // by either:
    //   (a) the operator completing the SAF picker → onSafGrantReceived
    //   (b) a Legacy scan finding files anyway (Samsung path) → set to
    //       false in performScan() after a successful Legacy hit
    private val _needsSafGrant = MutableStateFlow(false)
    val needsSafGrant: StateFlow<Boolean> = _needsSafGrant

    // ── Sync history ──────────────────────────────────────────────────────────
    private val _syncHistory = MutableStateFlow<List<SyncRecord>>(emptyList())
    val syncHistory: StateFlow<List<SyncRecord>> = _syncHistory

    // ── Auto-flow: signals UI to show the delete confirmation dialog ──────────
    private val _promptDelete = MutableStateFlow(false)
    val promptDelete: StateFlow<Boolean> = _promptDelete

    fun dismissDeletePrompt() { _promptDelete.value = false }

    // ── ADR-0002 zero-touch key rotation: one-shot toast events ──────────────
    // SharedFlow with replay=0 + buffer=1 + DROP_OLDEST: a toast emitted while
    // no collector is attached is dropped (don't spam the user the next time
    // HomeScreen mounts). Pattern matches the existing one-shot UI signal
    // shape (e.g. _promptDelete) but using a flow because each emission is
    // distinct content rather than a sticky boolean.
    private val _toastEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val toastEvents: SharedFlow<String> = _toastEvents

    // ── Auto-sync toggle ──────────────────────────────────────────────────────
    private val _autoSyncEnabled = MutableStateFlow(true)
    val autoSyncEnabled: StateFlow<Boolean> = _autoSyncEnabled

    fun setAutoSync(enabled: Boolean, prefs: SharedPreferences) {
        _autoSyncEnabled.value = enabled
        prefs.edit().putBoolean(PREF_AUTO_SYNC, enabled).apply()
    }

    // ── Scan progress (drives pull-to-refresh indicator) ─────────────────────
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

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

    private val _statusMessage = MutableStateFlow("Starting up…")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading

    private val _serverReachable = MutableStateFlow<Boolean?>(null)
    val serverReachable: StateFlow<Boolean?> = _serverReachable

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState

    private val downloadClient: OkHttpClient by lazy { OkHttpClient() }

    // ── Pairing state (silent-drift watchdog layer 1, ADR-0001) ──────────────
    sealed class PairingState {
        data object Paired : PairingState()
        data class Unpaired(val reason: String, val message: String) : PairingState()
    }

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Paired)
    val pairingState: StateFlow<PairingState> = _pairingState

    /** Pure — no side effects. Returns the structural pairing status. */
    private fun checkPairing(
        url: String = _serverUrl.value,
        key: String = _apiKey.value
    ): PairingState {
        if (url.isBlank()) return PairingState.Unpaired(
            "missing_url",
            "Server URL is not set. Open Settings and enter the DroneOps server URL."
        )
        if (key.isBlank()) return PairingState.Unpaired(
            "missing_key",
            "API key is not set. Open Settings and paste the key from DroneOps Settings → Device Access."
        )
        if (!url.isValidUrl()) return PairingState.Unpaired(
            "invalid_url",
            "Server URL must start with http:// or https://. Open Settings."
        )
        return PairingState.Paired
    }

    /** Publishes the current pairing state to observers. */
    private fun refreshPairing() {
        _pairingState.value = checkPairing()
    }

    // ── Preflight health gate (silent-drift watchdog layer 2, ADR-0001) ──────
    sealed class PreflightResult {
        // ADR-0008/0023: carry the async-upload capability hint out of the
        // preflight body so performUpload can pick the async vs legacy path.
        // `false` (default) preserves the legacy behaviour for older backends.
        data class Ok(val asyncUploadAvailable: Boolean = false) : PreflightResult()
        data class Fail(val code: String, val status: Int?, val message: String) : PreflightResult()
    }

    /**
     * Runs BEFORE every upload attempt. On failure, returns a classified
     * [PreflightResult.Fail] so callers render a specific banner instead of
     * silently retrying. Ported from companion v2.62.1's `preflightHealth()`.
     *
     * On success, additionally inspects the response body for an ADR-0003
     * `rotated_key` hint. If the server rotated this device's API key
     * server-side, the controller picks up the new key here — zero
     * operator interaction on the device. See [maybeApplyRotatedKey].
     */
    private suspend fun preflightHealth(url: String, apiKey: String): PreflightResult {
        return try {
            val response = ApiClient.create(url).deviceHealth(apiKey)
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    maybeApplyRotatedKey(currentKey = apiKey, body = body)
                    PreflightResult.Ok(
                        asyncUploadAvailable = body?.asyncUploadAvailable == true
                    )
                }
                response.code() == 401 -> PreflightResult.Fail(
                    "invalid_key",
                    401,
                    "Server rejected the API key. Open Settings → Device Access on the server, copy the key, and re-paste it here."
                )
                else -> PreflightResult.Fail(
                    "server_error",
                    response.code(),
                    "Server returned HTTP ${response.code()}. Try again; if it persists, check the DroneOps server status."
                )
            }
        } catch (e: UnknownHostException) {
            PreflightResult.Fail(
                "unreachable",
                null,
                "Cannot reach $url — check Wi-Fi and server status."
            )
        } catch (e: SocketTimeoutException) {
            PreflightResult.Fail(
                "unreachable",
                null,
                "Server at $url timed out — check Wi-Fi and server status."
            )
        } catch (e: IOException) {
            PreflightResult.Fail(
                "unreachable",
                null,
                "Network error reaching $url: ${e.message?.take(120)}"
            )
        }
    }

    // ── ADR-0003 zero-touch device API key rotation pickup ───────────────────
    //
    // Server returns `rotated_key` + `rotation_grace_until` ONLY when the
    // request authenticated via the OLD key during a 24h grace window. If
    // we see a sane new key, persist it to SharedPreferences, invalidate
    // the cached Retrofit service so the next call uses the new header,
    // and emit a one-shot toast so the operator sees confirmation. Bad
    // payloads are logged WARN and ignored — never overwrite a working
    // key with garbage.
    //
    // Validation rules — fail-safe by default:
    //   - missing field         → no-op (steady-state path)
    //   - empty string          → no-op (defensive; server should never)
    //   - prefix mismatch       → WARN + no-op
    //   - length < 40           → WARN + no-op
    //   - same as current key   → no-op (server idempotency / our own key)
    private companion object {
        const val ROTATED_KEY_PREFIX = "doc_"
        const val ROTATED_KEY_MIN_LENGTH = 40

        // ── ADR-0008/0023 async poll cadence ─────────────────────────────────
        // Poll every 2 s while non-terminal; back off to 5 s after 60 s; give
        // up the FOREGROUND poll after a generous ceiling (the job completes
        // server-side regardless — next launch reconciles via SHA-256 dedup).
        const val POLL_INTERVAL_FAST_MS  = 2_000L
        const val POLL_INTERVAL_SLOW_MS  = 5_000L
        const val POLL_BACKOFF_AFTER_MS  = 60_000L
        const val POLL_CEILING_MS        = 10 * 60_000L
    }

    private fun maybeApplyRotatedKey(
        currentKey: String,
        body: com.droneopssync.app.model.DeviceHealthResponse?,
    ) {
        val newKey = body?.rotatedKey ?: return
        if (newKey.isEmpty()) return
        if (newKey == currentKey) return  // idempotent — server echoed our key

        if (!newKey.startsWith(ROTATED_KEY_PREFIX) ||
            newKey.length < ROTATED_KEY_MIN_LENGTH) {
            diag(
                DiagLevel.WARN,
                "ROTATE",
                "Ignoring malformed rotated_key payload (prefix or length mismatch); keeping current key",
            )
            return
        }

        val grace = body.rotationGraceUntil
        prefs?.edit()?.putString(PREF_API_KEY, newKey)?.apply()
        _apiKey.value = newKey
        ApiClient.invalidate()
        refreshPairing()
        diag(
            DiagLevel.INFO,
            "ROTATE",
            "API key auto-rotated from server hint; grace expires at $grace",
        )
        _toastEvents.tryEmit("API key auto-updated")
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun loadSettings(prefs: SharedPreferences, context: Context? = null) {
        this.prefs = prefs
        if (context != null) this.appContext = context.applicationContext
        _serverUrl.value = prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        _apiKey.value    = prefs.getString(PREF_API_KEY, "") ?: ""

        // Log storage permission state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val granted = Environment.isExternalStorageManager()
            if (granted) {
                diag(DiagLevel.INFO, "PERM", "MANAGE_EXTERNAL_STORAGE: GRANTED (Samsung-permissive Legacy fallback may work)")
            } else {
                diag(DiagLevel.WARN, "PERM", "MANAGE_EXTERNAL_STORAGE: NOT GRANTED — Legacy fallback unavailable; SAF grant is required on stock-AOSP Android 11+")
            }
        } else {
            diag(DiagLevel.INFO, "PERM", "Android <11 — legacy storage (no MANAGE_EXTERNAL_STORAGE / SAF needed)")
        }

        _logPathsText.value = prefs.getString(PREF_LOG_PATHS, DEFAULT_PATHS.joinToString("\n"))
            ?: DEFAULT_PATHS.joinToString("\n")

        // ADR-0005: load persisted SAF tree URI if present. Validate it
        // here — a stale entry (revoked grant, deleted directory) is
        // surfaced to the operator via the banner instead of silently
        // failing on the next scan.
        //
        // ADR-0006: also verify the persisted grant carries WRITE
        // permission. v1.3.27 (ADR-0005) took the grant with READ only,
        // so DocumentsContract.deleteDocument silently fails after a
        // process restart. If we detect a READ-only persisted grant we
        // clear it and force the operator through the picker once more
        // — the new launcher (OpenDocumentTreeWithWrite) takes both
        // flags.
        val safRaw = prefs.getString(PREF_SAF_FLIGHT_LOG_URI, null)
        if (safRaw.isNullOrBlank()) {
            _safTreeUri.value = null
            diag(DiagLevel.INFO, "PERM", "SAF tree URI: not granted")
        } else {
            val parsed = runCatching { Uri.parse(safRaw) }.getOrNull()
            _safTreeUri.value = parsed
            diag(DiagLevel.INFO, "PERM", "SAF tree URI persisted: $safRaw")
            if (parsed != null && appContext != null) {
                val resolvable = runCatching {
                    DocumentFile.fromTreeUri(appContext!!, parsed)?.canRead() == true
                }.getOrDefault(false)
                if (!resolvable) {
                    diag(DiagLevel.WARN, "PERM", "SAF tree URI no longer resolvable — re-grant required")
                }
                // ADR-0006: WRITE-flag check. If the persisted grant
                // lacks WRITE, post-upload delete will silently fail.
                // Forget the grant and re-prompt.
                val perm = runCatching {
                    appContext!!.contentResolver.persistedUriPermissions
                        .firstOrNull { it.uri == parsed }
                }.getOrNull()
                if (perm != null && !perm.isWritePermission) {
                    diag(
                        DiagLevel.WARN, "PERM",
                        "Persisted SAF grant is READ-only (ADR-0006) — clearing and forcing re-grant for delete-on-controller"
                    )
                    prefs.edit().remove(PREF_SAF_FLIGHT_LOG_URI).apply()
                    _safTreeUri.value = null
                }
            }
        }

        // Surface "needs grant" only on Android 11+ (the picker is not
        // shown otherwise). The performScan() path will lower this flag
        // once it confirms Legacy is working on permissive OEMs.
        _needsSafGrant.value =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && _safTreeUri.value == null

        // Load persisted sync history
        val json = prefs.getString(PREF_SYNC_HISTORY, null)
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<SyncRecord>>() {}.type
                _syncHistory.value = Gson().fromJson(json, type) ?: emptyList()
            } catch (ignored: Exception) {}
        }

        _autoSyncEnabled.value = prefs.getBoolean(PREF_AUTO_SYNC, true)

        refreshPairing()
    }

    // ── ADR-0005: SAF onboarding callbacks (fired from MainActivity) ─────────

    /** Called by MainActivity after a successful tree-picker grant. */
    fun onSafGrantReceived(uri: Uri) {
        _safTreeUri.value = uri
        _needsSafGrant.value = false
        diag(DiagLevel.INFO, "PERM", "SAF grant received: $uri")
        // Immediate re-scan via the new source — operator expects a
        // visible result without re-tapping SCAN.
        scanLogs()
    }

    /** Called by MainActivity if the picker errored or the persistable grant was refused. */
    fun onSafGrantFailed(uri: String, reason: String) {
        diag(DiagLevel.ERROR, "PERM", "SAF grant failed for $uri — $reason")
        // Keep _needsSafGrant true so the banner stays visible.
        _needsSafGrant.value =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && _safTreeUri.value == null
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
        refreshPairing()
        checkServerHealth()
    }

    // ── Server health ─────────────────────────────────────────────────────────

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

    // ── Scan ──────────────────────────────────────────────────────────────────

    /** Manual scan — fire and forget. Also drives the pull-to-refresh indicator. */
    fun scanLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            try { performScan() } finally { _isScanning.value = false }
        }
    }

    /**
     * Core scan logic. Runs on whatever dispatcher the caller provides.
     * Returns the number of log files found.
     *
     * Source dispatch (ADR-0005):
     *
     *   if Android 11+ AND SAF grant present  → SafTreeSource
     *   else                                  → LegacyFileSource
     *
     * On Android 11+ with no SAF grant, the Legacy path runs first as a
     * permissive-OEM fallback (Samsung S25 Ultra works there). If Legacy
     * comes back empty AND we don't yet have a SAF grant, the banner
     * stays / becomes visible so the operator can grant. If Legacy
     * succeeds, _needsSafGrant is lowered — no point nagging the
     * operator on a device where the Samsung path is doing the job.
     */
    private suspend fun performScan(): Int {
        _statusMessage.value = "Scanning for logs…"

        val paths = _logPathsText.value
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val safUri = _safTreeUri.value
        val ctx    = appContext
        val useSaf = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                     safUri != null && ctx != null

        val source: FlightLogSource = if (useSaf) {
            SafTreeSource(safUri!!, ctx!!)
        } else {
            LegacyFileSource(paths)
        }

        diag(DiagLevel.INFO, "SCAN", "source=${source.label}  paths=${paths.size}")
        if (useSaf) {
            diag(DiagLevel.INFO, "SCAN", "  SAF treeUri=$safUri")
        }

        val result = source.scan()
        result.perPathDiag.forEach { diag(DiagLevel.INFO, "SCAN", "  $it") }
        result.missingPaths.forEach { diag(DiagLevel.WARN, "SCAN", "Missing: $it") }

        var found = result.logs.sortedByDescending { it.file.lastModified() }

        // Permissive-OEM detection: if Legacy succeeded on Android 11+,
        // we don't need a SAF grant — lower the banner. If Legacy came
        // back empty AND we're on Android 11+ with no SAF grant, the
        // banner stays visible so the operator can complete onboarding.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !useSaf) {
            if (found.isNotEmpty()) {
                _needsSafGrant.value = false
                diag(DiagLevel.INFO, "SCAN", "Legacy path succeeded on Android 11+ — SAF grant not required")
            } else if (safUri == null) {
                _needsSafGrant.value = true
                diag(DiagLevel.WARN, "SCAN", "Legacy returned 0 logs on Android 11+ — SAF grant required")
            }
        }

        _logs.value = found

        _statusMessage.value = when {
            found.isEmpty() && _needsSafGrant.value ->
                "DJI Fly logs need a one-time folder grant — tap the banner to allow"
            found.isEmpty() -> "No log files found — check paths in Settings"
            else -> "${found.size} log file(s) found via ${source.label}"
        }
        Log.d(TAG, "performScan done: source=${source.label} found=${found.size}")
        return found.size
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /** Manual upload — fire and forget. */
    fun uploadAll() {
        viewModelScope.launch(Dispatchers.IO) { performUpload() }
    }

    /**
     * Core upload logic. Runs on whatever dispatcher the caller provides.
     * Returns the count of files confirmed on server (SYNCED + DUPLICATE)
     * so callers can decide whether to trigger a delete prompt.
     */
    private suspend fun performUpload(): Int {
        val pending = _logs.value.filter { it.uploadStatus == UploadStatus.PENDING }
        if (pending.isEmpty()) {
            _statusMessage.value = "Nothing to upload"
            return 0
        }

        val url = _serverUrl.value
        val key = _apiKey.value
        if (url.isBlank() || key.isBlank()) {
            _statusMessage.value = "Configure server URL and API key in Settings"
            refreshPairing()
            return 0
        }
        if (!url.isValidUrl()) {
            _statusMessage.value = "Invalid URL — must start with http:// or https://"
            refreshPairing()
            return 0
        }

        // ── Preflight health gate (layer 2) ─────────────────────────────────
        // Block the upload if the server is unreachable or the key is bad.
        // Silent retries are exactly the class of failure that let the
        // 2026-04-23 RC Pro incident run for weeks unnoticed.
        val asyncAvailable: Boolean
        when (val pre = preflightHealth(url, key)) {
            is PreflightResult.Ok -> {
                _connectionError.value = null
                _serverReachable.value = true
                asyncAvailable = pre.asyncUploadAvailable
            }
            is PreflightResult.Fail -> {
                _statusMessage.value = pre.message
                _connectionError.value = pre.message
                _serverReachable.value = false
                diag(DiagLevel.ERROR, "PREFLIGHT", "${pre.code} status=${pre.status} msg=${pre.message}")
                return 0
            }
        }

        _isUploading.value = true
        pending.forEach { setStatus(it, UploadStatus.UPLOADING) }

        diag(
            DiagLevel.INFO, "UPLOAD",
            "Starting upload: ${pending.size} file(s) → $url  (path=${if (asyncAvailable) "async" else "legacy"})"
        )
        pending.forEach { diag(DiagLevel.INFO, "UPLOAD", "  ${it.file.name}  (${it.sizeFormatted})") }

        var totalImported = 0
        var totalSkipped  = 0
        var totalErrors   = 0
        var aborted = false

        try {
            for (log in pending) {
                if (aborted) {
                    setStatus(log, UploadStatus.ERROR)
                    totalErrors++
                    continue
                }
                // ADR-0008/0023: per-file outcome. The async path returns a
                // FileOutcome from the 202 + poll loop; the legacy path returns
                // one from the synchronous uploadFlights call. Both are pure
                // FileOutcomes the loop applies uniformly. GRACEFUL FALLBACK:
                // `asyncAvailable` is false on older/legacy servers, so a new
                // APK reaching a device before the backend deploy still works.
                val outcome: FileOutcome = if (asyncAvailable) {
                    uploadFileAsync(url, key, log)
                } else {
                    uploadFileLegacy(url, key, log)
                }

                setStatus(log, outcome.newStatus)
                totalImported += outcome.importedDelta
                if (outcome.newStatus == UploadStatus.DUPLICATE) totalSkipped += outcome.skippedDelta
                if (outcome.newStatus == UploadStatus.ERROR) totalErrors++
                if (outcome.abortBatch) aborted = true

                // ADR-0005: on a terminal success, the SAF cache copy is no
                // longer needed — server has the bytes. Tear the staging file
                // down to keep cacheDir bounded. The controller-side artifact
                // stays put until the operator confirms the delete dialog.
                if (outcome.newStatus == UploadStatus.SYNCED ||
                    outcome.newStatus == UploadStatus.DUPLICATE) {
                    cleanupSafCacheFile(log)
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
                append("$totalErrors error(s) — long-press to retry one, or tap Retry All")
            }
            if (isEmpty()) append("Upload complete — check Diagnostics")
        }

        // Record in sync history
        if (totalImported + totalSkipped + totalErrors > 0) {
            val record = SyncRecord(
                serverUrl      = url,
                filesAttempted = pending.size,
                imported       = totalImported,
                skipped        = totalSkipped,
                errors         = totalErrors
            )
            val updated = (_syncHistory.value + record).takeLast(MAX_HISTORY)
            _syncHistory.value = updated
            prefs?.edit()?.putString(PREF_SYNC_HISTORY, Gson().toJson(updated))?.apply()
        }

        return _logs.value.count {
            it.uploadStatus == UploadStatus.SYNCED || it.uploadStatus == UploadStatus.DUPLICATE
        }
    }

    /**
     * Build the multipart part for a single file, sanitizing the filename the
     * same way the legacy path always has (strip bracket/brace chars).
     */
    private fun buildPart(log: FlightLog): MultipartBody.Part {
        val safeName = log.file.name.replace(Regex("[\\[\\](){}]"), "_")
        diag(DiagLevel.INFO, "UPLOAD", "${log.file.name} → \"$safeName\"  (${log.sizeFormatted})")
        return MultipartBody.Part.createFormData(
            "files", safeName,
            log.file.asRequestBody("text/plain".toMediaTypeOrNull())
        )
    }

    /**
     * Legacy synchronous upload of one file (Stage C behaviour). One POST to
     * the unchanged `…/device-upload` route; the parse happens in-request on
     * the server. The per-file outcome decision is delegated to the pure
     * [classifyUploadOutcome] (Stage C fix: a socket timeout fails this file
     * alone and never aborts the batch).
     */
    private suspend fun uploadFileLegacy(url: String, key: String, log: FlightLog): FileOutcome {
        return try {
            val response = ApiClient.create(url).uploadFlights(key, listOf(buildPart(log)))
            val body = response.body()

            diag(DiagLevel.INFO, "UPLOAD", "  HTTP ${response.code()}")
            if (body != null) {
                diag(DiagLevel.INFO, "UPLOAD", "  imported=${body.imported}  skipped=${body.skipped}  errors=${body.errors.size}")
                body.errors.forEachIndexed { i, err -> diag(DiagLevel.ERROR, "UPLOAD", "  error[$i]: $err") }
            } else {
                val rawErr = response.errorBody()?.string()?.take(500) ?: "(no body)"
                diag(DiagLevel.ERROR, "UPLOAD", "  body null — raw: $rawErr")
            }

            classifyUploadOutcome(
                isSuccessful = response.isSuccessful,
                httpCode = response.code(),
                body = body,
                throwable = null,
            )
        } catch (e: Exception) {
            diag(DiagLevel.ERROR, "UPLOAD", "${e.javaClass.simpleName}: ${e.message}")
            classifyUploadOutcome(
                isSuccessful = false,
                httpCode = null,
                body = null,
                throwable = e,
            )
        }
    }

    /**
     * ADR-0008/0023 async upload of one file. POST to `…/device-upload/async`,
     * get a 202 + `batch_id`, then poll `…/status/{batch_id}` (2 s cadence,
     * back off to 5 s after 60 s, soft ceiling) and drive the per-file
     * UploadStatus from `per_file[0].state` until terminal.
     *
     * The connection is held only for the byte-stream, not the parse (B1
     * eliminated). A slow/failed parse on one file never affects another (B2
     * eliminated): each file is its own batch_id, and a poll exception or
     * ceiling is a per-file ERROR that never aborts the batch — EXCEPT a 401/403
     * on submit (bad device key) and an UnknownHost on submit (dead host),
     * which are genuinely batch-wide and reuse [classifyUploadOutcome].
     *
     * Client death mid-poll is safe: the job completes server-side regardless;
     * SHA-256 dedup reconciles on next launch.
     */
    private suspend fun uploadFileAsync(url: String, key: String, log: FlightLog): FileOutcome {
        // ── Submit ───────────────────────────────────────────────────────────
        val batchId: String = try {
            val response = ApiClient.create(url).uploadFlightsAsync(key, listOf(buildPart(log)))
            diag(DiagLevel.INFO, "UPLOAD", "  async submit HTTP ${response.code()}")

            if (!response.isSuccessful) {
                val rawErr = response.errorBody()?.string()?.take(500) ?: "(no body)"
                diag(DiagLevel.ERROR, "UPLOAD", "  async submit failed — raw: $rawErr")
                // Bad key (401/403) is batch-wide; other non-2xx fail this file.
                return classifyUploadOutcome(
                    isSuccessful = false,
                    httpCode = response.code(),
                    body = null,
                    throwable = null,
                )
            }

            val acceptBody = response.body()
            if (acceptBody == null || acceptBody.batchId.isBlank()) {
                diag(DiagLevel.ERROR, "UPLOAD", "  async submit 2xx but no batch_id — treating as error")
                return FileOutcome(UploadStatus.ERROR, abortBatch = false)
            }

            // Pre-parse dedup short-circuit: server may already mark the single
            // submitted file `skipped` in the 202 body (no job, no poll needed).
            val submitState = acceptBody.files.firstOrNull()?.state
            reducePerFileState(submitState)?.let { terminal ->
                diag(DiagLevel.INFO, "UPLOAD", "  async submit short-circuit: state=$submitState")
                return terminalOutcome(terminal)
            }

            diag(DiagLevel.INFO, "UPLOAD", "  async accepted batch_id=${acceptBody.batchId}")
            acceptBody.batchId
        } catch (e: Exception) {
            // UnknownHost → batch-wide abort; SocketTimeout/other → this file only.
            diag(DiagLevel.ERROR, "UPLOAD", "  async submit ${e.javaClass.simpleName}: ${e.message}")
            return classifyUploadOutcome(
                isSuccessful = false,
                httpCode = null,
                body = null,
                throwable = e,
            )
        }

        // ── Poll ─────────────────────────────────────────────────────────────
        val pollService = ApiClient.createPoll(url)
        val started = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - started
            if (elapsed > POLL_CEILING_MS) {
                diag(
                    DiagLevel.WARN, "UPLOAD",
                    "  async poll ceiling reached for batch=$batchId — job continues server-side; dedup reconciles on next launch"
                )
                return FileOutcome(UploadStatus.ERROR, abortBatch = false)
            }
            val interval = if (elapsed >= POLL_BACKOFF_AFTER_MS) POLL_INTERVAL_SLOW_MS else POLL_INTERVAL_FAST_MS
            kotlinx.coroutines.delay(interval)

            val perFile: PerFileStatus? = try {
                val response = pollService.pollUpload(key, batchId)
                if (!response.isSuccessful) {
                    diag(DiagLevel.WARN, "UPLOAD", "  async poll HTTP ${response.code()} — retrying")
                    continue
                }
                val statusBody = response.body()
                diag(
                    DiagLevel.INFO, "UPLOAD",
                    "  async poll status=${statusBody?.status} phase=${statusBody?.phase} progress=${statusBody?.progress}"
                )
                statusBody?.perFile?.firstOrNull()
            } catch (e: Exception) {
                // Transient poll failure — the job is still running server-side;
                // keep polling until the ceiling. Never abort the batch on a poll.
                diag(DiagLevel.WARN, "UPLOAD", "  async poll ${e.javaClass.simpleName}: ${e.message} — retrying")
                continue
            }

            val terminal = reducePerFileState(perFile?.state)
            if (terminal != null) {
                perFile?.error?.let { diag(DiagLevel.ERROR, "UPLOAD", "  async per-file error: $it") }
                diag(DiagLevel.INFO, "UPLOAD", "  async terminal: ${perFile?.state} → $terminal")
                return terminalOutcome(terminal, perFile)
            }
        }
    }

    /**
     * Map a terminal [UploadStatus] (from the async reducer) to a [FileOutcome]
     * carrying the right counter deltas. Never aborts the batch — a per-file
     * terminal state is by definition file-scoped.
     */
    private fun terminalOutcome(status: UploadStatus, perFile: PerFileStatus? = null): FileOutcome =
        when (status) {
            UploadStatus.SYNCED -> FileOutcome(
                UploadStatus.SYNCED,
                abortBatch = false,
                importedDelta = perFile?.imported ?: 1,
            )
            UploadStatus.DUPLICATE -> FileOutcome(
                UploadStatus.DUPLICATE,
                abortBatch = false,
                skippedDelta = 1,
            )
            else -> FileOutcome(UploadStatus.ERROR, abortBatch = false)
        }

    // ── Auto-flow: scan → sync → prompt delete ────────────────────────────────

    /**
     * Called on app launch and after network reconnect.
     * Scans for logs, waits briefly for the server health check, then
     * uploads if the server is reachable and there are pending files.
     * After upload, raises promptDelete if confirmed files exist.
     */
    fun startAutoFlow() {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            val found = try { performScan() } finally { _isScanning.value = false }
            if (found == 0) return@launch
            if (!_autoSyncEnabled.value) return@launch  // respect the toggle

            val url = _serverUrl.value
            val key = _apiKey.value
            if (url.isBlank() || key.isBlank()) {
                refreshPairing()
                return@launch
            }

            // Preflight health gate — mirrors companion v2.62.1. Authenticated
            // probe replaces the old 8 s wait on the unauthenticated /health
            // poll; we need to know the *key* is accepted before attempting
            // the multipart upload.
            val pre = preflightHealth(url, key)
            if (pre is PreflightResult.Fail) {
                _statusMessage.value = pre.message
                _connectionError.value = pre.message
                _serverReachable.value = false
                diag(DiagLevel.WARN, "AUTO", "Preflight failed — aborting auto-sync: ${pre.message}")
                return@launch
            }
            _serverReachable.value = true
            _connectionError.value = null

            diag(DiagLevel.INFO, "AUTO", "Auto-sync starting")
            val deletable = performUpload()
            if (deletable > 0) _promptDelete.value = true
        }
    }

    // ── Network-triggered sync (foreground, from ConnectivityManager) ─────────

    /** Called by ConnectivityManager.NetworkCallback when network becomes available. */
    fun onNetworkAvailable() {
        if (!_autoSyncEnabled.value) return
        if (_isUploading.value) return
        if (_logs.value.none { it.uploadStatus == UploadStatus.PENDING }) return
        val url = _serverUrl.value
        val key = _apiKey.value
        if (url.isBlank() || key.isBlank()) {
            refreshPairing()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            diag(DiagLevel.INFO, "AUTO", "Network connected — preflighting")
            val pre = preflightHealth(url, key)
            if (pre is PreflightResult.Fail) {
                _statusMessage.value = pre.message
                _connectionError.value = pre.message
                _serverReachable.value = false
                diag(DiagLevel.WARN, "AUTO", "Network-triggered sync aborted at preflight: ${pre.message}")
                return@launch
            }
            _serverReachable.value = true
            _connectionError.value = null
            _statusMessage.value = "Network connected — syncing…"
            val deletable = performUpload()
            if (deletable > 0) _promptDelete.value = true
        }
    }

    // ── Sync history ──────────────────────────────────────────────────────────

    fun clearSyncHistory() {
        _syncHistory.value = emptyList()
        prefs?.edit()?.remove(PREF_SYNC_HISTORY)?.apply()
    }

    // ── Retry ─────────────────────────────────────────────────────────────────

    fun retryFailed() {
        _logs.value = _logs.value.map {
            if (it.uploadStatus == UploadStatus.ERROR) it.copy(uploadStatus = UploadStatus.PENDING)
            else it
        }
        _statusMessage.value = "Error files reset to pending — tap SYNC ALL to retry"
    }

    fun retrySingle(log: FlightLog) {
        if (log.uploadStatus == UploadStatus.ERROR) {
            setStatus(log, UploadStatus.PENDING)
            _statusMessage.value = "${log.name} reset — tap SYNC ALL to retry"
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    fun deleteSynced() {
        viewModelScope.launch(Dispatchers.IO) {
            // Transfer-integrity guard (data-loss): only delete the controller original for
            // files whose transfer was VERIFIED complete. A SYNCED row off an
            // unverified/truncated SAF copy must never trigger the destructive
            // delete — that would erase the only good copy of irreplaceable
            // field data. Legacy entries are the on-disk original (verifiedTransfer
            // defaults true), so their delete behaviour is unchanged.
            val eligible = _logs.value.filter { it.uploadStatus == UploadStatus.SYNCED || it.uploadStatus == UploadStatus.DUPLICATE }
            val toDelete = eligible.filter { isDeleteEligible(it.uploadStatus, it.verifiedTransfer) }
            val protectedCount = eligible.size - toDelete.size
            if (protectedCount > 0) {
                diag(
                    DiagLevel.WARN, "DELETE",
                    "$protectedCount synced file(s) held back — transfer unverified (possible truncated copy); controller original preserved"
                )
            }
            var deleted = 0
            var failed = 0
            for (log in toDelete) {
                val ok = if (log.sourceUri != null) {
                    // ADR-0005: SAF entry — delete the original on the
                    // controller via DocumentsContract. Cache file is
                    // already gone (post-upload cleanup). Falls back to
                    // failure-marker if the provider revoked our grant
                    // or the directory moved.
                    deleteSafDocument(log.sourceUri)
                } else {
                    log.file.delete()
                }
                if (ok) {
                    setStatus(log, UploadStatus.DELETED)
                    deleted++
                } else {
                    failed++
                }
            }
            _statusMessage.value = when {
                failed == 0 -> "$deleted file(s) deleted from controller"
                deleted == 0 -> "Delete failed for $failed file(s) — check Diagnostics ([DELETE] channel)"
                else -> "$deleted deleted, $failed could not be removed — see Diagnostics"
            }
        }
    }

    /**
     * ADR-0005: delete a SAF-sourced document on the controller via the
     * persisted tree grant. Returns true if the provider confirmed the
     * delete, false on any error (revoked grant, missing file, IO).
     *
     * ADR-0006: surface the previously-swallowed failure. The grant must
     * carry WRITE permission (see launcher + loadSettings) — without it,
     * deleteDocument raises SecurityException. We log the cause via the
     * `[DELETE]` channel so the operator can verify in Diagnostics.
     */
    private fun deleteSafDocument(uri: Uri): Boolean {
        val ctx = appContext ?: return false
        return try {
            val ok = android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri)
            if (!ok) {
                diag(DiagLevel.WARN, "DELETE", "DocumentsContract.deleteDocument returned false for $uri")
            }
            ok
        } catch (e: SecurityException) {
            diag(
                DiagLevel.ERROR, "DELETE",
                "SecurityException deleting $uri — persisted grant missing WRITE? (${e.message?.take(120)})"
            )
            false
        } catch (e: Exception) {
            diag(
                DiagLevel.ERROR, "DELETE",
                "${e.javaClass.simpleName} deleting $uri: ${e.message?.take(120)}"
            )
            false
        }
    }

    /**
     * ADR-0005: tear down a single SAF staging file after upload. No-op
     * on Legacy entries (sourceUri == null) — those files live on /sdcard
     * and are deleted only when the operator confirms via deleteSynced.
     */
    private fun cleanupSafCacheFile(log: FlightLog) {
        if (log.sourceUri == null) return
        runCatching { log.file.delete() }
    }

    // ── OTA update ────────────────────────────────────────────────────────────

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
                val request = Request.Builder().url(state.downloadUrl).build()
                val response = downloadClient.newCall(request).execute()
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun defaultPathsText(): String = DEFAULT_PATHS.joinToString("\n")

    private fun setStatus(log: FlightLog, status: UploadStatus) {
        _logs.value = _logs.value.map {
            if (it.file.absolutePath == log.file.absolutePath) it.copy(uploadStatus = status) else it
        }
    }

    private fun String.isValidUrl(): Boolean =
        startsWith("http://") || startsWith("https://")
}
