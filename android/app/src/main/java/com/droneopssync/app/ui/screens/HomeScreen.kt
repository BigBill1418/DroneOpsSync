package com.droneopssync.app.ui.screens

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
import com.droneopssync.app.model.UpdateState
import com.droneopssync.app.model.UploadStatus
import com.droneopssync.app.ui.theme.*
import com.droneopssync.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToDiag: () -> Unit = {},
    onInstallUpdate: (String) -> Unit = {}
) {
    val logs            by viewModel.logs.collectAsState()
    val statusMessage   by viewModel.statusMessage.collectAsState()
    val isUploading     by viewModel.isUploading.collectAsState()
    val serverReachable by viewModel.serverReachable.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()
    val updateState     by viewModel.updateState.collectAsState()

    val hasPending  = logs.any { it.uploadStatus == UploadStatus.PENDING }
    val hasSynced   = logs.any { it.uploadStatus == UploadStatus.SYNCED || it.uploadStatus == UploadStatus.DUPLICATE }
    val hasErrors   = logs.any { it.uploadStatus == UploadStatus.ERROR }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // ── Delete confirmation dialog ────────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = DocPanel,
            title = { Text("Confirm Delete", color = DocWhite, fontWeight = FontWeight.Bold) },
            text = {
                val syncedCount = logs.count { it.uploadStatus == UploadStatus.SYNCED }
                val dupCount    = logs.count { it.uploadStatus == UploadStatus.DUPLICATE }
                val total       = syncedCount + dupCount
                val breakdown   = buildString {
                    if (syncedCount > 0) append("$syncedCount just synced")
                    if (syncedCount > 0 && dupCount > 0) append(", ")
                    if (dupCount > 0) append("$dupCount already on server")
                }
                Text(
                    "Delete $total file(s) from this controller?\n($breakdown)\n\n" +
                    "All files are confirmed in DroneOpsCommand — this cannot be undone.",
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

    if (isLandscape) {
        // ── Landscape: left controls panel + right log list ───────────────────
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(DocDeep)
        ) {
            // Left panel — settings button + hero controls (scrollable)
            Column(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onNavigateToDiag) {
                        Icon(Icons.Outlined.BugReport, contentDescription = "Diagnostics", tint = DocMuted)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = DocMuted)
                    }
                }

                HeroContent(
                    serverReachable  = serverReachable,
                    connectionError  = connectionError,
                    hasPending       = hasPending,
                    hasErrors        = hasErrors,
                    hasSynced        = hasSynced,
                    isUploading      = isUploading,
                    logs             = logs,
                    updateState      = updateState,
                    onRetryConnection = { viewModel.checkServerHealth() },
                    onUploadAll      = { viewModel.uploadAll() },
                    onScanLogs       = { viewModel.scanLogs() },
                    onRetryFailed    = { viewModel.retryFailed() },
                    onDeleteSynced   = { showDeleteDialog = true },
                    onDownloadUpdate = { viewModel.downloadUpdate(context.cacheDir, onInstallUpdate) },
                    onInstallUpdate  = onInstallUpdate,
                    onDismissUpdate  = { viewModel.dismissUpdate() }
                )
            }

            VerticalDivider(color = DocDivider, thickness = 1.dp)

            // Right panel — status bar + log list + footer
            Column(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight()
            ) {
                StatusBar(statusMessage)
                HorizontalDivider(color = DocDivider, thickness = 1.dp)

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

                SiteFooter()
            }
        }
    } else {
        // ── Portrait: original stacked layout ────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DocDeep)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateToDiag) {
                    Icon(Icons.Outlined.BugReport, contentDescription = "Diagnostics", tint = DocMuted)
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = DocMuted)
                }
            }

            HeroContent(
                serverReachable   = serverReachable,
                connectionError   = connectionError,
                hasPending        = hasPending,
                hasErrors         = hasErrors,
                hasSynced         = hasSynced,
                isUploading       = isUploading,
                logs              = logs,
                updateState       = updateState,
                onRetryConnection = { viewModel.checkServerHealth() },
                onUploadAll       = { viewModel.uploadAll() },
                onScanLogs        = { viewModel.scanLogs() },
                onRetryFailed     = { viewModel.retryFailed() },
                onDeleteSynced    = { showDeleteDialog = true },
                onDownloadUpdate  = { viewModel.downloadUpdate(context.cacheDir, onInstallUpdate) },
                onInstallUpdate   = onInstallUpdate,
                onDismissUpdate   = { viewModel.dismissUpdate() }
            )

            StatusBar(statusMessage)
            HorizontalDivider(color = DocDivider, thickness = 1.dp)

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

            SiteFooter()
        }
    }
}

