package app.scanforge.android.reconstruction

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.scanforge.android.app
import app.scanforge.reconstruction.CancellationToken
import app.scanforge.reconstruction.DirectoryCaptureSource
import app.scanforge.reconstruction.ImageDecoder
import app.scanforge.reconstruction.ReconstructionException
import app.scanforge.reconstruction.ReconstructionFailure
import app.scanforge.reconstruction.RgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.scanforge.workflow.Thumbnail

/** Decodes JPEG keyframes with BitmapFactory into the platform-independent RGB format. */
object AndroidImageDecoder : ImageDecoder {
    override fun decode(bytes: ByteArray): RgbImage {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Unreadable JPEG")
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val rgb = ByteArray(px.size * 3)
        for (i in px.indices) { val p = px[i]; rgb[i * 3] = (p shr 16).toByte(); rgb[i * 3 + 1] = (p shr 8).toByte(); rgb[i * 3 + 2] = p.toByte() }
        val img = RgbImage(bmp.width, bmp.height, rgb)
        bmp.recycle()
        return img
    }
}

/**
 * Runs reconstruction in WorkManager so it survives leaving the screen. If the process is stopped, WorkManager
 * re-runs the work and the pipeline resumes from its last TSDF checkpoint. Progress is published per real stage.
 */
class ReconstructionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val projectId = inputData.getString(KEY_PROJECT) ?: return@withContext Result.failure()
        val app = applicationContext.app
        val token = CancellationToken()
        val source = DirectoryCaptureSource(app.store.captureDir(projectId), AndroidImageDecoder)
        try {
            app.workflow.reconstruct(projectId, source, cancel = token) { p ->
                if (isStopped) token.cancel()
                setProgressAsync(workDataOf(KEY_STAGE to p.stage.name, KEY_DONE to p.completed, KEY_TOTAL to p.total, KEY_MESSAGE to p.message))
            }
            writeThumbnail(applicationContext, projectId)
            Result.success()
        } catch (e: ReconstructionException) {
            if (e.failure == ReconstructionFailure.CANCELLED && isStopped) Result.retry()
            else Result.failure(Data.Builder().putString(KEY_FAILURE, e.failure.name).build())
        }
    }

    companion object {
        /** Renders previews/thumbnail.png from the current model version (used by the project library). */
        fun writeThumbnail(context: Context, projectId: String) {
            val app = context.app
            // Note: a local named `app` shadows the `app.scanforge` package, so types are imported, never fully qualified.
            val px = Thumbnail.render(app.store.loadModel(projectId), 256)
            val bmp = android.graphics.Bitmap.createBitmap(px, 256, 256, android.graphics.Bitmap.Config.ARGB_8888)
            val f = java.io.File(app.store.dir(projectId), "previews/thumbnail.png")
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }

        const val KEY_PROJECT = "project"
        const val KEY_STAGE = "stage"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_MESSAGE = "message"
        const val KEY_FAILURE = "failure"

        fun uniqueName(projectId: String) = "reconstruct-$projectId"

        fun enqueue(context: Context, projectId: String) {
            val req = OneTimeWorkRequestBuilder<ReconstructionWorker>().setInputData(workDataOf(KEY_PROJECT to projectId)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(projectId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, projectId: String) = WorkManager.getInstance(context).cancelUniqueWork(uniqueName(projectId))
    }
}
