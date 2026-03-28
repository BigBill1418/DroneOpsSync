package com.droneopssync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droneopssync.app.model.SyncRecord
import com.droneopssync.app.ui.theme.*
import com.droneopssync.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHistoryScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val history by viewModel.syncHistory.collectAsState()
    // Most-recent first
    val sorted = remember(history) { history.sortedByDescending { it.timestamp } }

    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = DocPanel,
            title = { Text("Clear History", color = DocWhite, fontWeight = FontWeight.Bold) },
            text  = { Text("Remove all ${sorted.size} sync record(s)?", color = DocMuted) },
            confirmButton = {
                Button(
                    onClick = { showClearDialog = false; viewModel.clearSyncHistory() },
                    colors = ButtonDefaults.buttonColors(containerColor = DocRed)
                ) { Text("Clear", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = DocMuted)
                }
            }
        )
    }

    Scaffold(
        containerColor = DocDeep,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sync History", color = DocWhite, fontWeight = FontWeight.Bold)
                        Text("${sorted.size} session(s)", color = DocMuted, fontSize = 11.sp)
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
                        onClick = { showClearDialog = true },
                        enabled = sorted.isNotEmpty()
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear history", tint = DocMuted)
                    }
                }
            )
        }
    ) { padding ->
        if (sorted.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No sync sessions yet", color = DocMuted, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Sync logs to see history here", color = DocMuted.copy(alpha = 0.55f), fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sorted, key = { it.timestamp }) { record ->
                    SyncRecordCard(record)
                }
            }
        }
    }
}

@Composable
private fun SyncRecordCard(record: SyncRecord) {
    val hasErrors = record.errors > 0
    val accentColor = when {
        hasErrors && record.imported == 0 -> DocRed
        hasErrors                          -> DocAmber
        else                               -> DocGreen
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = DocPanel),
        border = androidx.compose.foundation.BorderStroke(1.dp, DocSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Colour indicator strip
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(48.dp)
                    .background(accentColor, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.dateFormatted,
                    color = DocWhite,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = record.summary,
                    color = accentColor,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = record.serverUrl,
                    color = DocMuted,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = accentColor.copy(alpha = 0.12f)
            ) {
                Text(
                    text = "${record.filesAttempted} file(s)",
                    color = accentColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