// ── Hero section content (shared between portrait and landscape) ──────────────
@Composable
private fun HeroContent(
    serverReachable: Boolean?,
    connectionError: String?,
    hasPending: Boolean,
    hasErrors: Boolean,
    hasSynced: Boolean,
    isUploading: Boolean,
    logs: List<FlightLog>,
    updateState: UpdateState,
    onRetryConnection: () -> Unit,
    onUploadAll: () -> Unit,
    onScanLogs: () -> Unit,
    onRetryFailed: () -> Unit,
    onDeleteSynced: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: (String) -> Unit,
    onDismissUpdate: () -> Unit
) {
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
        BrandLogo()

        // ── OTA update banner ─────────────────────────────────────────────────
        when (val us = updateState) {
            is UpdateState.Available -> {
                Spacer(Modifier.height(12.dp))
                UpdateBanner(
                    message = "Update v${us.version} available",
                    actionLabel = "DOWNLOAD",
                    color = DocCyan,
                    onClick = onDownloadUpdate,
                    onDismiss = onDismissUpdate
                )
            }
            is UpdateState.Downloading -> {
                Spacer(Modifier.height(12.dp))
                UpdateProgressBanner(progress = us.progress)
            }
            is UpdateState.ReadyToInstall -> {
                Spacer(Modifier.height(12.dp))
                UpdateBanner(
                    message = "Update downloaded — tap to install",
                    actionLabel = "INSTALL",
                    color = DocGreen,
                    onClick = { onInstallUpdate(us.apkPath) },
                    onDismiss = onDismissUpdate
                )
            }
            else -> {}
        }

        Spacer(Modifier.height(26.dp))

        ReadyToSyncBadge(serverReachable)

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
                onClick = onRetryConnection,
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

        Button(
            onClick = onUploadAll,
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

        OutlinedButton(
            onClick = onScanLogs,
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

        if (hasErrors && !isUploading) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onRetryFailed,
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

        if (hasSynced) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onDeleteSynced,
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
                val count = logs.count { it.uploadStatus == UploadStatus.SYNCED || it.uploadStatus == UploadStatus.DUPLICATE }
                Text(
                    "DELETE $count CONFIRMED FILE(S)",
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// ── OTA update banner components ──────────────────────────────────────────────
@Composable
private fun UpdateBanner(
    message: String,
    actionLabel: String,
    color: Color,
    onClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.82f),
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                color = color,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = actionLabel,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onClick)
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = color.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onDismiss)
            )
        }
    }
}

@Composable
private fun UpdateProgressBanner(progress: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(0.82f),
        shape = RoundedCornerShape(8.dp),
        color = DocCyan.copy(alpha = 0.10f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = DocCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Downloading update… $progress%", color = DocCyan, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = DocCyan,
                trackColor = DocSurface
            )
        }
    }
}

// ── Status bar ────────────────────────────────────────────────────────────────
@Composable
private fun StatusBar(statusMessage: String) {
    Surface(color = DocPanel) {
        Text(
            text = statusMessage,
            color = DocMuted,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            maxLines = 4
        )
    }
}

