package com.dronedump.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dronedump.app.model.FlightLog
import com.dronedump.app.model.UploadStatus
import com.dronedump.app.ui.theme.*
import com.dronedump.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit
) {
    val logs by viewModel.logs.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val serverReachable by viewModel.serverReachable.collectAsState()

    val hasVerified = logs.any { it.uploadStatus == UploadStatus.VERIFIED }
    val hasPending  = logs.any { it.uploadStatus == UploadStatus.PENDING }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = DroneCard,
            title = {
                Text("Confirm Delete", color = DroneTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                val count = logs.count { it.uploadStatus == UploadStatus.VERIFIED }
                Text(
                    "Delete $count verified file(s) from this controller?\n\nFiles have been confirmed on the NAS — this cannot be undone.",
                    color = DroneTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteVerified()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DroneError)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = DroneTextSecondary)
                }
            }
        )
    }

    Scaffold(
        containerColor = DroneBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "DroneDump",
                        color = DronePrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DroneSurface),
                actions = {
                    // Server health indicator
                    IconButton(onClick = { viewModel.checkServerHealth() }) {
                        Icon(
                            imageVector = when (serverReachable) {
                                true  -> Icons.Default.CloudDone
                                false -> Icons.Default.CloudOff
                                null  -> Icons.Default.Cloud
                            },
                            contentDescription = "Server status",
                            tint = when (serverReachable) {
                                true  -> DroneVerified
                                false -> DroneError
                                null  -> DroneTextSecondary
                            }
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = DroneTextSecondary)
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DroneSurface)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status message
                Text(
                    text = statusMessage,
                    color = DroneTextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Scan button
                    OutlinedButton(
                        onClick = { viewModel.scanLogs() },
                        modifier = Modifier.weight(1f),
                        enabled = !isUploading
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("SCAN")
                    }

                    // Upload button
                    Button(
                        onClick = { viewModel.uploadAll() },
                        modifier = Modifier.weight(2f),
                        enabled = hasPending && !isUploading,
                        colors = ButtonDefaults.buttonColors(containerColor = DronePrimary)
                    ) {
                        if (isUploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = DroneBackground,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("UPLOADING...", color = DroneBackground, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp), tint = DroneBackground)
                            Spacer(Modifier.width(6.dp))
                            Text("SYNC ALL", color = DroneBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Delete button — only visible when there are verified files
                if (hasVerified) {
                    Button(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = DroneError)
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        val count = logs.count { it.uploadStatus == UploadStatus.VERIFIED }
                        Text("DELETE $count VERIFIED FILE(S) FROM CONTROLLER", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.FlightTakeoff,
                        contentDescription = null,
                        tint = DroneTextSecondary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No logs loaded", color = DroneTextSecondary, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap SCAN to find flight logs", color = DroneTextSecondary, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs, key = { it.file.absolutePath }) { log ->
                    LogFileCard(log)
                }
            }
        }
    }
}

@Composable
private fun LogFileCard(log: FlightLog) {
    val (statusColor, statusLabel, statusIcon) = when (log.uploadStatus) {
        UploadStatus.PENDING   -> Triple(DroneTextSecondary, "PENDING",   Icons.Default.Schedule)
        UploadStatus.UPLOADING -> Triple(DroneUploading,     "UPLOADING", Icons.Default.CloudUpload)
        UploadStatus.VERIFIED  -> Triple(DroneVerified,      "VERIFIED",  Icons.Default.CheckCircle)
        UploadStatus.DELETED   -> Triple(DroneDeleted,       "DELETED",   Icons.Default.Delete)
        UploadStatus.ERROR     -> Triple(DroneError,         "ERROR",     Icons.Default.Error)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = DroneCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = statusLabel,
                tint = statusColor,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.name,
                    color = DroneTextPrimary,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${log.dateFormatted}  ·  ${log.sizeFormatted}",
                    color = DroneTextSecondary,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = statusColor.copy(alpha = 0.15f)
            ) {
                Text(
                    text = statusLabel,
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
