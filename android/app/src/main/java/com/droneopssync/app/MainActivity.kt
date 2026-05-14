package com.droneopssync.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.droneopssync.app.ui.screens.DiagScreen
import com.droneopssync.app.ui.screens.HomeScreen
import com.droneopssync.app.ui.screens.SettingsScreen
import com.droneopssync.app.ui.screens.SplashScreen
import com.droneopssync.app.ui.screens.SyncHistoryScreen
import com.droneopssync.app.ui.theme.DroneOpsSyncTheme
import com.droneopssync.app.viewmodel.MainViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Launcher for READ + WRITE EXTERNAL_STORAGE on Android 9/10
    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* nothing to do */ }

    // ── ADR-0005: SAF tree-picker for DJI flight logs on AOSP-strict Android 11+ ─
    // ── ADR-0006: persisted grant MUST include WRITE so DocumentsContract.delete
    //              succeeds after process restart (the result intent default omits
    //              FLAG_GRANT_WRITE_URI_PERMISSION, so we both subclass the
    //              contract to add it to the launch intent AND request the
    //              persistable take with both READ + WRITE).
    // Result is the user-selected directory URI. We persist it across reboots
    // via takePersistableUriPermission; the ViewModel reads it on next scan.
    private val openTreeLauncher =
        registerForActivityResult(OpenDocumentTreeWithWrite()) { uri: Uri? ->
            if (uri == null) {
                // User cancelled. Banner stays visible; ViewModel's
                // _needsSafGrant remains true.
                return@registerForActivityResult
            }
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: SecurityException) {
                // Provider refused the persistable grant. Treat as a
                // failed grant — ViewModel will keep prompting.
                viewModel.onSafGrantFailed(uri.toString(), e.message ?: "SecurityException")
                return@registerForActivityResult
            }
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putString(PREF_SAF_FLIGHT_LOG_URI, uri.toString()).apply()
            viewModel.onSafGrantReceived(uri)
        }

    // Auto-sync when network becomes available (foreground only)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModel.onNetworkAvailable()
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, networkCallback)
    }

    private fun unregisterNetworkCallback() {
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
    }

    override fun onStart() {
        super.onStart()
        registerNetworkCallback()
    }

    override fun onStop() {
        super.onStop()
        unregisterNetworkCallback()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ — request All Files Access. Still useful as a
            // fallback for permissive OEMs (Samsung) where the legacy File
            // path keeps working without a SAF grant. SAF is the primary
            // path on stock-AOSP Android 11/12 (e.g. DJI RC Pro 2) — see
            // ADR-0005.
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } else {
            // Android 9/10 — request READ + WRITE so scan and delete both work
            val toRequest = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            if (toRequest.isNotEmpty()) requestStoragePermission.launch(toRequest)
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        viewModel.loadSettings(prefs, applicationContext)
        viewModel.checkServerHealth()
        viewModel.checkForUpdate()
        viewModel.startAutoFlow()

        setContent {
            DroneOpsSyncTheme {
                val navController = rememberNavController()
                val sharedPrefs = remember { prefs }

                NavHost(navController = navController, startDestination = "splash") {
                    composable("splash") {
                        SplashScreen(
                            onFinished = {
                                navController.navigate("home") {
                                    popUpTo("splash") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("home") {
                        HomeScreen(
                            viewModel = viewModel,
                            onNavigateToSettings = { navController.navigate("settings") },
                            onNavigateToDiag = { navController.navigate("diag") },
                            onNavigateToHistory = { navController.navigate("history") },
                            onInstallUpdate = { apkPath -> installApk(apkPath) },
                            onRequestSafGrant = { launchSafTreePicker() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = viewModel,
                            prefs = sharedPrefs,
                            onBack = { navController.popBackStack() },
                            onRequestSafGrant = { launchSafTreePicker() }
                        )
                    }
                    composable("diag") {
                        DiagScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("history") {
                        SyncHistoryScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    /**
     * Build the `ACTION_OPEN_DOCUMENT_TREE` intent with `EXTRA_INITIAL_URI`
     * pre-seeded to the DJI Fly FlightRecord directory so the picker
     * lands the operator one tap away from "Use this folder."
     *
     * On Android 8+ `EXTRA_INITIAL_URI` is honored when it points to a
     * document URI under `com.android.externalstorage.documents`. The
     * `:` in `primary:Android/...` is part of the document ID — encoded
     * by `buildDocumentUri`.
     */
    private fun launchSafTreePicker() {
        val initialUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DocumentsContract.buildDocumentUri(
                EXTERNAL_STORAGE_AUTHORITY,
                DJI_FLY_FLIGHTRECORD_DOCUMENT_ID,
            )
        } else {
            null
        }
        try {
            // The OpenDocumentTree contract internally builds an
            // ACTION_OPEN_DOCUMENT_TREE intent and ANDs FLAG_GRANT_READ.
            // We only need to pass the optional initial URI hint.
            openTreeLauncher.launch(initialUri)
        } catch (e: Exception) {
            // If the picker is somehow missing on this device (no
            // DocumentsUI), surface a diag entry — the banner will keep
            // showing and the operator knows to fall back to a wired pull.
            viewModel.onSafGrantFailed(
                "(picker)",
                "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun installApk(apkPath: String) {
        val apkFile = File(apkPath)
        if (!apkFile.exists()) return
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * ADR-0006: `ActivityResultContracts.OpenDocumentTree.createIntent` does
     * not attach FLAG_GRANT_WRITE_URI_PERMISSION to the picker intent, so
     * the resulting grant is READ-only and `DocumentsContract.deleteDocument`
     * fails with SecurityException after the process restarts. Subclass and
     * add both READ + WRITE flags so the persistable grant we take includes
     * write capability.
     */
    private class OpenDocumentTreeWithWrite : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: Context, input: Uri?): Intent =
            super.createIntent(context, input).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
    }

    companion object {
        // Shared with MainViewModel.loadSettings via the SharedPreferences
        // instance handed in. Kept as constants here so the activity-side
        // SAF onboarding writes to the same key the ViewModel reads.
        const val PREFS_NAME = "droneopssync_prefs"
        const val PREF_SAF_FLIGHT_LOG_URI = "saf_flight_log_uri"

        // ExternalStorageProvider authority — stable, AOSP-canonical.
        private const val EXTERNAL_STORAGE_AUTHORITY =
            "com.android.externalstorage.documents"

        // Document ID for the DJI Fly FlightRecord directory under the
        // primary external volume. Format: "primary:<relative-path>".
        // Confirmed correct path; SAF takes a directory URI, not a string
        // literal, so this only seeds the picker — the operator's grant
        // is what we persist.
        private const val DJI_FLY_FLIGHTRECORD_DOCUMENT_ID =
            "primary:Android/data/dji.go.v5/files/FlightRecord"
    }
}
