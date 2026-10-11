package app.scanforge.android.ui.scan

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.scanforge.android.app
import app.scanforge.android.capture.CameraFrames
import app.scanforge.android.capture.MarkerCaptureEngine
import app.scanforge.android.capture.MarkerGuidance
import app.scanforge.android.capture.MarkerHudState
import app.scanforge.android.capture.Plane
import app.scanforge.android.markers.OpenCvMarkerDetector
import app.scanforge.android.reconstruction.ReconstructionWorker
import app.scanforge.android.systemService
import app.scanforge.android.ui.Routes
import app.scanforge.android.ui.components.Status
import app.scanforge.android.ui.components.StatusChip
import app.scanforge.android.ui.components.color
import app.scanforge.android.ui.components.humanBytes
import app.scanforge.android.ui.theme.SfColors
import app.scanforge.markers.BoardLayout
import app.scanforge.markers.BoardSpec
import app.scanforge.reconstruction.CameraIntrinsics
import app.scanforge.reconstruction.QualityPreset
import java.util.concurrent.Executors

class MarkerScanViewModel(application: Application, handle: SavedStateHandle) : AndroidViewModel(application) {
    private val spec = BoardSpec.fromCode(handle.get<String>("board") ?: BoardSpec.A2.code)
    val engine = MarkerCaptureEngine(
        application.app,
        projectId = handle.get<String>("projectId")!!,
        preset = QualityPreset.valueOf(handle.get<String>("preset") ?: "STANDARD"),
        board = BoardLayout(spec, application.app.settings.boardScale(spec.code)),
    )
    override fun onCleared() { engine.close() }
}

/** Analyzer state confined to the camera analysis thread. */
private class FrameAnalyzer(private val engine: MarkerCaptureEngine, private val cameraManager: CameraManager) {
    private var detector: OpenCvMarkerDetector? = null
    private var intrinsics: CameraIntrinsics? = null
    private var gray = ByteArray(0)
    var error: String? = null
        private set

