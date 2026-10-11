package app.scanforge.android.ui.projects

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.scanforge.android.app
import app.scanforge.android.ui.Routes
import app.scanforge.android.ui.components.ConfirmDialog
import app.scanforge.android.ui.components.KeyValue
import app.scanforge.android.ui.components.Panel
import app.scanforge.android.ui.components.StatusChip
import app.scanforge.android.ui.components.Status
import app.scanforge.android.ui.components.confidenceStatus
import app.scanforge.android.ui.components.humanBytes
import app.scanforge.android.ui.theme.SfColors
import app.scanforge.android.viewer.DisplayMode
import app.scanforge.android.viewer.MeshViewerView
import app.scanforge.export.AxisConvention
import app.scanforge.export.ExportFormat
import app.scanforge.export.ExportOptions
import app.scanforge.export.estimateExportBytes
import app.scanforge.geometry.LengthUnit
import app.scanforge.project.MeasurementRecords
import app.scanforge.project.MeasurementType
import app.scanforge.project.ProjectStatus
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import app.scanforge.reconstruction.ReconstructionFailure
import app.scanforge.project.ProjectDocument

@Composable
fun ProjectDetailScreen(nav: NavController, vm: ProjectViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val unit = context.app.settings.flow.collectAsStateWithLifecycle().value.unit
    val doc = ui.doc
    var viewer by remember { mutableStateOf<MeshViewerView?>(null) }
    var mode by remember { mutableStateOf(DisplayMode.COLORS) }
    var ortho by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(true) }
    var bounds by remember { mutableStateOf(true) }
    var showExport by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf<String?>(null) }
    var confirmDeleteCapture by remember { mutableStateOf(false) }

    LaunchedEffect(ui.mesh, ui.evidence) { ui.mesh?.let { m -> viewer?.meshRenderer?.setMesh(m, ui.evidence) } }
    LaunchedEffect(ui.picked) { viewer?.meshRenderer?.measurementPoints = ui.picked }

    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val viewerPane: @Composable (Modifier) -> Unit = { mod ->
        Box(mod) {
            if (ui.mesh != null) AndroidView(factory = { ctx -> MeshViewerView(ctx, vm::onTapRay).also { v -> viewer = v; ui.mesh?.let { v.meshRenderer.setMesh(it, ui.evidence) } } }, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (doc == null) "Loading…" else "No model yet", color = SfColors.TextSecondary) }
        }
    }
    val panels: @Composable () -> Unit = {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ui.busy?.let { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            ui.message?.let { Text(it, color = SfColors.Warn) }
            if (doc == null) return@Column
            StatusPanel(vm, doc, onReconstruct = { vm.reconstruct(); nav.navigate(Routes.reconstruct(doc.id)) })
            if (ui.mesh != null) {
                Panel(title = "View") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DisplayMode.entries.filter { it != DisplayMode.EVIDENCE || ui.evidence != null }.forEach { m ->
                            FilterChip(selected = mode == m, onClick = { mode = m; viewer?.meshRenderer?.mode = m }, label = { Text(m.name.lowercase().replaceFirstChar(Char::uppercase)) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = ortho, onClick = { ortho = !ortho; viewer?.queueEvent { viewer?.meshRenderer?.camera?.orthographic = ortho } }, label = { Text("Orthographic") })
                        FilterChip(selected = grid, onClick = { grid = !grid; viewer?.meshRenderer?.showGrid = grid }, label = { Text("Grid & axes") })
                        FilterChip(selected = bounds, onClick = { bounds = !bounds; viewer?.meshRenderer?.showBounds = bounds }, label = { Text("Bounds") })
                    }
                    TextButton(onClick = { viewer?.queueEvent { viewer?.meshRenderer?.camera?.reset() } }) { Text("Reset camera (or double-tap)") }
                    if (mode == DisplayMode.EVIDENCE) Text("Cyan: observed by ≥3 frames. Amber: weak evidence. Holes: not observed or failed.", style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
                }
                ModelInfo(doc, ui, unit)
                MeasurePanel(vm, ui, unit, onCalibrate = { calibrating = it })
                EditPanel(vm, doc)
                Panel(title = "Share") {
                    Button(onClick = { showExport = true }, modifier = Modifier.fillMaxWidth()) { Text("Export…") }
                    OutlinedButton(onClick = { vm.sendToBlender() }, modifier = Modifier.fillMaxWidth()) { Text("Send to Blender") }
                }
            }
            if (doc.capture?.retained == true && doc.model.current != null) {
                TextButton(onClick = { confirmDeleteCapture = true }) { Text("Delete source captures to save space…", color = SfColors.Warn) }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(doc?.name ?: "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val canUndo = (doc?.model?.currentVersion ?: 0) > 1
            val canRedo = doc != null && doc.model.currentVersion < doc.model.versions.size
            IconButton(onClick = vm::undo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo edit") }
            IconButton(onClick = vm::redo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo edit") }
        }
        if (wide) Row(Modifier.fillMaxSize()) { viewerPane(Modifier.weight(1f).fillMaxSize()); Box(Modifier.width(380.dp)) { panels() } }
        else Column(Modifier.fillMaxSize()) { viewerPane(Modifier.fillMaxWidth().height(340.dp)); panels() }
    }
    if (showExport && ui.mesh != null) ExportDialog(vm, ui, onDismiss = { showExport = false })
    calibrating?.let { id -> CalibrationDialog(onApply = { meters -> vm.calibrate(id, meters) }, onDismiss = { calibrating = null }) }
    if (confirmDeleteCapture) ConfirmDialog("Delete source captures?",
        "The model, measurements and exports stay. Without the captures you can no longer re-run or refine reconstruction for this project.",
        "Delete captures", onConfirm = { context.app.store.deleteCapture(vm.projectId); vm.reload() }, onDismiss = { confirmDeleteCapture = false })
}

@Composable
private fun StatusPanel(vm: ProjectViewModel, doc: ProjectDocument, onReconstruct: () -> Unit) {
    when (doc.status) {
        ProjectStatus.RECONSTRUCTED -> {}
        ProjectStatus.RECONSTRUCTING -> Panel { StatusChip(Status.NEUTRAL, "Reconstruction in progress") }
        ProjectStatus.CAPTURING -> Panel(title = "Unfinished capture") {
            Text("The app stopped during capture. The saved frames can be reconstructed, but capture cannot continue in a new AR session.", color = SfColors.TextSecondary)
            Button(onClick = onReconstruct) { Text("Reconstruct saved frames") }
        }
        ProjectStatus.CAPTURED, ProjectStatus.FAILED -> Panel(title = if (doc.status == ProjectStatus.FAILED) "Reconstruction failed" else "Captured") {
            doc.failureReason?.let { r ->
                val f = runCatching { ReconstructionFailure.valueOf(r.uppercase()) }.getOrNull()
                Text(f?.userMessage ?: r, color = SfColors.Bad)
                f?.recovery?.forEach { Text("• $it", color = SfColors.TextSecondary) }
            }
            if (doc.capture?.mode == "photo") Text("Photo captures need the optional desktop photogrammetry workflow: export the package with captures (docs/workflows/desktop-photogrammetry.md).", color = SfColors.TextSecondary)
            else if (doc.capture?.retained != false) Button(onClick = onReconstruct) { Text("Reconstruct") }
            OutlinedButton(onClick = { vm.exportPackage(includeCapture = true) }) { Text("Export package with captures") }
        }
    }
}

@Composable
private fun ModelInfo(doc: ProjectDocument, ui: ProjectUi, unit: LengthUnit) {
    val v = doc.model.current ?: return
    val f = doc.scale.factor
    Panel(title = "Model") {
        val size = (0 until 3).map { (v.boundsMax[it] - v.boundsMin[it]) * f }
        KeyValue("Bounding box (W × H × D)", size.joinToString(" × ") { unit.format(it) })
        KeyValue("Triangles / vertices", "${v.triangleCount} / ${v.vertexCount}")
        KeyValue("Stored size", humanBytes(v.file.bytes))
        ui.mesh?.let { KeyValue("PLY export estimate", humanBytes(estimateExportBytes(it, ExportOptions(ExportFormat.PLY)))) }
        KeyValue("Texture", if (v.hasVertexColors) "Vertex colors (no texture map)" else "None")
        KeyValue("Surface", if (v.watertight) "Closed" else "Open, ${v.holeCount} hole(s)")
        KeyValue("Version", "${v.version} of ${doc.model.versions.size} (${v.operation.replace('_', ' ')})")
        KeyValue("Scale", "${doc.scale.source.replace('_', ' ')} × %.4f".format(f))
        doc.scale.relativeSpread?.let { KeyValue("Calibration spread", "%.2f %%".format(it * 100)) }
        doc.reconstruction?.coverage?.let { c ->
            val o = c.jsonObject
            o["view_fraction"]?.jsonPrimitive?.content?.toDoubleOrNull()?.let { KeyValue("Viewing directions covered", "%.0f %%".format(it * 100)) }
            o["strong_vertex_fraction"]?.jsonPrimitive?.content?.toDoubleOrNull()?.let { KeyValue("Strongly observed surface", "%.0f %%".format(it * 100)) }
        }
        doc.reconstruction?.warnings?.forEach { Text("⚠ $it", color = SfColors.Warn, style = MaterialTheme.typography.bodySmall) }
        if (doc.capture?.source == "synthetic") StatusChip(Status.WARN, "Synthetic test data")
    }
}

@Composable
private fun MeasurePanel(vm: ProjectViewModel, ui: ProjectUi, unit: LengthUnit, onCalibrate: (String) -> Unit) {
    val doc = ui.doc ?: return
    Panel(title = "Measure") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = ui.measureMode == MeasureMode.DISTANCE, onClick = { vm.setMeasureMode(if (ui.measureMode == MeasureMode.DISTANCE) MeasureMode.NONE else MeasureMode.DISTANCE) }, label = { Text("Distance") })
            FilterChip(selected = ui.measureMode == MeasureMode.CIRCLE, onClick = { vm.setMeasureMode(if (ui.measureMode == MeasureMode.CIRCLE) MeasureMode.NONE else MeasureMode.CIRCLE) }, label = { Text("Diameter") })
            FilterChip(selected = ui.measureMode == MeasureMode.SECTION, onClick = { vm.setMeasureMode(if (ui.measureMode == MeasureMode.SECTION) MeasureMode.NONE else MeasureMode.SECTION) }, label = { Text("Perimeter") })
        }
        when (ui.measureMode) {
            MeasureMode.DISTANCE -> Text("Tap two points on the model.", color = SfColors.TextSecondary)
            MeasureMode.CIRCLE -> {
                Text("Tap 3 or more points around a round edge (${ui.picked.size} picked).", color = SfColors.TextSecondary)
                Row { Button(onClick = vm::finishCircle, enabled = ui.picked.size >= 3) { Text("Fit circle") }; TextButton(onClick = vm::clearPicked) { Text("Clear") } }
            }
            MeasureMode.SECTION -> Text("Tap a point: the closed outline at that height (parallel to the support surface) is measured.", color = SfColors.TextSecondary)
            MeasureMode.NONE -> {}
        }
        doc.measurements.forEach { m ->
            val values = MeasurementRecords.calibrated(doc, m).joinToString(" × ") { MeasurementRecords.format(it, m.dimension, unit) }
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    StatusChip(confidenceStatus(m.confidence), m.confidence)
                }
                Text(values, style = MaterialTheme.typography.titleMedium, color = SfColors.Text)
                Text((if (m.derived) "Estimated from a fitted/reconstructed shape. " else "Measured on the surface. ") + m.reasons.firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
                m.resolutionLimitMeters?.let { Text("Reconstruction resolution ${unit.format(it)} (a lower bound, not an accuracy)", style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary) }
                Row {
                    if (m.type == MeasurementType.DISTANCE && m.points.size == 2) TextButton(onClick = { onCalibrate(m.id) }) { Text("Use as reference…") }
                    TextButton(onClick = { vm.deleteMeasurement(m.id) }) { Text("Delete", color = SfColors.Bad) }
                }
            }
        }
        if (doc.scale.calibrations.isNotEmpty()) {
            Text("Calibration history", style = MaterialTheme.typography.titleSmall, color = SfColors.TextSecondary)
            doc.scale.calibrations.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = c.active, onCheckedChange = { vm.setCalibrationActive(c.id, it) })
                    Text("${unit.format(c.measuredMeters)} → ${unit.format(c.referenceMeters)} (×%.4f) ${c.createdAt.take(10)}".format(c.factor), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun EditPanel(vm: ProjectViewModel, doc: ProjectDocument) {
    Panel(title = "Edit (non-destructive; the original scan is kept)") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = vm::removeSmallPieces) { Text("Remove fragments") }
            OutlinedButton(onClick = vm::keepLargest) { Text("Keep largest") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = vm::cutBelowSupport, enabled = doc.reconstruction?.supportPlane != null) { Text("Cut below surface") }
            OutlinedButton(onClick = vm::originToBottomCenter) { Text("Origin to base") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.simplify(0.5) }) { Text("Simplify 50 %") }
            OutlinedButton(onClick = { vm.simplify(0.2) }) { Text("Simplify 80 %") }
        }
        Text("Simplification merges nearby vertices (vertex clustering); fine detail below the cluster size is lost.", style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
    }
}

