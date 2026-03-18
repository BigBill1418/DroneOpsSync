package com.droneopssync.app.ui.screens

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    var serverUrl  by remember(currentServerUrl) { mutableStateOf(currentServerUrl) }
    var apiKey     by remember(currentApiKey)    { mutableStateOf(currentApiKey) }
    var logPaths   by remember(currentLogPaths)  { mutableStateOf(currentLogPaths) }
    var saved      by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }

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
                Text(
                    "DroneOpsCommand URL",
                    color = DocCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "Public URL of your DroneOpsCommand instance — Cloudflare tunnel or local address",
                    color = DocMuted,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://your-tunnel.your-domain.com", color = DocMuted) },
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
                Text(
                    "Device API Key",
                    color = DocCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "Found in DroneOpsCommand → Settings → Device Access",
                    color = DocMuted,
                    fontSize = 13.sp
                )
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
                Text(
                    "Flight Log Paths",
                    color = DocCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "One path per line. Paths that don't exist on this controller are silently skipped.",
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
                    Text(
                        "1. Tap SCAN FOR LOGS — finds .txt/.log files in all configured paths",
                        color = DocMuted, fontSize = 13.sp
                    )
                    Text(
                        "2. Tap SYNC ALL — uploads logs to DroneOpsCommand via Cloudflare tunnel",
                        color = DocMuted, fontSize = 13.sp
                    )
                    Text(
                        "3. Tap DELETE — removes synced files from this controller only",
                        color = DocMuted, fontSize = 13.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Files are never deleted automatically — you must confirm.",
                        color = DocRed, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
