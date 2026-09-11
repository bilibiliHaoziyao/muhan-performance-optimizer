package com.muhan.socbatteryinfo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.muhan.socbatteryinfo.MainActivity
import com.muhan.socbatteryinfo.R
import com.muhan.socbatteryinfo.util.BatteryReader
import com.muhan.socbatteryinfo.util.DisplayMode
import com.muhan.socbatteryinfo.util.MonitorItems
import com.muhan.socbatteryinfo.util.Prefs
import java.util.concurrent.Executors

/** 悬浮窗前台服务：半透明悬浮窗持续刷新显示所选内容 */
class FloatingWindowService : Service() {

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var textView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    @Volatile private var isDragging = false
    @Volatile private var updateInFlight = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!updateInFlight) {
                updateInFlight = true
                executor.execute {
                    try {
                        updateContent()
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
        if (Prefs.getDisplayMode(this) != DisplayMode.FLOATING) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startAsForeground()
        if (floatView == null) createOverlay()
        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        executor.shutdownNow()
        floatView?.let { view -> windowManager?.removeView(view) }
        floatView = null
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(FLOATING_NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun createOverlay() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.floating_window, null)
        textView = view.findViewById(R.id.tvFloatContent)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 160
        }

        setupDrag(view, params)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager?.addView(view, params)
        floatView = view
        layoutParams = params
    }

    private fun setupDrag(view: View, params: WindowManager.LayoutParams) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initialX + (event.rawX - initialTouchX)).toInt()
                    params.y = (initialY + (event.rawY - initialTouchY)).toInt()
                    windowManager?.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** 在后台线程采样并拼接文本，随后回到主线程更新悬浮窗文本 */
    private fun updateContent() {
        if (isDragging) return
        val battery = BatteryReader.read(this)
        val text = MonitorItems.selectedLines(this, battery)
            .joinToString("\n") { "${it.label} ${it.value}" }
        handler.post {
            if (!isDragging) {
                textView?.text = text
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            FLOATING_CHANNEL_ID,
            getString(R.string.floating_switch_label),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.floating_switch_label)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, FLOATING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.floating_switch_label))
            .setContentText(getString(R.string.floating_switch_label))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FLOATING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(FLOATING_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val FLOATING_CHANNEL_ID = "floating_window_channel"
        const val FLOATING_NOTIFICATION_ID = 2
        /** 刷新间隔：1 秒（避免持续高负载与耗电） */
        private const val REFRESH_INTERVAL_MS = 1000L
    }
}