@Composable
private fun ExportDialog(vm: ProjectViewModel, ui: ProjectUi, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var format by remember { mutableStateOf<ExportFormat?>(ExportFormat.PLY) }
    var axis by remember { mutableStateOf(AxisConvention.Y_UP) }
    var unit by remember { mutableStateOf(LengthUnit.METER) }
    var colors by remember { mutableStateOf(true) }
    var simplify by remember { mutableStateOf(false) }
    var withCapture by remember { mutableStateOf(false) }
    val mesh = ui.mesh!!
    val options = format?.let { ExportOptions(it, axis, unit, colors, if (simplify) mesh.triangleCount / 2 else null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportFormat.entries.forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.extension.uppercase()) }) }
                    FilterChip(selected = format == null, onClick = { format = null }, label = { Text("Package") })
                }
                if (format != null && format != ExportFormat.GLB) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = axis == AxisConvention.Y_UP, onClick = { axis = AxisConvention.Y_UP }, label = { Text("Y-up") })
                        FilterChip(selected = axis == AxisConvention.Z_UP, onClick = { axis = AxisConvention.Z_UP }, label = { Text("Z-up") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(LengthUnit.METER, LengthUnit.CENTIMETER, LengthUnit.MILLIMETER, LengthUnit.INCH).forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.symbol) }) }
                    }
                }
                if (format == ExportFormat.GLB) Text("glTF is always meters and Y-up by specification.", style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
                if (format?.supportsColor == true) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(colors, { colors = it }); Text("Include vertex colors") }
                if (format != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(simplify, { simplify = it }); Text("Halve triangle count") }
                if (format == null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(withCapture, { withCapture = it }); Text("Include source captures (large)") }
                options?.let { Text("Estimated size: ${humanBytes(estimateExportBytes(mesh, it))}", color = SfColors.TextSecondary) }
                Text("Real-world scale (including calibration) is preserved.", style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
                ui.busy?.let { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                val result = ui.lastExport?.primaryFile ?: ui.lastPackage
                result?.let { f ->
                    Text("Ready: ${f.name}", color = SfColors.Good)
                    TextButton(onClick = { share(context, f) }) { Text("Share / save…") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (options != null) vm.export(options) else vm.exportPackage(withCapture) }, enabled = ui.busy == null) { Text("Export") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun share(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share ${file.name}"))
}

@Composable
private fun CalibrationDialog(onApply: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(LengthUnit.MILLIMETER) }
    val value = text.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrate scale") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the true length of this distance, measured on the real object (ruler, caliper, or a printed marker of documented size).")
                OutlinedTextField(text, { text = it }, label = { Text("True length") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LengthUnit.entries.forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.symbol) }) }
                }
                Text("One reference corrects overall scale only; local reconstruction errors remain. Adding several references shows how consistent they are.",
                    style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
            }
        },
        confirmButton = { TextButton(enabled = value != null && value > 0, onClick = { onApply(unit.toMeters(value!!)); onDismiss() }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
