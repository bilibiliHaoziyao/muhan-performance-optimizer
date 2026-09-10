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
import org.json.JSONObject

/**
 * 前台服务：小米超级岛（HyperOS 岛通知 / 焦点通知）。
 *
 * 按小米官方文档实现：
 *  - 以原生通知方式发送，在 notification.extras.putString("miui.focus.param", json) 附加岛参数
 *  - islandParams 为 JSON，包含 param_v2：大岛内容、小岛内容、焦点通知、AOD、状态栏数据
 *  - 支持超级岛的小米设备以“岛”形式展示；不支持的设备降级为普通常驻通知
 */
class IslandService : Service() {

    private val handler = Handler(Looper.getMainLooper())

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
        if (Prefs.getDisplayMode(this) != DisplayMode.ISLAND) {
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun update() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(BatteryReader.read(this)))
    }

    private fun buildNotification(data: BatteryData): Notification {
        val lines = MonitorItems.selectedLines(this, data).ifEmpty { defaultLines(data) }
        val first = lines.first()
        val title = first.label
        val contentText = lines.joinToString("\n") { "${it.label} ${it.value}" }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, IslandService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_notification, getString(R.string.stop_monitor), stopIntent)

        // 岛图片资源：miui.focus.pics
        val pics = Bundle()
        pics.putParcelable(
            PIC_APP,
            Icon.createWithResource(this, R.drawable.ic_notification)
        )
        val islandBundle = Bundle()
        islandBundle.putBundle("miui.focus.pics", pics)
        builder.addExtras(islandBundle)

        val notification = builder.build()
        // 附加岛通知参数
        notification.extras.putString("miui.focus.param", buildIslandParams(lines, first))
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

    /** 把 "100% 2.0GHz" 拆成 (主值, 副值) */
    private fun splitValue(value: String): Pair<String, String> {
        val idx = value.indexOf(' ')
        return if (idx > 0) value.substring(0, idx) to value.substring(idx + 1).trim()
        else value to ""
    }

    /** 构建 miui.focus.param 的 JSON（详见小米超级岛开发指南） */
    private fun buildIslandParams(lines: List<MonitorLine>, first: MonitorLine): String {
        val (mainValue, subValue) = splitValue(first.value)
        val bigContent = lines.joinToString(" · ") { "${it.label} ${it.value}" }
        val tickerText = "${first.label} ${first.value}"

        val paramV2 = JSONObject()
            .put("protocol", 1)
            .put("business", "battery")
            .put("islandFirstFloat", false)
            .put("enableFloat", false)
            .put("updatable", true)
            // 状态栏焦点信息（OS2/OS3）
            .put("ticker", tickerText)
            // 息屏显示数据
            .put("aodTitle", tickerText)

        // 大岛 / 小岛内容
        val textInfo = JSONObject()
            .put("frontTitle", first.label)
            .put("title", mainValue)
            .put("content", subValue)
            .put("useHighLight", false)
        val picInfo = JSONObject()
            .put("type", 1)
            .put("pic", PIC_APP)
        val imageTextInfoLeft = JSONObject()
            .put("type", 1)
            .put("picInfo", picInfo)
            .put("textInfo", textInfo)
        val bigIslandArea = JSONObject().put("imageTextInfoLeft", imageTextInfoLeft)
        val smallIslandArea = JSONObject().put("picInfo", picInfo)

        val paramIsland = JSONObject()
            .put("islandProperty", 1)
            .put("bigIslandArea", bigIslandArea)
            .put("smallIslandArea", smallIslandArea)

        // 焦点通知数据
        val baseInfo = JSONObject()
            .put("title", first.label)
            .put("content", bigContent)
            .put("type", 2)

        return JSONObject()
            .put("param_v2", paramV2
                .put("param_island", paramIsland)
                .put("baseInfo", baseInfo))
            .toString()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.island_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.island_channel_desc)
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
        const val ACTION_STOP = "com.muhan.socbatteryinfo.action.STOP_ISLAND"
        const val CHANNEL_ID = "island_channel"
        const val NOTIFICATION_ID = 1
        const val REFRESH_INTERVAL_MS = 1000L

        // miui.focus.pics 中自定义图片的 key
        private const val PIC_APP = "miui.focus.pic_app"
    }
}
