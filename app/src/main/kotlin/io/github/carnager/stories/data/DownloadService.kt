package io.github.carnager.stories.data

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import io.github.carnager.stories.App
import io.github.carnager.stories.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the app awake while books download, with a notification saying so.
 * The work itself is [Remote]'s; this only lasts as long as it does.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stories:download")
            .apply { acquire(MAX_MS) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always first: Android stops an app that starts this and doesn't call it.
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!watching) {
            watching = true
            scope.launch {
                val remote = App.instance.remote
                while (remote.active.value) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
                    delay(1_000)
                }
                finish()
            }
        }
        return START_NOT_STICKY
    }

    // Android's daily limit for this kind of service. Downloads go on when next allowed.
    override fun onTimeout(startId: Int, fgsType: Int) = finish()

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val going = App.instance.remote.cache.value.values.filter { it.kept && !it.complete }
        val total = going.sumOf { it.total }
        val done = going.sumOf { it.done }
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (going.size == 1) "Downloading a book" else "Downloading ${going.size} books")
            .setContentText("$percent%")
            .setProgress(100, percent, total == 0L)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION = 2
        private const val MAX_MS = 6 * 3600_000L

        /** Not allowed while the app is in the background; the download then goes on as far as it's let. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, DownloadService::class.java))
            } catch (_: ForegroundServiceStartNotAllowedException) {
            }
        }
    }
}
