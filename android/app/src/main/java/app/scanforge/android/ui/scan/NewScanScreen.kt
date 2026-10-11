package app.scanforge.android.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.scanforge.android.app
import app.scanforge.android.capability.ScanSupport
import app.scanforge.android.ui.Routes
import app.scanforge.android.ui.components.Panel
import app.scanforge.android.ui.components.Status
import app.scanforge.android.ui.components.StatusChip
import app.scanforge.android.ui.components.screenPadding
import app.scanforge.android.ui.home.HomeViewModel
import app.scanforge.android.ui.theme.SfColors
import app.scanforge.project.ObjectCategory
import app.scanforge.reconstruction.QualityPreset
import app.scanforge.markers.BoardLayout
import app.scanforge.markers.BoardPdf
import app.scanforge.markers.BoardSpec
import app.scanforge.markers.PaperSize
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private fun categoryLabel(c: ObjectCategory) = c.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

private val presetHelp = mapOf(
    QualityPreset.DRAFT to "Fast capture and processing. Rough proportions and visualization.",
    QualityPreset.STANDARD to "Balanced detail, color and processing time. General Blender assets.",
    QualityPreset.HIGH to "More frames and finer voxels: more detail, more memory, storage and time. " +
        "It does not by itself make dimensions more accurate.",
)

@Composable
fun NewScanScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.app
    val prefs by app.settings.flow.collectAsState()
    val support = HomeViewModel.capabilityCache?.support
    val depthAvailable = support == ScanSupport.DEPTH_SCANNING
    var name by rememberSaveable { mutableStateOf("Scan " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"))) }
    var category by rememberSaveable { mutableStateOf(ObjectCategory.HOUSEHOLD_OBJECT) }
    var preset by rememberSaveable { mutableStateOf(prefs.defaultPreset) }
    // Marker board is the default: it works on every phone with a camera; ARCore depth is offered where supported.
    var mode by rememberSaveable { mutableStateOf("MARKER") }
    var showTutorial by remember { mutableStateOf(!prefs.tutorialSeen) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).screenPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("New scan", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Panel(title = "Object category (organizational only)") {
            ObjectCategory.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(categoryLabel(c)) }) }
                }
            }
            Text("Transparent, reflective, very dark, textureless, moving or repetitive surfaces are hard to reconstruct in any category.",
                style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
        }
        Panel(title = "Quality") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityPreset.entries.forEach { p -> FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(p.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
            }
            Text(presetHelp.getValue(preset), style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
        }
        Panel(title = "Scanning method") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "MARKER", onClick = { mode = "MARKER" }, label = { Text("Marker board") })
                FilterChip(selected = mode == "DEPTH", onClick = { mode = "DEPTH" }, enabled = depthAvailable, label = { Text("ARCore depth") })
            }
            Text(
                if (mode == "MARKER") "Place the object on the printed board and photograph it from all sides. Works without ARCore; " +
                    "scale comes from the printed board. Best for objects up to A3 size with some surface texture."
                else "Uses ARCore depth while you walk around the object. Good for larger objects; scale from motion tracking.",
                style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary,
            )
        }
        if (mode == "MARKER") BoardPanel()
        TextButton(onClick = { showTutorial = true }) { Text("How to scan") }
        Button(
            enabled = name.isNotBlank() && support != null && support != ScanSupport.NOT_SUPPORTED,
            onClick = {
                val p = app.store.create(name, category)
                if (mode == "DEPTH" && depthAvailable) nav.navigate(Routes.scan(p.id, "DEPTH", preset.name, category.name)) { popUpTo("home") }
                else nav.navigate(Routes.markerScan(p.id, preset.name, BoardSpec.preset(prefs.boardSize).code)) { popUpTo("home") }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Continue to scanner") }
    }
    if (showTutorial) TutorialDialog { showTutorial = false; app.settings.update { it.copy(tutorialSeen = true) } }
}

@Composable
fun TutorialDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scanning in 5 steps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Put the object on a textured table in even, bright light.")
                Text("2. Move the phone slowly until a surface is detected, then tap where the object stands.")
                Text("3. Press Start and walk all the way around, 30–80 cm away.")
                Text("4. Circle again from higher up, then capture the top. Follow the on-screen guidance.")
                Text("5. Press Finish. Reconstruction runs on the phone; you can leave the screen.")
                Text("Shiny, transparent, very dark or plain surfaces may not reconstruct.", color = SfColors.Warn)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}


/** Board size choice, printable PDF and print-scale check for marker scanning. */
@Composable
fun BoardPanel() {
    val context = LocalContext.current
    val app = context.app
    val prefs by app.settings.flow.collectAsState()
    val spec = BoardSpec.preset(prefs.boardSize)
    var checking by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    val checked = remember(spec.code, version) { app.settings.boardScaleChecked(spec.code) }
    val scale = remember(spec.code, version) { app.settings.boardScale(spec.code) }
    Panel(title = "Marker board") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoardSpec.PRESETS.forEach { b -> FilterChip(selected = spec == b, onClick = { app.settings.update { it.copy(boardSize = b.paper.name) } }, label = { Text(b.paper.name) }) }
        }
        Text(when (spec.paper) {
            PaperSize.A4 -> "A4 board: objects up to about 15 × 20 cm footprint."
            PaperSize.A3 -> "A3 board: objects up to about 20 × 28 cm footprint."
            PaperSize.A2 -> "A2 board: objects up to an A3 footprint (30 × 42 cm). Print at a copy shop if needed."
        }, style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openBoardPdf(context, spec) }) { Text("Print board (PDF)") }
            TextButton(onClick = { checking = true }) { Text("Check print scale") }
        }
        if (checked) StatusChip(Status.GOOD, "Print scale checked: %.1f %%".format(scale * 100))
        else StatusChip(Status.WARN, "Print scale not checked (assumed 100 %)")
    }
    if (checking) {
        var text by remember { mutableStateOf("100") }
        val mm = text.replace(',', '.').toDoubleOrNull()
        AlertDialog(
            onDismissRequest = { checking = false },
            title = { Text("Check print scale") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Measure the bar labelled \"100 mm\" on the printed board with a ruler and enter its length.")
                    OutlinedTextField(text, { text = it }, label = { Text("Measured length (mm)") }, singleLine = true)
                    Text("Printers often shrink pages slightly (\"fit to page\"). This corrects the scale of every scan made with this board.",
                        style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
                }
            },
            confirmButton = {
                TextButton(enabled = mm != null && mm in 90.0..110.0, onClick = {
                    app.settings.setBoardScale(spec.code, mm!! / 100.0); version++; checking = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { checking = false }) { Text("Cancel") } },
        )
    }
}

private fun openBoardPdf(context: android.content.Context, spec: BoardSpec) {
    val file = java.io.File(context.filesDir, "scanforge/exports/boards/${spec.code}.pdf")
    file.parentFile?.mkdirs()
    file.writeBytes(BoardPdf.render(BoardLayout(spec)))
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/pdf").putExtra(android.content.Intent.EXTRA_STREAM, uri)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(send, "Print or save the board").putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS, arrayOf(view)))
}