    fun analyze(image: ImageProxy) {
        try {
            val w = image.width; val h = image.height
            val det = detector ?: OpenCvMarkerDetector().also { detector = it }
            val k = intrinsics?.takeIf { it.width == w && it.height == h } ?: CameraFrames.intrinsicsGuess(cameraManager, w, h).also { intrinsics = it }
            if (gray.size != w * h) gray = ByteArray(w * h)
            val p = image.planes
            val y = Plane(p[0].buffer, p[0].rowStride, p[0].pixelStride)
            CameraFrames.luma(y, w, h, gray)
            val markers = det.detect(gray, w, h, engine.board)
            engine.onFrame(gray, w, h, image.imageInfo.timestamp, k, markers) {
                val u = Plane(p[1].buffer, p[1].rowStride, p[1].pixelStride); val v = Plane(p[2].buffer, p[2].rowStride, p[2].pixelStride)
                CameraFrames.jpeg(CameraFrames.toNv21(y, u, v, w, h), w, h)
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            image.close()
        }
    }

    fun close() { detector?.close() }
}

private fun guidanceText(s: MarkerHudState): Pair<Status, String> = when (s.guidance) {
    MarkerGuidance.BOARD_NOT_VISIBLE -> Status.BAD to "Point the camera at the marker board"
    MarkerGuidance.MORE_OF_BOARD -> Status.WARN to "Show more of the board (step back or tilt the phone)"
    MarkerGuidance.HOLD_STILL -> Status.WARN to "Move more slowly"
    MarkerGuidance.READY -> Status.NEUTRAL to "Board found. Press Start, then walk slowly around the object"
    MarkerGuidance.CONTINUE_AROUND -> Status.WARN to "Keep going around the object" + (s.nearestGapRadians?.let { if (it > 0) "  ←" else "  →" } ?: "")
    MarkerGuidance.RAISE_ANGLE -> Status.WARN to "Go around again from higher up"
    MarkerGuidance.CAPTURE_TOP -> Status.WARN to "Take a few photos from above"
    MarkerGuidance.COMPLETE -> Status.GOOD to "Coverage is good. Press Finish"
}

@Composable
fun MarkerScanScreen(nav: NavController, vm: MarkerScanViewModel = viewModel()) {
    val context = LocalContext.current
    val app = context.app
    val prefs by app.settings.flow.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val state by vm.engine.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    var cameraError by remember { mutableStateOf<String?>(null) }

    if (!hasCamera) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Camera access is needed to scan.", style = MaterialTheme.typography.titleMedium)
            Text("Photos are processed and stored only on this device unless you send them.", color = SfColors.TextSecondary)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
        }
        return
    }

    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; keepScreenOn = true } }
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val analyzer = FrameAnalyzer(vm.engine, context.systemService())
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor) { img -> analyzer.analyze(img); analyzer.error?.let { cameraError = it } }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                cameraError = "Camera could not start: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.execute { analyzer.close() }
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Leave scanner", tint = SfColors.Text) }
                StatusChip(
                    when { state.markersVisible == 0 -> Status.BAD; state.poseOk -> Status.GOOD; else -> Status.WARN },
                    "Board: ${state.markersVisible}/${state.boardMarkers} markers" + (state.reprojectionPx?.let { " · %.1f px".format(it) } ?: ""),
                )
            }
            if (state.scanning || state.photos > 0) Surface(color = SfColors.Panel.copy(alpha = 0.85f), shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    MStat("Time", "%d:%02d".format(state.durationMs / 60000, (state.durationMs / 1000) % 60))
                    MStat("Photos", "${state.photos}/${vm.engine.maxPhotos}")
                    MStat("Stored", humanBytes(state.bytesWritten))
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { state.viewFraction.toFloat() }, color = (if (state.viewFraction >= 0.75) Status.GOOD else Status.WARN).color(),
                            trackColor = SfColors.Border, modifier = Modifier.size(36.dp))
                        Text("%.0f".format(state.viewFraction * 100), style = MaterialTheme.typography.labelSmall)
                    }
                    MStat("Coverage", "of directions")
                }
            }
            val (st, text) = guidanceText(state)
            Surface(color = SfColors.Panel.copy(alpha = 0.9f), shape = RoundedCornerShape(10.dp)) {
                Text(text, Modifier.padding(12.dp), color = st.color(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            state.message?.let { Text(it, color = SfColors.Warn) }
            (cameraError)?.let { Text(it, color = SfColors.Bad) }
            if (!state.scanning && state.photos == 0) Surface(color = SfColors.Panel.copy(alpha = 0.9f), shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Before you start", color = SfColors.TextSecondary, style = MaterialTheme.typography.titleSmall)
                    Text("• Board flat, object in the middle, even light, no glare on the paper.", style = MaterialTheme.typography.bodySmall)
                    Text("• Keep most of the board in view; walk around twice (low and high), then from above.", style = MaterialTheme.typography.bodySmall)
                    Text("• Photos are taken automatically when the view has changed enough and the image is sharp.", style = MaterialTheme.typography.bodySmall)
                    if (!app.settings.boardScaleChecked(vm.engine.board.spec.code)) Text("• Print scale not checked: sizes assume the board printed at exactly 100 %.", color = SfColors.Warn, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().background(SfColors.Charcoal.copy(alpha = 0.55f)).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                if (prefs.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (state.scanning) vm.engine.pause() else vm.engine.start(mapOf("model" to "${Build.MANUFACTURER} ${Build.MODEL}", "android" to Build.VERSION.RELEASE, "camera" to "camerax"))
            }, enabled = !state.finished && (state.scanning || state.markersVisible > 0 || state.photos > 0), modifier = Modifier.height(64.dp)) {
                Icon(if (state.scanning) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
                Text(if (state.scanning) " Pause" else if (state.photos > 0) " Resume" else " Start", style = MaterialTheme.typography.titleMedium)
            }
            Button(onClick = {
                if (prefs.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                if (vm.engine.finish()) {
                    ReconstructionWorker.enqueue(context, vm.engine.projectId)
                    nav.navigate(Routes.reconstruct(vm.engine.projectId)) { popUpTo("home") }
                }
            }, enabled = state.photos >= 8 && !state.finished, modifier = Modifier.height(64.dp)) {
                Icon(Icons.Filled.Check, null); Text(" Finish")
            }
        }
    }
}

@Composable
private fun MStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SfColors.TextSecondary)
        Text(value, style = MaterialTheme.typography.titleSmall, color = SfColors.Text)
    }
}
