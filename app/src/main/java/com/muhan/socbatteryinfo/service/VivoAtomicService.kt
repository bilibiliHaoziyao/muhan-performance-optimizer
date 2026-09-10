package com.muhan.socbatteryinfo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.muhan.socbatteryinfo.MainActivity
import com.muhan.socbatteryinfo.R
import com.muhan.socbatteryinfo.util.BatteryData
import com.muhan.socbatteryinfo.util.BatteryReader
import com.muhan.socbatteryinfo.util.DisplayMode
import com.muhan.socbatteryinfo.util.MonitorItems
import com.muhan.socbatteryinfo.util.MonitorLine
import com.muhan.socbatteryinfo.util.Prefs

/**
 * 前台服务：vivo 原子岛 / 原子通知（本地发送方式）。
 *
 * 按 vivo《原子通知技术规范》本地方法实现：
 *  - 发送原生通知，并在 extras 中携带 notification.superx.* 系列字段
 *  - operation：0 创建 / 1 更新 / 2 结束
 *  - template：1 强调信息模板（describe + coreInfo + image）
 *  - 同时提供 baseInfos（大卡）、infos（强调信息）、shortInfos（小卡/锁屏）、
 *    capsule（状态栏胶囊）、island（OS5.0 原子岛左/右岛）
 *  - showNotify=true：展示失败时降级为普通通知
 *
 * 内容与悬浮窗 / 小米超级岛共用用户勾选的显示项（MonitorItems）。
 */
class VivoAtomicService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var posted = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            update()
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
        if (Prefs.getDisplayMode(this) != DisplayMode.VIVO_ATOMIC) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        posted = false
        startAsForeground(buildNotification(BatteryReader.read(this), OPERATION_CREATE))
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun update() {
        // 首次创建（operation=0），之后更新（operation=1）
        val operation = if (posted) OPERATION_UPDATE else OPERATION_CREATE
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(BatteryReader.read(this), operation))
        posted = true
    }

    private fun buildNotification(data: BatteryData, operation: Int): Notification {
        val lines = MonitorItems.selectedLines(this, data).ifEmpty { defaultLines(data) }
        val first = lines.first()
        val summary = lines.joinToString("\n") { "${it.label} ${it.value}" }
        val firstValue = first.value

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VivoAtomicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val appIcon = Icon.createWithResource(this, R.drawable.ic_notification)

        // notification.superx.* 系列字段
        val superx = Bundle().apply {
            putInt("notification.superx.operation", operation)
            putBoolean("notification.superx.showNotify", true)
            putInt("notification.superx.template", TEMPLATE_EMPHASIS)
            putBoolean("notification.superx.sound", false)
            putString("notification.superx.scene", SCENE_BATTERY)
            putParcelable("notification.superx.clickResp", contentIntent)
        }

        // 大卡基础信息区
        superx.putBundle(
            "notification.superx.baseInfos",
            Bundle().apply {
                putParcelable("notification.superx.baseInfos.icon", appIcon)
                putCharSequence("notification.superx.baseInfos.title", first.label)
                putCharSequence("notification.superx.baseInfos.content", summary)
            }
        )

        // 强调信息区（template=1）
        superx.putBundle(
            "notification.superx.infos",
            Bundle().apply {
                putString("notification.superx.infos.describe", first.label)
                putString("notification.superx.infos.coreInfo", firstValue)
                putParcelable("notification.superx.infos.image", appIcon)
                putParcelable("notification.superx.infos.imageClickResp", contentIntent)
            }
        )

        // 小卡 & Origin 锁屏
        superx.putBundle(
            "notification.superx.shortInfos",
            Bundle().apply {
                putParcelable("notification.superx.shortInfos.icon", appIcon)
                putParcelable("notification.superx.shortInfos.image", appIcon)
                putParcelable("notification.superx.shortInfos.imageClickResp", contentIntent)
                putString("notification.superx.shortInfos.describeShort", first.label)
                putString("notification.superx.shortInfos.coreInfoShort", firstValue)
            }
        )

        // 状态栏胶囊
        superx.putBundle(
            "notification.superx.capsule",
            Bundle().apply {
                putInt("notification.superx.capsule.state", 1)
                putParcelable("notification.superx.capsule.icon", appIcon)
                putCharSequence("notification.superx.capsule.content", firstValue)
            }
        )

        // OS5.0 原子岛：左岛图片+文本，右岛胶囊文本
        superx.putBundle(
            "notification.superx.island",
            Bundle().apply {
                putInt("island.superx.leftTemplate", 1)
                putBundle(
                    "island.superx.leftInfo",
                    Bundle().apply {
                        putParcelable("island.superx.leftInfo.icon", appIcon)
                        putCharSequence("island.superx.leftInfo.content", "${first.label} ${firstValue}")
                    }
                )
                putInt("island.superx.rightTemplate", 6)
                putBundle(
                    "island.superx.rightInfo",
                    Bundle().apply {
                        putCharSequence("island.superx.rightInfo.capsuleContent", firstValue)
                    }
                )
                putInt("island.superx.islandClick", 0)
                putInt("island.superx.template", TEMPLATE_EMPHASIS)
            }
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(first.label)
            .setContentText(summary)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_notification, getString(R.string.stop_monitor), stopIntent)

        val notification = builder.build()
        // 合并 vivo 原子通知扩展字段（不覆盖 NotificationCompat 自带 extras）
        notification.extras.putAll(superx)
        return notification
    }

    /** 未勾选任何显示项时的兜底内容 */
    private fun defaultLines(data: BatteryData): List<MonitorLine> {
        val status = if (data.isCharging) getString(R.string.live_update_charging) else getString(R.string.app_name)
        val lines = mutableListOf<MonitorLine>()
        data.powerWatts(Prefs.isDualCell(this))?.let { lines += MonitorLine("功率", String.format("%.1fW", it)) }
        data.capacityPercent?.let { lines += MonitorLine("电量", "$it%") }
        data.temperatureC?.let { lines += MonitorLine("电池", String.format("%.1f°C", it)) }
        if (lines.isEmpty()) lines += MonitorLine(status, "--")
        return lines
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vivo_atomic_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.vivo_atomic_channel_desc)
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
        const val ACTION_STOP = "com.muhan.socbatteryinfo.action.STOP_VIVO_ATOMIC"
        const val CHANNEL_ID = "vivo_atomic_channel"
        const val NOTIFICATION_ID = 1
        const val REFRESH_INTERVAL_MS = 1000L

        private const val OPERATION_CREATE = 0
        private const val OPERATION_UPDATE = 1
        private const val TEMPLATE_EMPHASIS = 1 // 强调信息模板
        private const val SCENE_BATTERY = "BATTERY"
    }
}
