package com.tgfinder.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tgfinder.MainActivity
import com.tgfinder.R
import com.tgfinder.TgFinderApp
import com.tgfinder.data.db.DownloadEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps downloads running with the screen off and shows their progress.
 * The downloads themselves live in [DownloadRepository]; this service only watches them and stops
 * itself once nothing is active.
 */
class DownloadService : LifecycleService() {

    private var watcher: Job? = null
    private var foreground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureChannel(this)
        if (!foreground) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, build(emptyList()),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            foreground = true
        }
        if (watcher == null) {
            val repo = (application as TgFinderApp).container.downloads
            watcher = lifecycleScope.launch {
                repo.all
                    .map { list -> list.filter { it.status in DownloadEntity.ACTIVE } }
                    .distinctUntilChanged()
                    .collect { active ->
                        if (active.isEmpty() && !repo.hasActiveWork()) {
                            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            foreground = false
                            stopSelf()
                        } else {
                            if (NotificationManagerCompat.from(this@DownloadService).areNotificationsEnabled()) {
                                @Suppress("MissingPermission")
                                NotificationManagerCompat.from(this@DownloadService).notify(NOTIFICATION_ID, build(active))
                            }
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits dataSync services to 6 hours a day: pause and stop cleanly when told to. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val app = application as TgFinderApp
        app.container.appScope.launch { app.container.db.downloads().active().forEach { app.container.downloads.pause(it.key) } }
        stopSelf()
    }

    private fun build(active: List<DownloadEntity>): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val total = active.sumOf { it.totalBytes.coerceAtLeast(0) }
        val done = active.sumOf { it.downloadedBytes.coerceAtLeast(0) }
        val percent = if (total > 0) ((done * 100) / total).toInt() else 0
        val title = when (active.size) {
            0 -> "Preparing download…"
            1 -> active.first().title
            else -> "Downloading ${active.size} videos"
        }
        val text = when {
            active.any { it.status == DownloadEntity.SAVING } -> "Saving to Movies/${MediaStoreSaver.FOLDER}…"
            total > 0 -> "$percent% · ${formatBytes(done)} of ${formatBytes(total)}"
            else -> "Starting…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, percent, total <= 0)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: IllegalStateException) {
                // Not allowed from the background (Android 12+); downloads still run while the app is open.
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                            description = "Progress of video downloads"
                        }
                    )
                }
            }
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = listOf("KB", "MB", "GB", "TB")
            var value = bytes / 1024.0
            var i = 0
            while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
            return if (value >= 100) "%.0f %s".format(value, units[i]) else "%.1f %s".format(value, units[i])
        }
    }
}
