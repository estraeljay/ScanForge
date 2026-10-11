package app.scanforge.android.ui.scan

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.scanforge.android.app
import app.scanforge.android.capture.CaptureEngine
import app.scanforge.android.capture.ScanHudState
import app.scanforge.android.capture.ScanPhase
import app.scanforge.android.capture.ScanRenderer
import app.scanforge.android.capture.ScanSetup
import app.scanforge.android.reconstruction.ReconstructionWorker
import app.scanforge.android.ui.Routes
import app.scanforge.android.ui.components.Status
import app.scanforge.android.ui.components.StatusChip
import app.scanforge.android.ui.components.color
import app.scanforge.android.ui.components.humanBytes
import app.scanforge.android.ui.theme.SfColors
import app.scanforge.project.ObjectCategory
import app.scanforge.reconstruction.CaptureMode
import app.scanforge.reconstruction.LiveGuidance
import app.scanforge.reconstruction.QualityPreset
import app.scanforge.reconstruction.TrackingQuality
import com.google.ar.core.ArCoreApk

class ScanViewModel(application: Application, handle: SavedStateHandle) : AndroidViewModel(application) {
    val engine = CaptureEngine(
        application.app,
        ScanSetup(
            projectId = handle.get<String>("projectId")!!,
            mode = CaptureMode.valueOf(handle.get<String>("mode") ?: "DEPTH"),
            preset = QualityPreset.valueOf(handle.get<String>("preset") ?: "STANDARD"),
            category = ObjectCategory.valueOf(handle.get<String>("category") ?: "OTHER"),
        ),
    )
    var installRequested = false

    override fun onCleared() { engine.close() }
}

private fun guidanceText(g: LiveGuidance, s: ScanHudState): Pair<Status, String> = when (g) {
    LiveGuidance.INITIALIZING -> Status.NEUTRAL to when (s.phase) {
        ScanPhase.PREPARING -> "Move the phone slowly to start tracking"
        ScanPhase.PLACING -> if (s.planesDetected) "Tap the surface where the object stands" else "Point at the table around the object"
        ScanPhase.READY -> "Press Start, then move slowly around the object"
        else -> "Initializing"
    }
    LiveGuidance.TRACKING_LOST -> Status.BAD to (s.trackingFailure ?: "Tracking lost: return to the previously scanned area")
    LiveGuidance.MOVE_SLOWER -> Status.WARN to "Move more slowly"
    LiveGuidance.MOVE_CLOSER -> Status.WARN to "Move closer to the object"
    LiveGuidance.MOVE_FARTHER -> Status.WARN to "Move farther away"
    LiveGuidance.CONTINUE_AROUND -> Status.WARN to "Keep walking around the object" + gapArrow(s)
    LiveGuidance.RAISE_ANGLE -> Status.WARN to "Circle again from a higher angle" + gapArrow(s)
    LiveGuidance.CAPTURE_TOP -> Status.WARN to "Capture the top surface from above"
    LiveGuidance.IMPROVE_LIGHT -> Status.WARN to "Improve lighting"
    LiveGuidance.COMPLETE -> Status.GOOD to "Measured coverage is good. Capture the underside if needed, then Finish"
}

/** Positive angle = the nearest unobserved sector lies to the user's left (see ViewCoverage.nearestGap). */
private fun gapArrow(s: ScanHudState): String = s.nearestGapRadians?.let { if (it > 0) "  ←" else "  →" } ?: ""

