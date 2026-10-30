package dev.horizon.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.horizon.android.bridge.HorizonNativeBridge
import dev.horizon.android.ui.ZalithMainActivity

/**
 * Foreground Service for continuous background downloading of Minecraft 26.3 assets
 * with live Android Notification bar updates (percentage, stage, speed, and active URL).
 */
class HorizonDownloadService : Service() {
    companion object {
        private const val CHANNEL_ID = "horizonz_asset_download_channel"
        private const val NOTIFICATION_ID = 2603
        private const val DONE_NOTIFICATION_ID = 2604

        fun start(context: Context) {
            val intent = Intent(context, HorizonDownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (ignored: Exception) {
            }
        }
    }

    @Volatile
    private var runningLoop = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannelIfNeeded()
        val initialNotification = buildProgressNotification(
            title = "Downloading Minecraft 26.3 (0%)",
            subtitle = "Initializing Mojang asset downloader...",
            percent = 0,
            indeterminate = true,
            ongoing = true
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (ignored: Exception) {
        }

        acquireWakeLock()

        if (!runningLoop) {
            runningLoop = true
            Thread({
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                while (runningLoop) {
                    try {
                        val status = HorizonNativeBridge.pollInstallProgress(applicationContext, true)
                        if (status.running) {
                            val speedPart = if (status.speedText.isNotBlank()) " • ${status.speedText}" else ""
                            val title = "Minecraft 26.3 Download — ${status.percent}%$speedPart"
                            val detailLine = if (status.currentUrl.isNotBlank()) {
                                "${status.stage}\n${status.currentUrl}"
                            } else {
                                status.stage
                            }
                            val notif = buildProgressNotification(
                                title = title,
                                subtitle = detailLine,
                                percent = status.percent,
                                indeterminate = false,
                                ongoing = true
                            )
                            nm?.notify(NOTIFICATION_ID, notif)
                        } else if (status.ready) {
                            val doneNotif = buildProgressNotification(
                                title = "Minecraft 26.3 Ready to Play! (100%)",
                                subtitle = "All official game assets, worldgen tables, and touch controls installed.",
                                percent = 100,
                                indeterminate = false,
                                ongoing = false
                            )
                            nm?.notify(DONE_NOTIFICATION_ID, doneNotif)
                            break
                        } else if (status.error.isNotBlank()) {
                            val errNotif = buildProgressNotification(
                                title = "Download Paused / Error",
                                subtitle = status.error,
                                percent = status.percent,
                                indeterminate = false,
                                ongoing = false
                            )
                            nm?.notify(DONE_NOTIFICATION_ID, errNotif)
                            break
                        }
                        Thread.sleep(750L)
                    } catch (e: InterruptedException) {
                        break
                    } catch (ignored: Throwable) {
                    }
                }
                runningLoop = false
                releaseWakeLock()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                } catch (ignored: Exception) {
                }
                stopSelf()
            }, "HorizonZ-DownloadNotificationThread").start()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        runningLoop = false
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HorizonZ::AssetDownloadLock")?.apply {
                    setReferenceCounted(false)
                    acquire(30 * 60 * 1000L)
                }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (ignored: Exception) {
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Minecraft Asset Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live Minecraft 26.3 background download progress and speed"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(
        title: String,
        subtitle: String,
        percent: Int,
        indeterminate: Boolean,
        ongoing: Boolean
    ): Notification {
        val openIntent = Intent(this, ZalithMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(Notification.BigTextStyle().bigText(subtitle))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)

        if (ongoing) {
            builder.setProgress(100, percent.coerceIn(0, 100), indeterminate)
        } else {
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
            builder.setAutoCancel(true)
        }

        return builder.build()
    }
}
