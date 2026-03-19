package com.droneopssync.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droneopssync.app.model.FlightLog
import com.droneopssync.app.model.UploadStatus
import com.droneopssync.app.ui.theme.*
import com.droneopssync.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit
) {
    val logs            by viewModel.logs.collectAsState()
    val statusMessage   by viewModel.statusMessage.collectAsState()
    val isUploading     by viewModel.isUploading.collectAsState()
    val serverReachable by viewModel.serverReachable.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()

    val hasPending  = logs.any { it.uploadStatus == UploadStatus.PENDING }
    val hasSynced   = logs.any { it.uploadStatus == UploadStatus.SYNCED }
    val hasErrors   = logs.any { it.uploadStatus == UploadStatus.ERROR }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // ── Delete confirmation dialog ────────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = DocPanel,
            title = { Text("Confirm Delete", color = DocWhite, fontWeight = FontWeight.Bold) },
            text = {
                val count = logs.count { it.uploadStatus == UploadStatus.SYNCED }
                Text(
                    "Delete $count synced file(s) from this controller?\n\n" +
                    "Files are confirmed in DroneOpsCommand — this cannot be undone.",
                    color = DocMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = { showDeleteDialog = false; viewModel.deleteSynced() },
                    colors = ButtonDefaults.buttonColors(containerColor = DocRed)
                ) { Text("Delete", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteDialog = false },
                    border = BorderStroke(1.dp, DocMuted)
                ) { Text("Cancel", color = DocMuted) }
            }
        )
    }

    // ── Root ──────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DocDeep)
    ) {

        // ── Minimal top bar ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = DocMuted)
            }
        }

        // ── Hero section ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.radialGradient(
                        0.0f  to Color(0xFF091220),
                        0.55f to Color(0xFF060C18),
                        1.0f  to DocDeep,
                        center = Offset.Unspecified,
                        radius = 900f
                    )
                )
                .padding(top = 12.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Logo
            BrandLogo()

            Spacer(Modifier.height(26.dp))

            // ── Ready To Sync indicator ──────────────────────────────────────
            ReadyToSyncBadge(serverReachable)

            // ── Connection error detail + Retry button ───────────────────────
            if (serverReachable == false) {
                Spacer(Modifier.height(8.dp))

                connectionError?.let { error ->
                    Text(
                        text = error,
                        color = DocRed.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .padding(bottom = 8.dp),
                        maxLines = 3
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.checkServerHealth() },
                    modifier = Modifier
                        .fillMaxWidth(0.60f)
                        .height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.5.dp, DocOrange.copy(alpha = 0.7f))
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = DocOrange
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "RETRY CONNECTION",
                        color = DocOrange,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(26.dp))

            // ── SYNC ALL ─────────────────────────────────────────────────────
            Button(
                onClick = { viewModel.uploadAll() },
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .height(52.dp),
                enabled = hasPending && serverReachable == true && !isUploading,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DocCyan,
                    disabledContainerColor = DocSurface
                )
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = DocDeep,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "SYNCING…",
                        color = DocDeep,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        fontSize = 15.sp
                    )
                } else {
                    Icon(
                        Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = DocDeep
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "SYNC ALL",
                        color = DocDeep,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── SCAN ─────────────────────────────────────────────────────────
            OutlinedButton(
                onClick = { viewModel.scanLogs() },
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .height(46.dp),
                enabled = !isUploading,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.5.dp, DocCyan.copy(alpha = 0.6f))
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = DocCyan
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "SCAN FOR LOGS",
                    color = DocCyan,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    fontSize = 14.sp
                )
            }

            // ── Retry failed uploads ─────────────────────────────────────────
            if (hasErrors && !isUploading) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { viewModel.retryFailed() },
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.5.dp, DocAmber.copy(alpha = 0.6f))
                ) {
                    Icon(
                        Icons.Default.Replay,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = DocAmber
                    )
                    Spacer(Modifier.width(8.dp))
                    val errorCount = logs.count { it.uploadStatus == UploadStatus.ERROR }
                    Text(
                        "RETRY $errorCount FAILED",
                        color = DocAmber,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        fontSize = 14.sp
                    )
                }
            }

            // ── Delete synced (conditional) ──────────────────────────────────
            if (hasSynced) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DocRed.copy(alpha = 0.85f)
                    )
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    val count = logs.count { it.uploadStatus == UploadStatus.SYNCED }
                    Text(
                        "DELETE $count SYNCED FILE(S)",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // ── Status bar ────────────────────────────────────────────────────────
        Surface(color = DocPanel) {
            Text(
                text = statusMessage,
                color = DocMuted,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                maxLines = 2
            )
        }

        HorizontalDivider(color = DocDivider, thickness = 1.dp)

        // ── Log list / empty state ────────────────────────────────────────────
        if (logs.isEmpty()) {
            EmptyState(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs, key = { it.file.absolutePath }) { log ->
                    LogFileCard(log)
                }
            }
        }
    }
}

