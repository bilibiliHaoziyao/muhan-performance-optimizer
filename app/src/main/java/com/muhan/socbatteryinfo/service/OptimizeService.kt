package com.muhan.socbatteryinfo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.muhan.socbatteryinfo.MainActivity
import com.muhan.socbatteryinfo.R
import com.muhan.socbatteryinfo.util.MemoryCleaner
import com.muhan.socbatteryinfo.util.Prefs

/**
 * 运存优化前台服务：
 * 每 3 分钟检查一次运存占用率，达到阈值且自动清理开启时，
 * 清理后台进程（跳过白名单）。通知在每次检查时更新，功耗极低。
 */
class OptimizeService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkAndClean()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startAsForeground(buildNotification(0, null))
        handler.removeCallbacks(checkRunnable)
        handler.post(checkRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun checkAndClean() {
        val usage = MemoryCleaner.usagePercent()?.toInt()
        var cleaned = 0
        val autoClean = Prefs.isAutoCleanEnabled(this)
        if (autoClean && usage != null && usage >= Prefs.getCleanThreshold(this)) {
            cleaned = MemoryCleaner.cleanBackgroundProcesses(this, Prefs.getCleanWhitelist(this))
            if (cleaned > 0) {
                Prefs.incrementCleanStat(this)
                notifyCleaned(cleaned)
            }
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(cleaned, usage))
    }

    /** 清理完成后弹出独立通知，提示清理了多少进程 */
    private fun notifyCleaned(cleaned: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.optimize_clean_notify_title))
            .setContentText(getString(R.string.optimize_clean_notify_body, cleaned))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(NOTIFICATION_ID_CLEAN, notification)
    }

    private fun buildNotification(cleaned: Int, usage: Int?): Notification {
        val title = getString(R.string.optimize_notify_title)
        val text = getString(
            R.string.optimize_notify_usage,
            usage ?: 0,
            if (cleaned > 0) {
                getString(R.string.optimize_notify_cleaned_suffix, cleaned)
            } else {
                ""
            }
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OptimizeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_notification, getString(R.string.stop_monitor), stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.optimize_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.optimize_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.muhan.socbatteryinfo.action.STOP_OPTIMIZE"
        const val CHANNEL_ID = "optimize_channel"
        const val NOTIFICATION_ID = 3
        const val NOTIFICATION_ID_CLEAN = 4
        const val CHECK_INTERVAL_MS = 3 * 60 * 1000L
    }
}
