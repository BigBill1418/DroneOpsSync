package com.droneopssync.app.ui.screens

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droneopssync.app.BuildConfig
import com.droneopssync.app.model.UpdateState
import com.droneopssync.app.ui.theme.*
import com.droneopssync.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    prefs: SharedPreferences,
    onBack: () -> Unit
) {
    val currentServerUrl by viewModel.serverUrl.collectAsState()
    val currentApiKey    by viewModel.apiKey.collectAsState()
    val currentLogPaths  by viewModel.logPathsText.collectAsState()
    val updateState      by viewModel.updateState.collectAsState()

    var serverUrl by remember(currentServerUrl) { mutableStateOf(currentServerUrl) }
    var apiKey    by remember(currentApiKey)    { mutableStateOf(currentApiKey) }
    var logPaths  by remember(currentLogPaths)  { mutableStateOf(currentLogPaths) }
    var saved          by remember { mutableStateOf(false) }
    var apiKeyVisible  by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = DocDeep,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = DocWhite, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DocPanel),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = DocMuted)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.saveSettings(prefs, serverUrl, apiKey, logPaths)
                        saved = true
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Save", tint = DocCyan)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (saved) {
                Card(colors = CardDefaults.cardColors(containerColor = DocGreen.copy(alpha = 0.12f))) {
                    Text(
                        "Settings saved",
                        color = DocGreen,
                        modifier = Modifier.padding(12.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ── DroneOpsCommand URL ───────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DroneOpsCommand URL", color = DocCyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Local IP (e.g. http://192.168.1.50:8080) or WireGuard VPN IP for remote access",
                    color = DocMuted,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("http://192.168.1.50:8080", color = DocMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = DocCyan,
                        unfocusedBorderColor = DocSurface,
                        focusedTextColor     = DocWhite,
                        unfocusedTextColor   = DocWhite,
                        cursorColor          = DocCyan
                    )
                )
            }

            HorizontalDivider(color = DocDivider)

            // ── Device API Key ────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Device API Key", color = DocCyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Found in DroneOpsCommand → Settings → Device Access", color = DocMuted, fontSize = 13.sp)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste API key here", color = DocMuted) },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None
                                          else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (apiKeyVisible) "Hide" else "Show",
                                tint = DocMuted
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = DocCyan,
                        unfocusedBorderColor = DocSurface,
                        focusedTextColor     = DocWhite,
                        unfocusedTextColor   = DocWhite,
                        cursorColor          = DocCyan
                    )
                )
            }

            HorizontalDivider(color = DocDivider)

            // ── Flight Log Paths ─────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Flight Log Paths", color = DocCyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    TextButton(
                        onClick = {
                            logPaths = viewModel.defaultPathsText()
                            saved = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Reset to defaults", color = DocMuted, fontSize = 12.sp)
                    }
                }
                Text(
                    "One path per line. Scans .txt, .log, .csv, and .json files. Paths that don't exist are silently skipped.",
                    color = DocMuted,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = logPaths,
                    onValueChange = { logPaths = it; saved = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    minLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = DocCyan,
                        unfocusedBorderColor = DocSurface,
                        focusedTextColor     = DocWhite,
                        unfocusedTextColor   = DocWhite,
                        cursorColor          = DocCyan
                    )
                )
            }

            HorizontalDivider(color = DocDivider)

            // ── How it works ─────────────────────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = DocPanel)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("How it works", color = DocCyan, fontWeight = FontWeight.Bold)
                    Text("1. Tap SCAN FOR LOGS — finds .txt / .log / .csv / .json flight records in configured paths", color = DocMuted, fontSize = 13.sp)
                    Text("2. Tap SYNC ALL — uploads logs to DroneOpsCommand over LAN or VPN", color = DocMuted, fontSize = 13.sp)
                    Text("3. Tap DELETE — removes synced files from this controller only", color = DocMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Files are never deleted automatically — you must confirm.", color = DocRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = DocDivider)

            // ── App update ────────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("App Update", color = DocCyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                val isChecking = updateState is UpdateState.Checking
                OutlinedButton(
                    onClick = { viewModel.checkForUpdate() },
                    enabled = !isChecking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (isChecking) DocMuted.copy(alpha = 0.3f) else DocCyan.copy(alpha = 0.6f)
                    )
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = DocCyan,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Checking…", color = DocMuted)
                    } else {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = DocCyan
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("CHECK FOR UPDATES", color = DocCyan, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    }
                }

                when (val us = updateState) {
                    is UpdateState.UpToDate -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = DocGreen, modifier = Modifier.size(18.dp))
                            Text("Connected — app is up to date", color = DocGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    is UpdateState.Available -> {
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = DocCyan.copy(alpha = 0.10f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = DocCyan, modifier = Modifier.size(18.dp))
                                Text(
                                    "Update v${us.version} available — return to home screen to download",
                                    color = DocCyan,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    else -> {}
                }
            }

            // ── Version footer ────────────────────────────────────────────────────
            val versionName = BuildConfig.VERSION_NAME.takeIf { it.isNotBlank() } ?: "1.1.9"
            Text(
                "DroneOpsSync  v$versionName  ·  Build ${BuildConfig.VERSION_CODE}",
                color = DocMuted,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }
    }
}