@Composable
fun ScanScreen(nav: NavController, vm: ScanViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = context as Activity
    val app = context.app
    val prefs by app.settings.flow.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    fun buzz() { if (prefs.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    val state by vm.engine.state.collectAsStateWithLifecycle()
    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    var showHelp by remember { mutableStateOf(false) }
    var showPoints by remember { mutableStateOf(true) }
    var showCoverage by remember { mutableStateOf(true) }
    val renderer = remember { ScanRenderer(activity, vm.engine) }
    // Plain holder (not state): the lifecycle observer must not be re-registered when the view is created.
    val glRef = remember { arrayOfNulls<GLSurfaceView>(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    if (!hasCamera) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Camera access is needed to scan.", style = MaterialTheme.typography.titleMedium)
            Text("Images are processed and stored only on this device unless you send them.", color = SfColors.TextSecondary)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
        }
        return
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Ensure Google Play Services for AR is installed (may open the Play Store and return here).
                    val install = runCatching { ArCoreApk.getInstance().requestInstall(activity, !vm.installRequested) }
                    if (install.getOrNull() == ArCoreApk.InstallStatus.INSTALL_REQUESTED) { vm.installRequested = true; return@LifecycleEventObserver }
                    install.exceptionOrNull()?.let { vm.engine.fail("Google Play Services for AR is unavailable: ${it.message}"); return@LifecycleEventObserver }
                    renderer.resume()?.let { vm.engine.fail(it) }
                    glRef[0]?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> { vm.engine.pause(); glRef[0]?.onPause(); renderer.pause() }
                else -> {}
            }
        }
        // addObserver replays ON_CREATE/ON_START/ON_RESUME up to the current state, so no manual dispatch is needed.
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) { onDispose { renderer.destroy() } }

    val wide = LocalConfiguration.current.screenWidthDp >= 600
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    preserveEGLContextOnPause = true
                    setEGLContextClientVersion(2)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    keepScreenOn = true
                    contentDescription = "Camera view. Tap the surface where the object stands to place the scan region."
                    setOnTouchListener { v, e -> if (e.action == MotionEvent.ACTION_UP) { renderer.onTap(e.x, e.y); v.performClick() }; true }
                    glRef[0] = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxSize()) {
                Hud(state, onBack = { nav.popBackStack() }, onHelp = { showHelp = true })
                Controls(
                    state, wide,
                    showPoints = showPoints, showCoverage = showCoverage,
                    onTogglePoints = { showPoints = it; renderer.showPoints = it },
                    onToggleCoverage = { showCoverage = it; renderer.showCoverage = it },
                    onPrimary = {
                        buzz()
                        when (state.phase) {
                            ScanPhase.READY -> vm.engine.start(renderer.deviceInfo())
                            ScanPhase.SCANNING -> vm.engine.pause()
                            ScanPhase.PAUSED -> vm.engine.resume()
                            else -> {}
                        }
                    },
                    onUndo = { vm.engine.undoRecent(3) },
                    onFinish = {
                        buzz()
                        if (vm.engine.finish()) {
                            val id = vm.engine.setup.projectId
                            if (vm.engine.setup.mode == CaptureMode.DEPTH) {
                                ReconstructionWorker.enqueue(context, id)
                                nav.navigate(Routes.reconstruct(id)) { popUpTo("home") }
                            } else nav.navigate(Routes.project(id)) { popUpTo("home") }
                        }
                    },
                    onRoiSize = { vm.engine.setRoiHalfSize(it.toDouble()) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (wide) SidePanel(state, vm.engine.setup, Modifier.width(300.dp).fillMaxSize())
        }
    }
    if (showHelp) HelpDialog { showHelp = false }
}

@Composable
private fun Hud(s: ScanHudState, onBack: () -> Unit, onHelp: () -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Leave scanner", tint = SfColors.Text) }
            when (s.tracking) {
                TrackingQuality.TRACKING -> StatusChip(Status.GOOD, "Tracking")
                null -> StatusChip(Status.NEUTRAL, "Initializing")
                else -> StatusChip(Status.BAD, "Tracking lost")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onHelp) { Icon(Icons.Filled.HelpOutline, "Scanning help", tint = SfColors.Text) }
        }
        if (s.phase == ScanPhase.SCANNING || s.phase == ScanPhase.PAUSED) {
            Surface(color = SfColors.Panel.copy(alpha = 0.85f), shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Stat("Time", "%d:%02d".format(s.durationMs / 60000, (s.durationMs / 1000) % 60))
                    Stat("Frames", "${s.keyframes}" + if (s.depthFrames != s.keyframes) " (${s.depthFrames} depth)" else "")
                    Stat("Stored", humanBytes(s.bytesWritten))
                    if (s.pendingWrites > 0) Stat("Saving", "${s.pendingWrites}")
                    CoverageRing(s)
                }
            }
        }
        val (status, text) = guidanceText(s.guidance, s)
        Surface(color = SfColors.Panel.copy(alpha = 0.9f), shape = RoundedCornerShape(10.dp)) {
            Text(text, Modifier.padding(12.dp), color = status.color(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        s.message?.let { Text(it, color = SfColors.Warn, style = MaterialTheme.typography.bodyMedium) }
        s.error?.let { Text(it, color = SfColors.Bad, style = MaterialTheme.typography.bodyLarge) }
        if (s.phase in listOf(ScanPhase.PREPARING, ScanPhase.PLACING, ScanPhase.READY)) Checklist(s, Modifier.widthIn(max = 360.dp))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SfColors.TextSecondary)
        Text(value, style = MaterialTheme.typography.titleSmall, color = SfColors.Text)
    }
}

/** Measured view coverage (outer value) and share of strongly observed surface (label). */
@Composable
private fun CoverageRing(s: ScanHudState) {
    val status = when {
        s.viewFraction >= 0.75 && s.strongSurfaceFraction >= 0.7 -> Status.GOOD
        s.keyframes == 0 -> Status.NEUTRAL
        else -> Status.WARN
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { s.viewFraction.toFloat() }, color = status.color(), trackColor = SfColors.Border, modifier = Modifier.size(36.dp))
            Text("%.0f".format(s.viewFraction * 100), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(6.dp))
        Stat("Coverage", "views · %.0f%% strong".format(s.strongSurfaceFraction * 100))
    }
}