// ── Site footer ───────────────────────────────────────────────────────────────
@Composable
private fun SiteFooter() {
    val uriHandler = LocalUriHandler.current
    HorizontalDivider(color = DocDivider, thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DocPanel)
            .clickable { uriHandler.openUri("https://www.barnardhq.com") }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Language,
            contentDescription = null,
            tint = DocCyan.copy(alpha = 0.6f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "www.barnardhq.com",
            color = DocCyan.copy(alpha = 0.6f),
            fontSize = 12.sp,
            letterSpacing = 0.5.sp
        )
    }
}

// ── Ready To Sync badge ───────────────────────────────────────────────────────
@Composable
private fun ReadyToSyncBadge(serverReachable: Boolean?) {
    val isChecking = serverReachable == null

    val statusColor = when (serverReachable) {
        true  -> DocGreen
        false -> DocRed
        null  -> DocAmber
    }

    val animatedColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(400),
        label = "badgeColor"
    )

    val label = when (serverReachable) {
        true  -> "Ready To Sync"
        false -> "Server Unreachable"
        null  -> "Checking…"
    }

    val dotScale = if (isChecking) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "dotPulse"
        )
        scale
    } else {
        1f
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .scale(dotScale)
                .clip(CircleShape)
                .background(animatedColor)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = label,
            color = animatedColor,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 0.5.sp
        )
    }
}

// ── Brand logo — orange DroneOpsSync wordmark ─────────────────────────────────
@Composable
private fun BrandLogo() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedDroneIcon(color = DocOrange, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = DocOrange, fontWeight = FontWeight.ExtraBold)) { append("DroneOps") }
                withStyle(SpanStyle(color = DocOrange.copy(alpha = 0.75f), fontWeight = FontWeight.Light)) { append("Sync") }
            },
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

// ── Animated drone icon ───────────────────────────────────────────────────────
@Composable
private fun AnimatedDroneIcon(color: Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "drone")

    // Propeller spin — fast continuous rotation
    val propAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(320, easing = LinearEasing), RepeatMode.Restart),
        label = "propSpin"
    )

    // Gentle hover float
    val floatY by infiniteTransition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "hover"
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2 + floatY
        // Diagonal arm reach from center to propeller hub
        val arm   = size.minDimension * 0.295f
        val diag  = arm * 0.7071f          // arm * cos(45°)
        val bodyR = size.minDimension * 0.115f
        val propRx = size.minDimension * 0.152f
        val propRy = size.minDimension * 0.050f
        val sw    = size.minDimension * 0.070f

        // Four arm tips (NE, NW, SW, SE)
        val tips = listOf(
            Offset(cx + diag, cy - diag),
            Offset(cx - diag, cy - diag),
            Offset(cx - diag, cy + diag),
            Offset(cx + diag, cy + diag)
        )

        // Arms
        tips.forEach { tip ->
            drawLine(color, Offset(cx, cy), tip, sw, StrokeCap.Round)
        }

        // Spinning propeller ellipses
        tips.forEach { tip ->
            rotate(propAngle, tip) {
                drawOval(
                    color = color.copy(alpha = 0.88f),
                    topLeft = Offset(tip.x - propRx, tip.y - propRy),
                    size = Size(propRx * 2, propRy * 2),
                    style = Stroke(width = sw * 0.72f)
                )
            }
        }

        // Body
        drawCircle(color, bodyR, Offset(cx, cy))

        // Landing gear
        val legX   = bodyR * 0.88f
        val legTop = cy + bodyR
        val legBot = legTop + bodyR * 0.78f
        listOf(-legX, legX).forEach { xOff ->
            drawLine(color, Offset(cx + xOff, legTop), Offset(cx + xOff, legBot), sw * 0.58f, StrokeCap.Round)
        }
        drawLine(color, Offset(cx - legX, legBot), Offset(cx + legX, legBot), sw * 0.58f, StrokeCap.Round)
    }
}
