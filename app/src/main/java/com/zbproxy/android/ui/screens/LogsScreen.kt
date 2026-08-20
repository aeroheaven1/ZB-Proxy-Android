package com.zbproxy.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zbproxy.android.util.LogCollector
import com.zbproxy.android.util.LogEntry
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(logCollector: LogCollector) {
    val logs by logCollector.logs.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var autoScroll by remember { mutableStateOf(true) }
    var filterLevel by remember { mutableStateOf<LogEntry.Level?>(null) }

    val filteredLogs = remember(logs, filterLevel) {
        if (filterLevel == null) logs
        else logs.filter { it.level == filterLevel }
    }

    // Auto scroll to bottom
    LaunchedEffect(filteredLogs.size) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(filteredLogs.size - 1)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Level filter chips
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filterLevel == null,
                    onClick = { filterLevel = null },
                    label = { Text("All", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = filterLevel == LogEntry.Level.INFO,
                    onClick = { filterLevel = if (filterLevel == LogEntry.Level.INFO) null else LogEntry.Level.INFO },
                    label = { Text("Info", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = filterLevel == LogEntry.Level.WARN,
                    onClick = { filterLevel = if (filterLevel == LogEntry.Level.WARN) null else LogEntry.Level.WARN },
                    label = { Text("Warn", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = filterLevel == LogEntry.Level.ERROR,
                    onClick = { filterLevel = if (filterLevel == LogEntry.Level.ERROR) null else LogEntry.Level.ERROR },
                    label = { Text("Error", fontSize = 11.sp) }
                )
            }

            // Actions
            Row {
                IconButton(onClick = { autoScroll = !autoScroll }) {
                    Icon(
                        if (autoScroll) Icons.Filled.VerticalAlignBottom else Icons.Filled.Pause,
                        contentDescription = "Auto-scroll",
                        tint = if (autoScroll) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { logCollector.clear() }) {
                    Icon(Icons.Filled.DeleteSweep, "Clear logs")
                }
            }
        }

        HorizontalDivider()

        // Log list
        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No logs yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Start the proxy to see logs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(filteredLogs) { entry ->
                    LogEntryItem(entry)
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
fun LogEntryItem(entry: LogEntry) {
    val (bgColor, textColor, icon, levelLabel) = when (entry.level) {
        LogEntry.Level.DEBUG -> listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            Icons.Filled.BugReport,
            "DBG"
        )
        LogEntry.Level.INFO -> listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.onSurface,
            Icons.Filled.Info,
            "INF"
        )
        LogEntry.Level.WARN -> listOf(
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Filled.Warning,
            "WRN"
        )
        LogEntry.Level.ERROR -> listOf(
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Filled.Error,
            "ERR"
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        color = (bgColor as androidx.compose.ui.graphics.Color),
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                entry.formattedTime,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = (textColor as androidx.compose.ui.graphics.Color).copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = (textColor as androidx.compose.ui.graphics.Color).copy(alpha = 0.15f),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    (levelLabel as String),
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.dp),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "[${entry.tag}]",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = textColor.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}