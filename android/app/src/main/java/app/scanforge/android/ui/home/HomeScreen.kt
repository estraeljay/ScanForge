package app.scanforge.android.ui.home

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.scanforge.android.BuildConfig
import app.scanforge.android.app
import app.scanforge.android.capability.DeviceCapabilities
import app.scanforge.android.capability.DeviceReport
import app.scanforge.android.capability.ScanSupport
import app.scanforge.android.ui.Routes
import app.scanforge.android.ui.components.KeyValue
import app.scanforge.android.ui.components.Panel
import app.scanforge.android.ui.components.Status
import app.scanforge.android.ui.components.StatusChip
import app.scanforge.android.ui.components.humanBytes
import app.scanforge.android.ui.components.screenPadding
import app.scanforge.android.ui.theme.SfColors
import app.scanforge.android.ui.transfer.rememberTransferState
import app.scanforge.project.ProjectDocument
import app.scanforge.project.ProjectStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    val report = MutableStateFlow<DeviceReport?>(capabilityCache)
    val projects = MutableStateFlow<List<ProjectDocument>>(emptyList())

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            projects.value = getApplication<Application>().app.store.list()
            if (capabilityCache == null) {
                capabilityCache = DeviceCapabilities.assess(getApplication(), getApplication<Application>().filesDir)
            }
            report.value = capabilityCache
        }
    }

    companion object {
        /** Capability assessment is stable for the process lifetime; re-run from Settings if needed. */
        @Volatile var capabilityCache: DeviceReport? = null
    }
}

@Composable
fun HomeScreen(nav: NavController, vm: HomeViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.refresh() }
    val report by vm.report.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val transfer by rememberTransferState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).screenPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(BuildConfig.APP_NAME, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        val canScan = report?.support != ScanSupport.NOT_SUPPORTED
        Button(onClick = { nav.navigate("newScan") }, enabled = report != null && canScan, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.Add, null); Text("  New Scan")
        }
        projects.firstOrNull { it.status == ProjectStatus.CAPTURED || it.status == ProjectStatus.FAILED || it.status == ProjectStatus.CAPTURING }?.let { p ->
            OutlinedButton(onClick = { nav.navigate(Routes.project(p.id)) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.PlayArrow, null); Text("  Resume recent: ${p.name}")
            }
        }
        Panel(title = "Device capabilities") {
            val r = report
            if (r == null) StatusChip(Status.NEUTRAL, "Checking device…")
            else {
                when (r.support) {
                    ScanSupport.DEPTH_SCANNING -> StatusChip(Status.GOOD, "ARCore depth and marker-board scanning")
                    ScanSupport.MARKER_SCANNING -> StatusChip(Status.GOOD, "Marker-board scanning (no ARCore needed)")
                    ScanSupport.NOT_SUPPORTED -> StatusChip(Status.BAD, "Scanning not supported on this device")
                }
                KeyValue("Device", r.model)
                KeyValue("Android", "${r.androidVersion} (API ${r.sdkInt})")
                KeyValue("ARCore", r.arCore.name.lowercase().replace('_', ' '))
                KeyValue("Depth API", when (r.depthApi) { true -> "yes"; false -> "no"; null -> "unknown" })
                KeyValue("Hardware depth sensor", when (r.hardwareDepthSensor) { true -> "yes"; false -> "not used by ARCore"; null -> "unknown" })
                KeyValue("Memory", humanBytes(r.totalRamBytes))
                r.fusionBenchmarkMs?.let { KeyValue("Fusion benchmark", "%.1f ms / depth frame".format(it)) }
                r.warnings().forEach { Text("• $it", color = SfColors.Warn, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Panel(title = "Storage") {
            report?.let { KeyValue("Free", humanBytes(it.freeStorageBytes)) }
            KeyValue("Projects", projects.size.toString())
        }
        Panel(title = "Blender connection") {
            StatusChip(if (transfer.running) Status.GOOD else Status.NEUTRAL, if (transfer.running) "Ready on ${transfer.address}:${transfer.port}" else "Transfer server off")
            KeyValue("Paired computers", transfer.clients.size.toString())
            KeyValue("Last successful transfer", transfer.lastSuccess ?: "none in this session")
        }
        if (projects.isNotEmpty()) Panel(title = "Recent projects") {
            projects.take(4).forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.project(p.id)) }, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(p.name); Text(p.status.name.lowercase(), color = SfColors.TextSecondary)
                }
            }
        }
    }
}
