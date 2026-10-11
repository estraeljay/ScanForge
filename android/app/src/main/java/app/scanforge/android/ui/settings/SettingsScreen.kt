package app.scanforge.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanforge.android.BuildConfig
import app.scanforge.android.app
import app.scanforge.android.ui.components.ConfirmDialog
import app.scanforge.android.ui.components.KeyValue
import app.scanforge.android.ui.components.Panel
import app.scanforge.android.ui.components.humanBytes
import app.scanforge.android.ui.components.screenPadding
import app.scanforge.android.ui.home.HomeViewModel
import app.scanforge.android.ui.scan.TutorialDialog
import app.scanforge.android.ui.theme.SfColors
import app.scanforge.geometry.LengthUnit
import app.scanforge.reconstruction.QualityPreset

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.app
    val prefs by app.settings.flow.collectAsStateWithLifecycle()
    var tutorial by remember { mutableStateOf(false) }
    var deleteCaptureOf by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val projects = remember(refresh) { app.store.list(includeArchived = true).map { it to app.store.sizeOf(app.store.captureDir(it.id)) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).screenPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Panel(title = "Units") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LengthUnit.entries.forEach { u -> FilterChip(selected = prefs.unit == u, onClick = { app.settings.update { it.copy(unit = u) } }, label = { Text(u.symbol) }) }
            }
            Text("All values are stored in meters internally and converted only for display and export.", style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
        }
        Panel(title = "Scanning") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QualityPreset.entries.forEach { p -> FilterChip(selected = prefs.defaultPreset == p, onClick = { app.settings.update { it.copy(defaultPreset = p) } }, label = { Text(p.name.lowercase()) }) }
            }
            Toggle("Simplified interface for beginners", prefs.simplifiedMode) { v -> app.settings.update { it.copy(simplifiedMode = v) } }
            Toggle("Haptic feedback", prefs.haptics) { v -> app.settings.update { it.copy(haptics = v) } }
            TextButton(onClick = { tutorial = true }) { Text("Show scanning tutorial") }
        }
        Panel(title = "Storage") {
            KeyValue("Total used", humanBytes(app.store.sizeOf(app.store.root)))
            Text("Source captures are needed to re-run reconstruction. Deleting them keeps the model, measurements and exports.",
                style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
            projects.filter { it.second > 0 }.forEach { (p, bytes) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.name}: captures ${humanBytes(bytes)}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { deleteCaptureOf = p.id }) { Text("Delete", color = SfColors.Warn) }
                }
            }
        }
        Panel(title = "Device") {
            val r = HomeViewModel.capabilityCache
            if (r == null) Text("Open the Scan tab to run the capability check.") else {
                KeyValue("Model", r.model); KeyValue("Support", r.support.name.lowercase().replace('_', ' '))
                KeyValue("Raw depth API", "${r.rawDepthApi ?: "unknown"}"); KeyValue("Camera autofocus", "${r.camera.autofocus}")
                KeyValue("Free storage", humanBytes(r.freeStorageBytes)); KeyValue("CPU cores", "${r.cpuCores}")
            }
            TextButton(onClick = { HomeViewModel.capabilityCache = null }) { Text("Re-check on next visit to Scan") }
        }
        Panel(title = "Privacy") {
            Text("Scans stay on this device. Nothing is uploaded to any server. Transfers go only to computers you pair on your local network.")
        }
        Panel(title = "About") {
            KeyValue("App", "${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME}")
            Text("Uses Google ARCore (Google APIs Terms of Service), AndroidX and Jetpack Compose (Apache 2.0), Kotlin and kotlinx.serialization (Apache 2.0). See docs/dependencies-and-licenses.md.",
                style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
        }
    }
    if (tutorial) TutorialDialog(marker = true) { tutorial = false }
    deleteCaptureOf?.let { id ->
        ConfirmDialog("Delete source captures?", "Re-running or refining the reconstruction of this project will no longer be possible.", "Delete",
            onConfirm = { app.store.deleteCapture(id); refresh++ }, onDismiss = { deleteCaptureOf = null })
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f)); Switch(checked = value, onCheckedChange = onChange) }
}
