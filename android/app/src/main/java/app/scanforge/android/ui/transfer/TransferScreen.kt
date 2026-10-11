package app.scanforge.android.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.scanforge.android.app
import app.scanforge.android.transfer.TransferService
import app.scanforge.android.transfer.TransferUiState
import app.scanforge.android.ui.components.ConfirmDialog
import app.scanforge.android.ui.components.KeyValue
import app.scanforge.android.ui.components.Panel
import app.scanforge.android.ui.components.Status
import app.scanforge.android.ui.components.StatusChip
import app.scanforge.android.ui.components.humanBytes
import app.scanforge.android.ui.components.screenPadding
import app.scanforge.android.ui.theme.MonoStyle
import app.scanforge.android.ui.theme.SfColors
import kotlinx.coroutines.delay

@Composable
fun rememberTransferState(): State<TransferUiState> = LocalContext.current.app.transfer.state.collectAsState()

@Composable
fun TransferScreen() {
    val context = LocalContext.current
    val manager = context.app.transfer
    val s by rememberTransferState()
    var sendPicker by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.running) { while (s.running) { manager.refresh(); delay(500) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).screenPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Transfer to Blender", style = MaterialTheme.typography.headlineSmall)
        s.error?.let { Text(it, color = SfColors.Bad) }
        Panel(title = "Connection") {
            StatusChip(if (s.running) Status.GOOD else Status.NEUTRAL, if (s.running) "Ready on this Wi-Fi network" else "Transfer server is off")
            if (!s.running) Button(onClick = { TransferService.start(context) }, modifier = Modifier.fillMaxWidth()) { Text("Connect to Blender") }
            else {
                KeyValue("Address", "${s.address}")
                KeyValue("Port", "${s.port}")
                Text("Pairing code", color = SfColors.TextSecondary)
                Text(s.pairingCode, style = MonoStyle.copy(fontSize = 26.sp), fontWeight = FontWeight.Bold, color = SfColors.Accent)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow new computers to pair", modifier = Modifier.weight(1f))
                    Switch(checked = s.pairingOpen, onCheckedChange = manager::setPairingOpen)
                }
                Text("In Blender: View3D sidebar › ScanForge › enter this address and code › Pair, then approve here.",
                    style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
                OutlinedButton(onClick = { TransferService.stop(context) }) { Text("Stop") }
            }
        }
        s.pendingPairings.forEach { p ->
            Panel(title = "Pairing request") {
                Text("\"${p.request.clientName}\" at ${p.address} wants to receive scans from this phone.")
                Text("Only approve if you just started pairing in Blender on that computer.", color = SfColors.Warn, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { manager.approve(p.requestId) }) { Text("Approve") }
                    OutlinedButton(onClick = { manager.deny(p.requestId) }) { Text("Deny") }
                }
            }
        }
        if (s.running) Panel(title = "Send") {
            Button(onClick = { sendPicker = true }, modifier = Modifier.fillMaxWidth()) { Text("Send projects…") }
            if (s.offers.isEmpty()) Text("Nothing offered yet.", color = SfColors.TextSecondary)
            s.offers.forEach { o ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(o.name, modifier = Modifier.weight(1f))
                        StatusChip(when (o.status) { "verified" -> Status.GOOD; "failed" -> Status.BAD; "in_progress" -> Status.NEUTRAL; else -> Status.NEUTRAL },
                            when (o.status) { "verified" -> "Received & verified"; "failed" -> "Failed"; "in_progress" -> "Sending"; else -> "Waiting for Blender" })
                    }
                    Text(humanBytes(o.bytes), color = SfColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    if (o.status == "in_progress") LinearProgressIndicator(progress = { o.progress }, modifier = Modifier.fillMaxWidth())
                    o.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary) }
                    Row {
                        if (o.status != "verified") TextButton(onClick = { manager.cancel(o.id) }) { Text("Cancel") }
                        if (o.status == "failed") TextButton(onClick = { manager.cancel(o.id); manager.send(o.projectId) }) { Text("Retry") }
                    }
                }
            }
            Text("A transfer is reported as successful only after Blender confirms the file's SHA-256 checksum.", style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
        }
        Panel(title = "Paired computers") {
            if (s.clients.isEmpty()) Text("None", color = SfColors.TextSecondary)
            s.clients.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.clientName)
                        Text("Last seen ${c.lastSeen?.take(19)?.replace('T', ' ') ?: "never"}", style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
                    }
                    TextButton(onClick = { revoke = c.clientId }) { Text("Remove", color = SfColors.Bad) }
                }
            }
        }
        Panel(title = "History") {
            val history = remember(s.offers) { context.app.store.list(includeArchived = true).flatMap { d -> d.transfers.map { d.name to it } }.sortedByDescending { it.second.at }.take(20) }
            if (history.isEmpty()) Text("No transfers yet", color = SfColors.TextSecondary)
            history.forEach { (name, t) -> KeyValue(name, "${t.status} · ${t.peer} · ${t.at.take(16).replace('T', ' ')}") }
        }
        Panel(title = "Troubleshooting") {
            Text("• Phone and computer must be on the same Wi-Fi; guest networks often block devices from seeing each other.", style = MaterialTheme.typography.bodySmall)
            Text("• Blender connects out to the phone, so the computer needs no open inbound port. If a firewall asks, allow Blender to access the local network.", style = MaterialTheme.typography.bodySmall)
            Text("• Traffic is encrypted with TLS; the pairing code proves Blender is talking to this phone. Nothing is sent to the internet.", style = MaterialTheme.typography.bodySmall)
            Text("• No network? Export a package from the project and copy it via USB or file sharing; Blender can import .sf3d files directly.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (sendPicker) {
        val projects = remember { context.app.store.list().filter { it.model.current != null } }
        val chosen = remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { sendPicker = false },
            title = { Text("Send to Blender") },
            text = {
                Column {
                    if (projects.isEmpty()) Text("No reconstructed projects")
                    projects.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(p.id in chosen.value, { on -> chosen.value = if (on) chosen.value + p.id else chosen.value - p.id })
                            Text(p.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = chosen.value.isNotEmpty(), onClick = { chosen.value.forEach { manager.send(it) }; sendPicker = false }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { sendPicker = false }) { Text("Cancel") } },
        )
    }
    revoke?.let { id -> ConfirmDialog("Remove computer?", "It will need to pair again to receive scans.", "Remove", onConfirm = { manager.revoke(id) }, onDismiss = { revoke = null }) }
}
