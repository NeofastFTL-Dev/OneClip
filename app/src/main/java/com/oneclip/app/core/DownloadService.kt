package com.oneclip.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oneclip.app.MainActivity
import com.oneclip.app.R
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.oneclip.app.action.START_DOWNLOADS"
        const val ACTION_CANCEL = "com.oneclip.app.action.CANCEL_DOWNLOAD"
        const val EXTRA_ID = "id"

        private const val TAG = "OneClip/Download"
        private const val CHANNEL_PROGRESS = "downloads"
        private const val CHANNEL_DONE = "download_done"
        private const val NOTIF_PROGRESS = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var draining = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_ID)?.let { Downloads.cancel(it) }
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIF_PROGRESS, progressNotification("Preparing download…", "Starting the engine", 0, true))
        } catch (t: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!draining) {
            draining = true
            scope.launch {
                try {
                    drain()
                } finally {
                    draining = false
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun drain() {
        while (true) {
            val item = Downloads.takeNextQueued() ?: break
            val dir = File(getExternalFilesDir(null) ?: filesDir, "work/${item.id}")
            dir.deleteRecursively()
            dir.mkdirs()

            var lastUpdate = 0L
            try {
                val files = Downloader.download(item.url, item.spec, dir, item.id) { progress, line ->
                    Downloads.progress(item.id, progress, line.take(160))
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 700L) {
                        lastUpdate = now
                        notifyProgress(item.title, line.take(120), progress)
                    }
                }

                val produced = files.firstOrNull()
                    ?: throw IllegalStateException("The extractor finished without producing a file.")
                val uri = Exporter.export(this, produced)
                Downloads.complete(item.id, uri?.toString(), produced.name)
                notifyDone(item.id, item.title, produced.name, uri)
            } catch (t: YoutubeDL.CanceledException) {
                Downloads.markCanceled(item.id)
            } catch (t: Throwable) {
                Log.e(TAG, "download failed: ${item.url} (${item.spec.label})", t)
                Downloads.fail(item.id, Downloader.humanError(t))
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                enableVibration(false)
            }
        )
    }

    private fun progressNotification(title: String, text: String, percent: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .build()
    }

    private fun notifyProgress(title: String, text: String, percent: Float) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIF_PROGRESS, progressNotification(title, text, percent.toInt().coerceIn(0, 100), false))
    }

    private fun notifyDone(itemId: String, title: String, fileName: String, uri: Uri?) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val intent = if (uri != null) {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, Exporter.mimeOf(fileName.substringAfterLast('.', "")))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(this, MainActivity::class.java)
        }
        val pending = PendingIntent.getActivity(
            this,
            itemId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle("Saved: $title")
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            manager.notify(itemId.hashCode() and 0xffff, notification)
        } catch (t: Throwable) {
            // Notification is a nicety, not a requirement.
        }
    }
}
