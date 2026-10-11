package app.scanforge.android

import android.app.Application
import android.util.Log
import app.scanforge.android.settings.UserSettings
import app.scanforge.android.transfer.TransferManager
import app.scanforge.project.ProjectStatus
import app.scanforge.project.ProjectStore
import app.scanforge.workflow.ScanWorkflow
import java.io.File

/** Process-wide dependencies (simple service locator; no DI framework needed at this size). */
class ScanForgeApp : Application() {
    lateinit var store: ProjectStore
        private set
    lateinit var workflow: ScanWorkflow
        private set
    lateinit var settings: UserSettings
        private set
    val transfer: TransferManager by lazy { TransferManager(this) }

    override fun onCreate() {
        super.onCreate()
        val root = File(filesDir, "scanforge")
        store = ProjectStore(root)
        workflow = ScanWorkflow(store)
        settings = UserSettings(this)
        recoverAfterCrash()
    }

    /**
     * Projects left in RECONSTRUCTING by a killed process are re-queued by WorkManager (which persists its own
     * queue); projects left in CAPTURING keep their partial capture and are offered under "Resume Recent Scan".
     */
    private fun recoverAfterCrash() {
        runCatching { store.recoverAfterCrash() }.onFailure { Log.w(TAG, "Crash recovery failed", it) }
        store.list(includeArchived = true).filter { it.status == ProjectStatus.CAPTURING }.forEach {
            Log.i(TAG, "Project ${it.id} has an unfinished capture (${it.name})")
        }
    }

    companion object {
        const val TAG = "ScanForge"
    }
}

val android.content.Context.app: ScanForgeApp get() = applicationContext as ScanForgeApp
