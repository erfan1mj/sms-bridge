package com.smsbridge.gateway.ui

import android.content.Intent
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.smsbridge.gateway.sms.SmsPermissionHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermissions: () -> Unit,
    onRequestDefaultSms: (Intent) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showRoleExplanationDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sms,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("SMS Bridge Gateway", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshDiagnostics() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Service & Connection Card
            item {
                ServiceStatusCard(
                    uiState = uiState,
                    onToggleService = { viewModel.toggleService() },
                    onStartPairing = { viewModel.startPairingMode() }
                )
            }

            // 2. SIM Cards Card
            item {
                SimCardsCard(uiState = uiState)
            }

            // 3. Permissions & System Role Card
            item {
                PermissionsCard(
                    uiState = uiState,
                    onRequestPermissions = onRequestPermissions,
                    onExplainRole = { showRoleExplanationDialog = true }
                )
            }

            // 4. Paired Clients Card
            item {
                PairedClientsCard(
                    uiState = uiState,
                    onRevokeClient = { viewModel.revokeClient(it) },
                    onRevokeAll = { viewModel.revokeAllPairings() }
                )
            }

            // 5. Recent Activity
            item {
                Text(
                    text = "Live Activity & Messages",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (uiState.recentMessages.isEmpty() && uiState.recentRequests.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            text = "No message traffic yet. Incoming and outgoing SMS messages will appear here in real time.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(uiState.recentMessages) { msg ->
                    MessageItemCard(msg)
                }
            }
        }
    }

    // QR Code Pairing Dialog
    if (uiState.isPairingModeActive && uiState.pairingQrBitmap != null) {
        Dialog(onDismissRequest = { viewModel.closePairingMode() }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Scan with iPhone",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Open the SMS Bridge app on your iPhone and scan this QR code to securely pair.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Image(
                        bitmap = uiState.pairingQrBitmap!!.asImageBitmap(),
                        contentDescription = "Pairing QR Code",
                        modifier = Modifier
                            .size(240.dp)
                            .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )

                    Text(
                        text = "Token: ${uiState.pairingToken?.take(8)}... (Valid for 5 min)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = { viewModel.closePairingMode() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }

    // Default SMS Role Explanation Dialog
    if (showRoleExplanationDialog) {
        AlertDialog(
            onDismissRequest = { showRoleExplanationDialog = false },
            title = { Text("Default SMS Application Role") },
            text = {
                Text(
                    "Explanation of System SMS Role:\n\n" +
                    "• Current State: The SMS Bridge can send and receive cellular SMS messages using standard Android permissions WITHOUT being the default SMS app.\n\n" +
                    "• Consequence of Becoming Default: If you set SMS Bridge as your default SMS app, your normal messaging app (like Google Messages) will stop handling SMS notifications and MMS.\n\n" +
                    "• Sent SMS History: Android restricts non-default apps from writing to the system message database. Messages sent from iPhone will reach the cellular recipient, but might not appear in your Google Messages history.\n\n" +
                    "Recommendation: Keep your existing messaging app as default unless you want full exclusive takeover."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRoleExplanationDialog = false
                    val intent = SmsPermissionHelper.createRequestDefaultSmsIntent(context)
                    if (intent != null) {
                        onRequestDefaultSms(intent)
                    }
                }) {
                    Text("Change Role Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRoleExplanationDialog = false }) {
                    Text("Keep Current Default")
                }
            }
        )
    }
}

@Composable
fun ServiceStatusCard(
    uiState: MainUiState,
    onToggleService: () -> Unit,
    onStartPairing: () -> Unit
) {
    val isRunning = uiState.serverStatus.isRunning

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isRunning) "Gateway Active" else "Gateway Offline",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isRunning) "${uiState.serverStatus.authenticatedClientsCount} paired iPhone(s) connected" else "Tap switch to start listening",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = isRunning,
                    onCheckedChange = { onToggleService() }
                )
            }

            if (isRunning) {
                HorizontalDivider()
                Text(
                    text = "Local Endpoints:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (uiState.ipAddresses.isEmpty()) {
                    Text(
                        text = "• No Wi-Fi or Hotspot IP detected. Connect to Wi-Fi or enable Android Hotspot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    uiState.ipAddresses.forEach { ip ->
                        val isHotspot = ip.startsWith("192.168.43.")
                        Text(
                            text = "• wss://$ip:${uiState.serverStatus.port}/ws" + (if (isHotspot) " (Android Hotspot)" else " (Wi-Fi)"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onStartPairing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Pair New iPhone (Show QR)")
                }
            }
        }
    }
}

@Composable
fun SimCardsCard(uiState: MainUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Cellular SIM Cards",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (uiState.simCards.isEmpty()) {
                Text(
                    text = if (uiState.hasPhoneStatePermission) "No active SIM card detected" else "Phone state permission needed to view SIM subscriptions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                uiState.simCards.forEach { sim ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${sim.displayName} (${sim.carrierName})",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Slot ${sim.slotIndex + 1} • Sub ID: ${sim.subscriptionId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (sim.isDefault) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("Default SMS", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionsCard(
    uiState: MainUiState,
    onRequestPermissions: () -> Unit,
    onExplainRole: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Permissions & Security",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("SMS Permissions", fontWeight = FontWeight.SemiBold)
                    Text("RECEIVE_SMS, SEND_SMS", style = MaterialTheme.typography.bodySmall)
                }
                if (uiState.hasSmsPermissions) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Granted", tint = MaterialTheme.colorScheme.primary)
                } else {
                    Button(onClick = onRequestPermissions) {
                        Text("Grant")
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Default SMS App Role", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (uiState.isDefaultSmsApp) "Currently Default Handler" else "Not Default (Recommended for Bridge)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onExplainRole) {
                    Text("Explain")
                }
            }
        }
    }
}

@Composable
fun PairedClientsCard(
    uiState: MainUiState,
    onRevokeClient: (String) -> Unit,
    onRevokeAll: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Paired iPhones (${uiState.pairedClients.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (uiState.pairedClients.isNotEmpty()) {
                    TextButton(onClick = onRevokeAll) {
                        Text("Revoke All", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (uiState.pairedClients.isEmpty()) {
                Text(
                    text = "No iPhones paired yet. Put the gateway in pairing mode to display the QR code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                uiState.pairedClients.forEach { client ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(client.clientName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Last seen: ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(client.lastSeenAt))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onRevokeClient(client.clientId) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Revoke", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MessageItemCard(msg: com.smsbridge.gateway.data.MessageEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${if (msg.direction == "INCOMING") "From: " else "To: "}${msg.sender}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = msg.body,
                style = MaterialTheme.typography.bodyMedium
            )
            if (msg.isMultipart) {
                Text(
                    text = "Multipart (${msg.partCount} parts)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