@Composable
private fun Checklist(s: ScanHudState, modifier: Modifier) {
    Surface(color = SfColors.Panel.copy(alpha = 0.9f), shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Before you start", style = MaterialTheme.typography.titleSmall, color = SfColors.TextSecondary)
            StatusChip(if (s.tracking == TrackingQuality.TRACKING) Status.GOOD else Status.NEUTRAL, "Camera tracking")
            val light = s.lightIntensity
            StatusChip(when { light == null -> Status.NEUTRAL; light < 0.12f -> Status.BAD; light < 0.25f -> Status.WARN; else -> Status.GOOD },
                when { light == null -> "Lighting: measuring"; light < 0.12f -> "Lighting: too dark"; light < 0.25f -> "Lighting: dim"; else -> "Lighting: good" })
            StatusChip(if (s.planesDetected) Status.GOOD else Status.NEUTRAL, if (s.planesDetected) "Surface detected" else "Looking for a surface")
            StatusChip(if (s.roiPlaced) Status.GOOD else Status.NEUTRAL, if (s.roiPlaced) "Scan region placed" else "Tap the object's base to place the region")
            Text("Recommended distance 30–80 cm. Avoid shiny, transparent, very dark, plain or moving objects.",
                style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
        }
    }
}

@Composable
private fun Controls(
    s: ScanHudState, wide: Boolean, showPoints: Boolean, showCoverage: Boolean,
    onTogglePoints: (Boolean) -> Unit, onToggleCoverage: (Boolean) -> Unit,
    onPrimary: () -> Unit, onUndo: () -> Unit, onFinish: () -> Unit, onRoiSize: (Float) -> Unit, modifier: Modifier,
) {
    Column(modifier.fillMaxWidth().navigationBarsPadding().background(SfColors.Charcoal.copy(alpha = 0.55f)).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.phase == ScanPhase.PLACING || s.phase == ScanPhase.READY) {
            Text("Scan region size: %.0f cm".format(s.roiHalfSize * 200), color = SfColors.TextSecondary)
            Slider(value = s.roiHalfSize.toFloat(), onValueChange = onRoiSize, valueRange = 0.05f..1.5f, modifier = Modifier.widthIn(max = 420.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = showPoints, onCheckedChange = onTogglePoints) { Icon(Icons.Filled.Grain, "Toggle preview points", tint = if (showPoints) SfColors.Accent else SfColors.TextSecondary) }
            IconToggleButton(checked = showCoverage, onCheckedChange = onToggleCoverage) { Icon(Icons.Filled.Layers, "Toggle coverage colors", tint = if (showCoverage) SfColors.Accent else SfColors.TextSecondary) }
            FilledTonalButton(onClick = onUndo, enabled = s.keyframes > 0 && s.phase != ScanPhase.SCANNING) { Icon(Icons.AutoMirrored.Filled.Undo, null); Text(" 3 s") }
            val (icon, label) = when (s.phase) {
                ScanPhase.SCANNING -> Icons.Filled.Pause to "Pause"
                ScanPhase.PAUSED -> Icons.Filled.PlayArrow to "Resume"
                else -> Icons.Filled.PlayArrow to "Start"
            }
            Button(onClick = onPrimary, enabled = s.phase in listOf(ScanPhase.READY, ScanPhase.SCANNING, ScanPhase.PAUSED), modifier = Modifier.height(64.dp)) {
                Icon(icon, null); Text(" $label", style = MaterialTheme.typography.titleMedium)
            }
            Button(onClick = onFinish, enabled = s.keyframes > 0 && (s.phase == ScanPhase.SCANNING || s.phase == ScanPhase.PAUSED), modifier = Modifier.height(64.dp)) {
                Icon(Icons.Filled.Check, null); Text(" Finish")
            }
        }
    }
}

@Composable
private fun SidePanel(s: ScanHudState, setup: ScanSetup, modifier: Modifier) {
    Surface(color = SfColors.Panel.copy(alpha = 0.92f), modifier = modifier) {
        Column(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Session", style = MaterialTheme.typography.titleMedium)
            Text("Mode: ${setup.mode.name.lowercase()} · Quality: ${setup.preset.name.lowercase()}", color = SfColors.TextSecondary)
            Text("Depth frames: ${s.depthFrames}", color = SfColors.TextSecondary)
            s.distanceMeters?.let { Text("Distance to object: %.2f m".format(it), color = SfColors.TextSecondary) }
            s.lightIntensity?.let { Text("Light intensity: %.2f".format(it), color = SfColors.TextSecondary) }
            Text("Surface voxels observed: ${s.surfaceVoxels}", color = SfColors.TextSecondary)
            Text("Preview colors: cyan = observed by ≥3 frames, amber = weak evidence. Unobserved areas show no points.",
                style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
        }
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scanning help") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("• Points show where depth was actually measured: cyan = well observed, amber = weak. Gaps mean no data.")
                Text("• The percentage is the share of viewing directions you have covered around the region.")
                Text("• Tracking lost: move back to an area you scanned before and hold still.")
                Text("• Undo removes the last 3 seconds of frames (only while paused).")
                Text("• High quality uses more frames and finer detail; it does not guarantee more accurate dimensions.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
