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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

    val hasPending  = logs.any { it.uploadStatus == UploadStatus.PENDING }
    val hasVerified = logs.any { it.uploadStatus == UploadStatus.VERIFIED }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // ── Delete confirmation dialog ───────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = BhqNavyMid,
            title = { Text("Confirm Delete", color = BhqWhite, fontWeight = FontWeight.Bold) },
            text = {
                val count = logs.count { it.uploadStatus == UploadStatus.VERIFIED }
                Text(
                    "Delete $count verified file(s) from this controller?\n\n" +
                    "Files are confirmed on the NAS — this cannot be undone.",
                    color = BhqGrey
                )
            },
            confirmButton = {
                Button(
                    onClick = { showDeleteDialog = false; viewModel.deleteVerified() },
                    colors = ButtonDefaults.buttonColors(containerColor = BhqRed)
                ) { Text("Delete", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteDialog = false },
                    border = BorderStroke(1.dp, BhqGrey)
                ) { Text("Cancel", color = BhqGrey) }
            }
        )
    }

    // ── Root ─────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BhqNavy)
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
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = BhqGrey)
            }
        }

        // ── Hero section ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.radialGradient(
                        0.0f  to Color(0xFF0F2248),
                        0.65f to Color(0xFF0B1A35),
                        1.0f  to BhqNavy,
                        center = Offset.Unspecified,
                        radius = 900f
                    )
                )
                .padding(top = 12.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Logo (PNG or text fallback)
            BrandLogo()

            Spacer(Modifier.height(26.dp))

            // ── Ready To Sync indicator ──────────────────────────────────────
            ReadyToSyncBadge(serverReachable)

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
                    containerColor = BhqCyan,
                    disabledContainerColor = BhqNavyLight
                )
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = BhqNavy,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "UPLOADING…",
                        color = BhqNavy,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        fontSize = 15.sp
                    )
                } else {
                    Icon(
                        Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = BhqNavy
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "SYNC ALL",
                        color = BhqNavy,
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
                border = BorderStroke(1.5.dp, BhqCyan.copy(alpha = 0.6f))
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = BhqCyan
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "SCAN FOR LOGS",
                    color = BhqCyan,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    fontSize = 14.sp
                )
            }

            // ── Delete verified (conditional) ────────────────────────────────
            if (hasVerified) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BhqRed.copy(alpha = 0.85f))
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    val count = logs.count { it.uploadStatus == UploadStatus.VERIFIED }
                    Text(
                        "DELETE $count VERIFIED FILE(S)",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // ── Status bar ───────────────────────────────────────────────────────
        Surface(color = BhqNavyMid) {
            Text(
                text = statusMessage,
                color = BhqGrey,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                maxLines = 2
            )
        }

        Divider(color = BhqDivider, thickness = 1.dp)

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

// ── Ready To Sync badge ──────────────────────────────────────────────────────
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
            true  -> BhqGreen
            false -> BhqRed
            null  -> BhqAmber
        },
        animationSpec = tween(400),
        label = "dotColor"
    )

    val labelColor by animateColorAsState(
        targetValue = when (serverReachable) {
            true  -> BhqGreen
            false -> BhqRed
            null  -> BhqAmber
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
                tint = BhqCyan,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Barnard", color = BhqWhite, fontWeight = FontWeight.Bold, fontSize = 36.sp)
                Text("HQ",      color = BhqCyan,  fontWeight = FontWeight.Bold, fontSize = 36.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "PROFESSIONAL AERIAL OPERATIONS",
                color = BhqWhite.copy(alpha = 0.65f),
                fontSize = 11.sp,
                letterSpacing = 2.sp
            )
        }
    }
}

// ── Empty state ──────────────────────────────────────────────────────────────
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                tint = BhqGrey.copy(alpha = 0.35f),
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("No logs found", color = BhqGrey, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text("Tap  SCAN FOR LOGS  above", color = BhqGrey.copy(alpha = 0.55f), fontSize = 13.sp)
        }
    }
}

// ── Log file card ────────────────────────────────────────────────────────────
@Composable
private fun LogFileCard(log: FlightLog) {
    val (statusColor, statusLabel, statusIcon) = when (log.uploadStatus) {
        UploadStatus.PENDING   -> Triple(BhqGrey,    "PENDING",   Icons.Default.Schedule)
        UploadStatus.UPLOADING -> Triple(BhqAmber,   "UPLOADING", Icons.Default.CloudUpload)
        UploadStatus.VERIFIED  -> Triple(BhqGreen,   "VERIFIED",  Icons.Default.CheckCircle)
        UploadStatus.DELETED   -> Triple(BhqDeleted, "DELETED",   Icons.Default.Delete)
        UploadStatus.ERROR     -> Triple(BhqRed,     "ERROR",     Icons.Default.Error)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = BhqNavyMid),
        border = BorderStroke(1.dp, BhqNavyLight)
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
                    color = BhqWhite,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${log.dateFormatted}  ·  ${log.sizeFormatted}",
                    color = BhqGrey,
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
