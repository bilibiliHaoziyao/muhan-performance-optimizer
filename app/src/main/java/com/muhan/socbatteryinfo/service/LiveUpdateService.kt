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
import com.muhan.socbatteryinfo.util.BatteryData
import com.muhan.socbatteryinfo.util.BatteryReader
import com.muhan.socbatteryinfo.util.DisplayMode
import com.muhan.socbatteryinfo.util.Prefs
import com.muhan.socbatteryinfo.util.ThermalReader

/**
 * 前台服务：Android 实时通知（Live Update / 提升的持续通知）。
 *
 * 按官方文档要求实现：
 *  - 标准样式（BigTextStyle）+ ongoing + contentTitle
 *  - 通知渠道 importance 不能为 MIN
 *  - 通过 EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing" 请求提升
 *  - Manifest 声明 android.permission.POST_PROMOTED_NOTIFICATIONS
 *
 * Android 16+ 且 ROM 支持时自动提升为实时动态；其余设备降级为普通常驻通知。
 */
class LiveUpdateService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var updateInFlight = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!updateInFlight) {
                updateInFlight = true
                worker.execute {
                    try {
                        update()
                    } finally {
                        updateInFlight = false
                    }
                }
            }
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setDisplayMode(this, DisplayMode.NONE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (Prefs.getDisplayMode(this) != DisplayMode.LIVE_UPDATE) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startAsForeground(buildNotification(BatteryReader.read(this)))
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        worker.shutdownNow()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun update() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(BatteryReader.read(this)))
    }

    private fun buildNotification(data: BatteryData): Notification {
        val watts = data.powerWatts(Prefs.isDualCell(this))
        val power = watts?.let { String.format("%.1fW", it) }
        val level = data.capacityPercent?.let { "$it%" } ?: "--"
        val batteryTemp = data.temperatureC?.let { String.format("%.1f°C", it) } ?: "--"
        val socTemp = (ThermalReader.readSocTemperature() ?: data.temperatureC?.plus(3f))
            ?.let { String.format("%.1f°C", it) } ?: "--"

        val title = if (data.isCharging) {
            getString(R.string.live_update_charging)
        } else {
            getString(R.string.app_name)
        }
        val parts = mutableListOf<String>()
        power?.let { parts.add("功率 $it") }
        parts.add("电量 $level")
        parts.add("电池 $batteryTemp")
        parts.add("SoC $socTemp")
        val content = parts.joinToString(" · ")

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveUpdateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_notification, getString(R.string.stop_monitor), stopIntent)
            .build()

        // 请求提升为实时动态：EXTRA_REQUEST_PROMOTED_ONGOING 对应官方 Notification
        // 常量（API 36.1 引入）。仅在 Android 16+（SDK 36+）写入该 extra，
        // 旧系统忽略，避免无意义的兼容层模拟。
        if (Build.VERSION.SDK_INT >= 36) {
            notification.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        }
        return notification
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.live_update_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.live_update_channel_desc)
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
        const val ACTION_STOP = "com.muhan.socbatteryinfo.action.STOP_LIVE_UPDATE"
        const val CHANNEL_ID = "live_update_channel"
        const val NOTIFICATION_ID = 1
        const val REFRESH_INTERVAL_MS = 1000L

        // Notification.EXTRA_REQUEST_PROMOTED_ONGOING（compileSdk 35 尚无该常量，手动定义）
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}
