package app.scanforge.android.ar

import app.scanforge.android.systemService
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.google.ar.core.Session

/** Tracks display rotation/size changes and forwards them to ARCore. */
class DisplayRotationHelper(private val context: Context) : DisplayManager.DisplayListener {
    @Volatile private var changed = true
    private var width = 0
    private var height = 0
    private val displayManager = context.systemService<DisplayManager>()

    @Suppress("DEPRECATION")
    private val display: Display
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display!!
        else context.systemService<WindowManager>().defaultDisplay

    fun onResume() = displayManager.registerDisplayListener(this, null)
    fun onPause() = displayManager.unregisterDisplayListener(this)

    fun onSurfaceChanged(w: Int, h: Int) { width = w; height = h; changed = true }

    fun updateSessionIfNeeded(session: Session) {
        if (changed) {
            session.setDisplayGeometry(display.rotation, width, height)
            changed = false
        }
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) { changed = true }
}
