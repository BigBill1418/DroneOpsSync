package com.dronedump.app.ui.screens

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedump.app.ui.theme.*
import com.dronedump.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    prefs: SharedPreferences,
    onBack: () -> Unit
) {
    val currentServerUrl by viewModel.serverUrl.collectAsState()
    val currentLogPaths  by viewModel.logPathsText.collectAsState()

    var serverUrl by remember(currentServerUrl) { mutableStateOf(currentServerUrl) }
    var logPaths  by remember(currentLogPaths)  { mutableStateOf(currentLogPaths) }
    var saved     by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BhqNavy,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = BhqWhite, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BhqNavyMid),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = BhqGrey)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.saveSettings(prefs, serverUrl, logPaths)
                        saved = true
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Save", tint = BhqCyan)
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
                Card(colors = CardDefaults.cardColors(containerColor = BhqGreen.copy(alpha = 0.12f))) {
                    Text(
                        "Settings saved",
                        color = BhqGreen,
                        modifier = Modifier.padding(12.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Server URL
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NAS Server URL", color = BhqCyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "IP address and port of your Synology running DroneDump Server",
                    color = BhqGrey, fontSize = 13.sp
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("http://192.168.1.100:7474", color = BhqGrey) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = BhqCyan,
                        unfocusedBorderColor = BhqNavyLight,
                        focusedTextColor     = BhqWhite,
                        unfocusedTextColor   = BhqWhite,
                        cursorColor          = BhqCyan
                    )
                )
            }

            Divider(color = BhqDivider)

            // Log paths
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Flight Log Paths", color = BhqCyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "One path per line. Paths that don't exist on this controller are silently skipped.",
                    color = BhqGrey, fontSize = 13.sp
                )
                OutlinedTextField(
                    value = logPaths,
                    onValueChange = { logPaths = it; saved = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    minLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = BhqCyan,
                        unfocusedBorderColor = BhqNavyLight,
                        focusedTextColor     = BhqWhite,
                        unfocusedTextColor   = BhqWhite,
                        cursorColor          = BhqCyan
                    )
                )
            }

            Divider(color = BhqDivider)

            // Info card
            Card(colors = CardDefaults.cardColors(containerColor = BhqNavyMid)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("How it works", color = BhqCyan, fontWeight = FontWeight.Bold)
                    Text("1. Tap SCAN FOR LOGS — finds .txt/.log files in all configured paths", color = BhqGrey, fontSize = 13.sp)
                    Text("2. Tap SYNC ALL — uploads files and verifies SHA-256 checksums", color = BhqGrey, fontSize = 13.sp)
                    Text("3. Tap DELETE — removes verified files from this controller only", color = BhqGrey, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Files are never deleted automatically — you must confirm.",
                        color = BhqRed, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
