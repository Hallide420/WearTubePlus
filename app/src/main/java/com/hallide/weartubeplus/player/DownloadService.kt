package com.hallide.weartubeplus.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.hallide.weartubeplus.MainActivity
import com.hallide.weartubeplus.data.Download
import com.hallide.weartubeplus.data.DownloadStatus
import com.hallide.weartubeplus.data.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while a download runs, so lowering the wrist does not
 * kill an in-flight transfer. The transfer itself lives in [Downloader]; this
 * service observes [Downloads.items] and mirrors progress into a `dataSync`
 * foreground notification, stopping once nothing is active.
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            context.startForegroundService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var nm: NotificationManager
    private var lastNotifyAt = 0L
    private var lastNotifyKey = ""

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        // Must post within a few seconds of startForegroundService().
        startForeground(NOTIF_ID, notification(null))
        scope.launch {
            Downloads.items.collectLatest { items ->
                val active = items.filter { it.isActive }
                if (active.isEmpty()) {
                    stopSelf()
                } else {
                    // Transfers report progress once a second; throttle the
                    // notification to match instead of rebuilding it per tick.
                    val key = active.joinToString("|") {
                        "${it.videoId}:${it.status}:${(it.fraction * 100).toInt()}"
                    }
                    val now = System.currentTimeMillis()
                    if (key != lastNotifyKey && now - lastNotifyAt >= 1_000) {
                        lastNotifyKey = key
                        lastNotifyAt = now
                        nm.notify(NOTIF_ID, notification(active))
                    }
                }
            }
        }
    }

    /**
     * Android 15+ caps `dataSync` foreground services (6 h per 24 h). Pause the
     * transfers so their `.part` files stay resumable instead of being killed.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Downloader.pauseAll()
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Downloads.items.value.none { it.isActive }) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(active: List<Download>?): Notification {
        val list = active.orEmpty()
        val current = list.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
            ?: list.firstOrNull()

        val title = when {
            list.isEmpty() -> "Downloads"
            list.size == 1 -> "Downloading ${current?.video?.title.orEmpty().take(32)}"
            else -> "Downloading ${list.size} videos"
        }
        val text = when {
            current == null -> "Preparing…"
            current.bytesTotal > 0 ->
                "${(current.fraction * 100).toInt()}% · ${current.video.channel}"
            else -> current.video.channel.ifEmpty { "Starting…" }
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (current != null && current.bytesTotal > 0) {
            builder.setProgress(100, (current.fraction * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
}
