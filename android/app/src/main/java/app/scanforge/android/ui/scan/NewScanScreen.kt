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
import app.scanforge.android.ui.components.screenPadding
import app.scanforge.android.ui.home.HomeViewModel
import app.scanforge.android.ui.theme.SfColors
import app.scanforge.project.ObjectCategory
import app.scanforge.reconstruction.QualityPreset
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
    var mode by rememberSaveable { mutableStateOf("AUTO") }
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
        if (!prefs.simplifiedMode) Panel(title = "Scanning method") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "AUTO", onClick = { mode = "AUTO" }, label = { Text("Automatic") })
                FilterChip(selected = mode == "DEPTH", onClick = { mode = "DEPTH" }, enabled = depthAvailable, label = { Text("Depth") })
                FilterChip(selected = mode == "PHOTO", onClick = { mode = "PHOTO" }, label = { Text("Photo") })
            }
            Text(if (depthAvailable) "Depth: reconstructed on this phone. Photo: images + camera poses for optional desktop photogrammetry."
                else "This device has no ARCore Depth API: only photo capture for optional desktop processing is available.",
                style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
        }
        TextButton(onClick = { showTutorial = true }) { Text("How to scan") }
        Button(
            enabled = name.isNotBlank() && support != null && support != ScanSupport.NOT_SUPPORTED,
            onClick = {
                val effectiveMode = when (mode) { "DEPTH" -> "DEPTH"; "PHOTO" -> "PHOTO"; else -> if (depthAvailable) "DEPTH" else "PHOTO" }
                val p = app.store.create(name, category)
                nav.navigate(Routes.scan(p.id, effectiveMode, preset.name, category.name)) { popUpTo("home") }
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
