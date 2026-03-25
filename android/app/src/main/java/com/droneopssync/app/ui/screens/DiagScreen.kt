package com.droneopssync.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droneopssync.app.model.DiagLevel
import com.droneopssync.app.model.DiagLog
import com.droneopssync.app.ui.theme.*
import com.droneopssync.app.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val diagLogs by viewModel.diagLogs.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(diagLogs.size) {
        if (diagLogs.isNotEmpty()) listState.animateScrollToItem(diagLogs.size - 1)
    }

    Scaffold(
        containerColor = DocDeep,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Diagnostics", color = DocWhite, fontWeight = FontWeight.Bold)
                        Text("${diagLogs.size} entries", color = DocMuted, fontSize = 11.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DocPanel),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = DocMuted)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val text = buildExportText(diagLogs)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "DroneOpsSync Diagnostics")
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Diagnostics"))
                        },
                        enabled = diagLogs.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export", tint = DocCyan)
                    }
                    IconButton(
                        onClick = { viewModel.clearDiagLogs() },
                        enabled = diagLogs.isNotEmpty()
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = DocMuted)
                    }
                }
            )
        }
    ) { padding ->
        if (diagLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No log entries yet", color = DocMuted, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Scan or sync to generate logs", color = DocMuted.copy(alpha = 0.55f), fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(diagLogs) { entry ->
                    DiagEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun DiagEntryRow(entry: DiagLog) {
    val levelColor = when (entry.level) {
        DiagLevel.ERROR -> DocRed
        DiagLevel.WARN  -> DocAmber
        DiagLevel.INFO  -> DocMuted
    }
    val tagColor = when (entry.level) {
        DiagLevel.ERROR -> DocRed
        DiagLevel.WARN  -> DocAmber
        DiagLevel.INFO  -> DocCyan.copy(alpha = 0.7f)
    }
    val tsFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = when (entry.level) {
                    DiagLevel.ERROR -> DocRed.copy(alpha = 0.06f)
                    DiagLevel.WARN  -> DocAmber.copy(alpha = 0.04f)
                    DiagLevel.INFO  -> DocPanel.copy(alpha = 0.5f)
                },
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Timestamp
        Text(
            text = tsFormat.format(Date(entry.timestamp)),
            color = DocMuted.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(76.dp)
        )
        // Tag badge
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = tagColor.copy(alpha = 0.14f),
            modifier = Modifier.width(52.dp)
        ) {
            Text(
                text = entry.tag,
                color = tagColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        // Message
        Text(
            text = entry.message,
            color = levelColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun buildExportText(logs: List<DiagLog>): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    return buildString {
        appendLine("DroneOpsSync — Diagnostic Log")
        appendLine("Exported: ${sdf.format(Date())}")
        appendLine("Entries: ${logs.size}")
        appendLine("=".repeat(60))
        appendLine()
        logs.forEach { entry ->
            appendLine("[${sdf.format(Date(entry.timestamp))}] [${entry.level.name.padEnd(5)}] [${entry.tag}] ${entry.message}")
        }
    }
}
