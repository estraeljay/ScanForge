package app.scanforge.android.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.scanforge.android.reconstruction.ReconstructionWorker
import app.scanforge.android.ui.Routes
import app.scanforge.android.ui.components.Panel
import app.scanforge.android.ui.components.Status
import app.scanforge.android.ui.components.StatusChip
import app.scanforge.android.ui.components.screenPadding
import app.scanforge.android.ui.theme.SfColors
import app.scanforge.reconstruction.ReconstructionFailure
import app.scanforge.reconstruction.Stage

private val stageLabels = listOf(
    Stage.VALIDATING to "Validate captured data",
    Stage.INTEGRATING to "Fuse depth frames",
    Stage.EXTRACTING to "Extract surface",
    Stage.CLEANING to "Remove support plane and fragments",
    Stage.ANALYZING to "Analyze coverage and holes",
)

@Composable
fun ReconstructionScreen(nav: NavController) {
    val context = LocalContext.current
    val projectId = nav.currentBackStackEntry?.arguments?.getString("projectId") ?: return
    val flow = remember(projectId) { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ReconstructionWorker.uniqueName(projectId)) }
    val infos by flow.collectAsState(initial = emptyList())
    val info = infos.lastOrNull()
    val stage = info?.progress?.getString(ReconstructionWorker.KEY_STAGE)?.let { runCatching { Stage.valueOf(it) }.getOrNull() }
    val done = info?.progress?.getInt(ReconstructionWorker.KEY_DONE, 0) ?: 0
    val total = info?.progress?.getInt(ReconstructionWorker.KEY_TOTAL, 0) ?: 0

    LaunchedEffect(info?.state) {
        if (info?.state == WorkInfo.State.SUCCEEDED) nav.navigate(Routes.project(projectId)) { popUpTo("home") }
    }

    Column(Modifier.fillMaxSize().screenPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Reconstruction", style = MaterialTheme.typography.headlineSmall)
        when (info?.state) {
            null, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> StatusChip(Status.NEUTRAL, "Waiting to start")
            WorkInfo.State.RUNNING -> StatusChip(Status.NEUTRAL, "Processing on this device")
            WorkInfo.State.SUCCEEDED -> StatusChip(Status.GOOD, "Done")
            WorkInfo.State.FAILED -> StatusChip(Status.BAD, "Reconstruction failed")
            WorkInfo.State.CANCELLED -> StatusChip(Status.WARN, "Cancelled")
        }
        Panel(title = "Stages") {
            val currentIndex = stageLabels.indexOfFirst { it.first == stage }
            stageLabels.forEachIndexed { i, (s, label) ->
                val status = when {
                    info?.state == WorkInfo.State.SUCCEEDED || (currentIndex >= 0 && i < currentIndex) -> Status.GOOD
                    i == currentIndex -> Status.NEUTRAL
                    else -> null
                }
                Row(Modifier.fillMaxWidth()) {
                    if (status != null) StatusChip(status, label) else Text("○ $label", color = SfColors.TextSecondary)
                }
                if (i == currentIndex && total > 0) {
                    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Text(info?.progress?.getString(ReconstructionWorker.KEY_MESSAGE) ?: "", color = SfColors.TextSecondary)
                }
            }
            Text("Progress reflects real processing steps. You can leave this screen; processing continues and resumes after interruptions.",
                style = MaterialTheme.typography.bodySmall, color = SfColors.TextSecondary)
        }
        if (info?.state == WorkInfo.State.FAILED) {
            val failure = info.outputData.getString(ReconstructionWorker.KEY_FAILURE)?.let { runCatching { ReconstructionFailure.valueOf(it) }.getOrNull() }
            Panel(title = "Why it failed") {
                Text(failure?.userMessage ?: "Unexpected error during reconstruction.")
                failure?.recovery?.forEach { Text("• $it", color = SfColors.TextSecondary) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ReconstructionWorker.enqueue(context, projectId) }) { Text("Retry") }
                OutlinedButton(onClick = { nav.navigate(Routes.project(projectId)) }) { Text("Open project") }
            }
        }
        if (info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED) {
            OutlinedButton(onClick = { ReconstructionWorker.cancel(context, projectId); nav.navigate(Routes.project(projectId)) { popUpTo("home") } }) {
                Text("Cancel (progress is kept)")
            }
        }
    }
}
