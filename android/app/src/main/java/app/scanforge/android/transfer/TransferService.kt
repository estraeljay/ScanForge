package app.scanforge.android.transfer

import app.scanforge.android.systemService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.scanforge.android.BuildConfig
import app.scanforge.android.app

/**
 * Keeps the transfer server alive while the user switches to other apps during a transfer.
 * Started only while the server runs; shows an ongoing notification as Android requires.
 */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.transfer.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = systemService<NotificationManager>()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(BuildConfig.APP_NAME)
            .setContentText("Ready to send scans to Blender on this Wi-Fi network")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        app.transfer.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() { app.transfer.stop(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "transfer"
        private const val ACTION_STOP = "stop"
        fun start(context: Context) = context.startForegroundService(Intent(context, TransferService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, TransferService::class.java).setAction(ACTION_STOP))
    }
}