// ── Ready To Sync badge ───────────────────────────────────────────────────────
@Composable
private fun ReadyToSyncBadge(serverReachable: Boolean?) {
    val isChecking = serverReachable == null

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "dotPulse"
    )

    val dotColor by animateColorAsState(
        targetValue = when (serverReachable) {
            true  -> DocGreen
            false -> DocRed
            null  -> DocAmber
        },
        animationSpec = tween(400),
        label = "dotColor"
    )

    val labelColor by animateColorAsState(
        targetValue = when (serverReachable) {
            true  -> DocGreen
            false -> DocRed
            null  -> DocAmber
        },
        animationSpec = tween(400),
        label = "labelColor"
    )

    val label = when (serverReachable) {
        true  -> "Ready To Sync"
        false -> "Server Unreachable"
        null  -> "Checking…"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .scale(if (isChecking) pulseScale else 1f)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = label,
            color = labelColor,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 0.5.sp
        )
    }
}

// ── Brand logo — PNG if present, Compose text fallback otherwise ─────────────
@Composable
private fun BrandLogo() {
    val context = LocalContext.current
    val resId = remember {
        context.resources.getIdentifier("barnard_hq_logo", "drawable", context.packageName)
    }

    if (resId != 0) {
        Image(
            painter = painterResource(resId),
            contentDescription = "BarnardHQ — Professional Aerial Operations",
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(2.5f),
            contentScale = ContentScale.Fit
        )
    } else {
        // Text fallback until barnard_hq_logo.png is added to res/drawable/
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Flight,
                contentDescription = null,
                tint = DocCyan,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = DocWhite)) { append("DroneOps") }
                    withStyle(SpanStyle(color = DocCyan))  { append("Sync") }
                },
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "FLIGHT LOG SYNC",
                color = DocMuted,
                fontSize = 11.sp,
                letterSpacing = 2.sp
            )
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                tint = DocMuted.copy(alpha = 0.35f),
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("No logs found", color = DocMuted, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text("Tap  SCAN FOR LOGS  above", color = DocMuted.copy(alpha = 0.55f), fontSize = 13.sp)
        }
    }
}

// ── Log file card ─────────────────────────────────────────────────────────────
@Composable
private fun LogFileCard(log: FlightLog) {
    val (statusColor, statusLabel, statusIcon) = when (log.uploadStatus) {
        UploadStatus.PENDING   -> Triple(DocMuted,    "PENDING",   Icons.Default.Schedule)
        UploadStatus.UPLOADING -> Triple(DocAmber,    "SYNCING",   Icons.Default.CloudUpload)
        UploadStatus.SYNCED    -> Triple(DocGreen,    "SYNCED",    Icons.Default.CheckCircle)
        UploadStatus.DUPLICATE -> Triple(DocMuted,    "ON SERVER", Icons.Default.CloudDone)
        UploadStatus.DELETED   -> Triple(DocDeleted,  "DELETED",   Icons.Default.Delete)
        UploadStatus.ERROR     -> Triple(DocRed,      "ERROR",     Icons.Default.Error)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = DocPanel),
        border = BorderStroke(1.dp, DocSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = statusLabel,
                tint = statusColor,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.name,
                    color = DocWhite,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${log.dateFormatted}  ·  ${log.sizeFormatted}",
                    color = DocMuted,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = statusColor.copy(alpha = 0.12f)
            ) {
                Text(
                    text = statusLabel,
                    color = statusColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
