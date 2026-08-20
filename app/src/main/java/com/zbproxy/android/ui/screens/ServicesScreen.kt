package com.zbproxy.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zbproxy.android.proxy.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    config: RootConfig,
    onUpdateService: (ServiceConfig) -> Unit,
    onDeleteService: (String) -> Unit,
    onUpdateOutbound: (OutboundConfig) -> Unit,
    onDeleteOutbound: (String) -> Unit
) {
    var showServiceDialog by remember { mutableStateOf(false) }
    var showOutboundDialog by remember { mutableStateOf(false) }
    var editingService by remember { mutableStateOf<ServiceConfig?>(null) }
    var editingOutbound by remember { mutableStateOf<OutboundConfig?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab bar
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Services") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Outbounds") }
            )
        }

        when (selectedTab) {
            0 -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(config.services) { service ->
                        ServiceConfigCard(
                            service = service,
                            onEdit = {
                                editingService = service.copy()
                                showServiceDialog = true
                            },
                            onDelete = { onDeleteService(service.name) }
                        )
                    }

                    item {
                        OutlinedButton(
                            onClick = {
                                editingService = ServiceConfig(
                                    name = "New-Service",
                                    listen = 25565,
                                    targetPort = 25565
                                )
                                showServiceDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add Service")
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
            1 -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(config.outbounds) { outbound ->
                        OutboundConfigCard(
                            outbound = outbound,
                            onEdit = {
                                editingOutbound = outbound.copy()
                                showOutboundDialog = true
                            },
                            onDelete = { onDeleteOutbound(outbound.name) }
                        )
                    }

                    item {
                        OutlinedButton(
                            onClick = {
                                editingOutbound = OutboundConfig(
                                    name = "New-Outbound",
                                    targetAddress = "mc.hypixel.net",
                                    targetPort = 25565
                                )
                                showOutboundDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add Outbound")
                        }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    // Service Dialog
    if (showServiceDialog && editingService != null) {
        ServiceEditDialog(
            service = editingService!!,
            onDismiss = { showServiceDialog = false },
            onSave = {
                onUpdateService(it)
                showServiceDialog = false
            }
        )
    }

    // Outbound Dialog
    if (showOutboundDialog && editingOutbound != null) {
        OutboundEditDialog(
            outbound = editingOutbound!!,
            onDismiss = { showOutboundDialog = false },
            onSave = {
                onUpdateOutbound(it)
                showOutboundDialog = false
            }
        )
    }
}

@Composable
fun ServiceConfigCard(
    service: ServiceConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    service.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Listen: 0.0.0.0:${service.listen}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            if (service.targetAddress.isNotEmpty()) {
                Text(
                    "Target: ${service.targetAddress}:${service.targetPort}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            val mc = service.minecraft
            if (mc != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        "Minecraft: ${mc.rewrittenHostname}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun OutboundConfigCard(
    outbound: OutboundConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Router,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    outbound.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Target: ${outbound.targetAddress}:${outbound.targetPort}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            val mc = outbound.minecraft
            if (mc != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        "Minecraft rewrite: ${mc.rewrittenHostname}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceEditDialog(
    service: ServiceConfig,
    onDismiss: () -> Unit,
    onSave: (ServiceConfig) -> Unit
) {
    var name by remember { mutableStateOf(service.name) }
    var listen by remember { mutableStateOf(service.listen.toString()) }
    var targetAddress by remember { mutableStateOf(service.targetAddress) }
    var targetPort by remember { mutableStateOf(service.targetPort.toString()) }
    var enableMinecraft by remember { mutableStateOf(service.minecraft != null) }
    var rewriteHostname by remember { mutableStateOf(service.minecraft?.rewrittenHostname ?: "mc.hypixel.net") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (service.name == "New-Service") "Add Service" else "Edit Service") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Service Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = listen,
                    onValueChange = { listen = it.filter { c -> c.isDigit() } },
                    label = { Text("Listen Port") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = targetAddress,
                    onValueChange = { targetAddress = it },
                    label = { Text("Target Address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = targetPort,
                    onValueChange = { targetPort = it.filter { c -> c.isDigit() } },
                    label = { Text("Target Port") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                Divider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enableMinecraft,
                        onCheckedChange = { enableMinecraft = it }
                    )
                    Text("Enable Minecraft Protocol", style = MaterialTheme.typography.bodyMedium)
                }

                if (enableMinecraft) {
                    OutlinedTextField(
                        value = rewriteHostname,
                        onValueChange = { rewriteHostname = it },
                        label = { Text("Rewrite Hostname") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(service.copy(
                    name = name,
                    listen = listen.toIntOrNull() ?: 25565,
                    targetAddress = targetAddress,
                    targetPort = targetPort.toIntOrNull() ?: 25565,
                    minecraft = if (enableMinecraft) MinecraftServiceConfig(
                        enableHostnameRewrite = true,
                        rewrittenHostname = rewriteHostname
                    ) else null
                ))
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboundEditDialog(
    outbound: OutboundConfig,
    onDismiss: () -> Unit,
    onSave: (OutboundConfig) -> Unit
) {
    var name by remember { mutableStateOf(outbound.name) }
    var targetAddress by remember { mutableStateOf(outbound.targetAddress) }
    var targetPort by remember { mutableStateOf(outbound.targetPort.toString()) }
    var enableMinecraft by remember { mutableStateOf(outbound.minecraft != null) }
    var rewriteHostname by remember { mutableStateOf(outbound.minecraft?.rewrittenHostname ?: "mc.hypixel.net") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (outbound.name == "New-Outbound") "Add Outbound" else "Edit Outbound") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Outbound Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = targetAddress,
                    onValueChange = { targetAddress = it },
                    label = { Text("Target Address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = targetPort,
                    onValueChange = { targetPort = it.filter { c -> c.isDigit() } },
                    label = { Text("Target Port") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                Divider()

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enableMinecraft,
                        onCheckedChange = { enableMinecraft = it }
                    )
                    Text("Minecraft Protocol", style = MaterialTheme.typography.bodyMedium)
                }

                if (enableMinecraft) {
                    OutlinedTextField(
                        value = rewriteHostname,
                        onValueChange = { rewriteHostname = it },
                        label = { Text("Rewrite Hostname") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(outbound.copy(
                    name = name,
                    targetAddress = targetAddress,
                    targetPort = targetPort.toIntOrNull() ?: 25565,
                    minecraft = if (enableMinecraft) MinecraftServiceConfig(
                        enableHostnameRewrite = true,
                        rewrittenHostname = rewriteHostname
                    ) else null
                ))
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}