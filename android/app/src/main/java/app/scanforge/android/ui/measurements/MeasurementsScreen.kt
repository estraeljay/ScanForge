package app.scanforge.android.ui.measurements

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.scanforge.android.app
import app.scanforge.android.ui.components.Panel
import app.scanforge.android.ui.components.StatusChip
import app.scanforge.android.ui.components.confidenceStatus
import app.scanforge.android.ui.components.screenPadding
import app.scanforge.android.ui.theme.SfColors
import app.scanforge.geometry.LengthUnit
import app.scanforge.project.MeasurementRecords
import app.scanforge.project.MeasurementReport
import app.scanforge.project.ProjectJson
import java.io.File

@Composable
fun MeasurementsScreen() {
    val context = LocalContext.current
    val app = context.app
    val prefs by app.settings.flow.collectAsStateWithLifecycle()
    val projects = remember { app.store.list().filter { it.measurements.isNotEmpty() } }
    var selected by remember { mutableStateOf(projects.firstOrNull()?.id) }
    val doc = selected?.let { id -> runCatching { app.store.load(id) }.getOrNull() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).screenPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Measurements", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LengthUnit.entries.forEach { u -> FilterChip(selected = prefs.unit == u, onClick = { app.settings.update { it.copy(unit = u) } }, label = { Text(u.symbol) }) }
        }
        if (projects.isEmpty()) { Text("No measurements yet. Open a reconstructed project and use Measure.", color = SfColors.TextSecondary); return@Column }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            projects.take(6).forEach { p -> FilterChip(selected = selected == p.id, onClick = { selected = p.id }, label = { Text(p.name) }) }
        }
        doc?.let { d ->
            Panel(title = "Scale") {
                Text("${d.scale.source.replace('_', ' ')} × %.4f".format(d.scale.factor))
                if (d.scale.source == "device_tracking") Text("Not calibrated: values rely on the phone's motion tracking. Add a reference length for verified dimensions.", color = SfColors.Warn, style = MaterialTheme.typography.bodySmall)
            }
            d.measurements.forEach { m ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.label, modifier = Modifier.weight(1f))
                        StatusChip(confidenceStatus(m.confidence), m.confidence)
                    }
                    Text(MeasurementRecords.calibrated(d, m).joinToString(" × ") { MeasurementRecords.format(it, m.dimension, prefs.unit) }, style = MaterialTheme.typography.titleLarge)
                    Text(if (m.derived) "Estimated (derived)" else "Measured directly", color = SfColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    m.reasons.forEach { Text("• $it", color = SfColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { shareText(context, File(app.filesDir, "scanforge/exports/reports/${d.id}.csv"), MeasurementReport.csv(d, prefs.unit), "text/csv") }) { Text("Export CSV") }
                OutlinedButton(onClick = {
                    shareText(context, File(app.filesDir, "scanforge/exports/reports/${d.id}.json"),
                        ProjectJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), MeasurementReport.json(d, prefs.unit)), "application/json")
                }) { Text("Export JSON") }
            }
        }
    }
}

private fun shareText(context: android.content.Context, file: File, text: String, mime: String) {
    file.parentFile?.mkdirs(); file.writeText(text)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share report"))
}
