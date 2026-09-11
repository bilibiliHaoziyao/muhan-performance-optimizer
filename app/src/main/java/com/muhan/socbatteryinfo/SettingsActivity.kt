package com.muhan.socbatteryinfo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.muhan.socbatteryinfo.service.FloatingWindowService
import com.muhan.socbatteryinfo.service.IslandService
import com.muhan.socbatteryinfo.service.LiveUpdateService
import com.muhan.socbatteryinfo.service.VivoAtomicService
import com.muhan.socbatteryinfo.util.CpuReader
import com.muhan.socbatteryinfo.util.DisplayMode
import com.muhan.socbatteryinfo.util.FloatingItems
import com.muhan.socbatteryinfo.util.Prefs
import com.muhan.socbatteryinfo.util.RootShell
import com.muhan.socbatteryinfo.util.ShizukuShell
import com.muhan.socbatteryinfo.util.ThemeMode
import rikka.shizuku.Shizuku

class SettingsActivity : AppCompatActivity() {

    private lateinit var rgDisplayMode: RadioGroup
    private lateinit var tvRootStatus: TextView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var switchMonet: SwitchCompat

    private val floatCheckboxes = mutableMapOf<String, CheckBox>()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateShizukuStatus() }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { updateShizukuStatus() }
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == REQ_SHIZUKU) {
            runOnUiThread { updateShizukuStatus() }
        }
    }

    private var coreCheckbox: CheckBox? = null

    // 莫奈取色开关：防抖重建，避免快速连续切换导致设置界面频繁卡顿
    private val monetRecreateHandler = Handler(Looper.getMainLooper())
    private val monetRecreateRunnable = Runnable { recreate() }

    private val floatLabels = mapOf(
        FloatingItems.CPU to R.string.float_item_cpu,
        FloatingItems.CORE to R.string.float_item_core,
        FloatingItems.GPU to R.string.float_item_gpu,
        FloatingItems.SOC_TEMP to R.string.float_item_soc_temp,
        FloatingItems.BATTERY_TEMP to R.string.float_item_battery_temp,
        FloatingItems.POWER to R.string.float_item_power,
        FloatingItems.LEVEL to R.string.float_item_level,
        FloatingItems.REFRESH to R.string.float_item_refresh
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        rgDisplayMode = findViewById(R.id.rgDisplayMode)
        tvRootStatus = findViewById(R.id.tvRootStatus)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)

        setupDisplayMode()

        buildFloatItems()

        setupThemePicker()

        setupMonetSwitch()

        findViewById<Button>(R.id.btnReRoot).setOnClickListener { reRequestRoot() }
        findViewById<Button>(R.id.btnShizuku).setOnClickListener { requestShizuku() }
        findViewById<Button>(R.id.btnBilibili).setOnClickListener { openBilibili() }
        findViewById<Button>(R.id.btnOpenSource).setOnClickListener { openSource() }
        updateRootStatus()
        updateShizukuStatus()

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    override fun onDestroy() {
        monetRecreateHandler.removeCallbacks(monetRecreateRunnable)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateRootStatus()
        // 从悬浮窗授权页返回后，若当前方式仍为悬浮窗则真正启动（已在运行则不重复启动，避免位置重置与通知重复）
        if (Prefs.getDisplayMode(this) == DisplayMode.FLOATING) {
            if (canOverlay()) {
                if (!isServiceRunning(FloatingWindowService::class.java)) {
                    startFloating()
                }
            } else {
                applyModeSelection(DisplayMode.NONE)
                Toast.makeText(this, R.string.overlay_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
        refreshFloatingItemsEnabled()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 授权后启动当前选中的通知类显示方式
                when (Prefs.getDisplayMode(this)) {
                    DisplayMode.LIVE_UPDATE -> startLiveUpdate()
                    DisplayMode.ISLAND -> startIsland()
                    DisplayMode.VIVO_ATOMIC -> startVivoAtomic()
                    else -> Unit
                }
            } else {
                applyModeSelection(DisplayMode.NONE)
                Toast.makeText(this, R.string.notify_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 显示方式（悬浮窗 / 实时通知 / 小米超级岛 三选一） ----------

    private fun setupDisplayMode() {
        // 先设置选中项，避免初始化触发切换逻辑
        rgDisplayMode.setOnCheckedChangeListener(null)
        rgDisplayMode.check(radioIdFor(Prefs.getDisplayMode(this)))
        rgDisplayMode.setOnCheckedChangeListener { _, checkedId ->
            applyModeSelection(modeForId(checkedId))
        }
    }

    private fun radioIdFor(mode: Int): Int = when (mode) {
        DisplayMode.FLOATING -> R.id.rbModeFloating
        DisplayMode.LIVE_UPDATE -> R.id.rbModeLiveUpdate
        DisplayMode.ISLAND -> R.id.rbModeIsland
        DisplayMode.VIVO_ATOMIC -> R.id.rbModeVivo
        else -> R.id.rbModeOff
    }

    private fun modeForId(id: Int): Int = when (id) {
        R.id.rbModeFloating -> DisplayMode.FLOATING
        R.id.rbModeLiveUpdate -> DisplayMode.LIVE_UPDATE
        R.id.rbModeIsland -> DisplayMode.ISLAND
        R.id.rbModeVivo -> DisplayMode.VIVO_ATOMIC
        else -> DisplayMode.NONE
    }

    /** 应用显示方式：停掉其它服务，再启动当前方式对应的服务 */
    private fun applyModeSelection(mode: Int) {
        stopService(Intent(this, FloatingWindowService::class.java))
        stopService(Intent(this, LiveUpdateService::class.java))
        stopService(Intent(this, IslandService::class.java))
        stopService(Intent(this, VivoAtomicService::class.java))

        Prefs.setDisplayMode(this, mode)
        rgDisplayMode.check(radioIdFor(mode))

        when (mode) {
            DisplayMode.FLOATING -> {
                if (canOverlay()) startFloating() else requestOverlayPermission()
            }
            DisplayMode.LIVE_UPDATE, DisplayMode.ISLAND, DisplayMode.VIVO_ATOMIC -> {
                if (hasNotificationPermission()) {
                    when (mode) {
                        DisplayMode.LIVE_UPDATE -> startLiveUpdate()
                        DisplayMode.ISLAND -> startIsland()
                        else -> startVivoAtomic()
                    }
                } else {
                    requestNotificationPermission()
                }
            }
            else -> Unit
        }
        refreshFloatingItemsEnabled()
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQ_NOTIFY
        )
    }

    private fun canOverlay(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    /** 服务是否正在运行（getRunningServices 会返回本应用自己的前台服务） */
    private fun isServiceRunning(service: Class<*>): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getRunningServices(100).any { it.service.className == service.name }
        } catch (_: Exception) {
            false
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun startFloating() {
        ContextCompat.startForegroundService(this, Intent(this, FloatingWindowService::class.java))
    }

    private fun startLiveUpdate() {
        ContextCompat.startForegroundService(this, Intent(this, LiveUpdateService::class.java))
    }

    private fun startIsland() {
        ContextCompat.startForegroundService(this, Intent(this, IslandService::class.java))
    }

    private fun startVivoAtomic() {
        ContextCompat.startForegroundService(this, Intent(this, VivoAtomicService::class.java))
    }

    private fun buildFloatItems() {
        val container = findViewById<LinearLayout>(R.id.llFloatItems)
        val selected = Prefs.getFloatingItems(this)
        coreCheckbox = null
        for (key in FloatingItems.ALL) {
            val labelRes = floatLabels[key] ?: continue
            val cb = CheckBox(this)
            cb.text = getString(labelRes)
            cb.setTextColor(MaterialColors.getColor(cb, com.google.android.material.R.attr.colorOnSurface))
            cb.isChecked = key in selected
            cb.setOnCheckedChangeListener { _, _ -> persistFloatItems() }
            container.addView(cb)
            floatCheckboxes[key] = cb

            if (key == FloatingItems.CORE) {
                coreCheckbox = cb
                cb.setOnLongClickListener {
                    showCoreSelectDialog()
                    true
                }
            }
        }
    }

    private fun persistFloatItems() {
        val selected = floatCheckboxes.filterValues { it.isChecked }.keys
        Prefs.setFloatingItems(this, selected)
    }

    private fun refreshFloatingItemsEnabled() {
        val mode = Prefs.getDisplayMode(this)
        val enabled = mode == DisplayMode.FLOATING || mode == DisplayMode.ISLAND || mode == DisplayMode.VIVO_ATOMIC
        floatCheckboxes.values.forEach { it.isEnabled = enabled }
    }

    private fun showCoreSelectDialog() {
        val coreCount = CpuReader.readCoreCount()
        val currentSelection = Prefs.getFloatingCoreIndices(this)

        val labels = Array(coreCount) { i -> getString(R.string.cpu_core_label, i) }
        val checked = BooleanArray(coreCount) { currentSelection.isEmpty() || it in currentSelection }

        AlertDialog.Builder(this)
            .setTitle(R.string.core_select_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = checked.indices.filter { checked[it] }.toSet()
                Prefs.setFloatingCoreIndices(this, selected)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.core_select_all) { _, _ ->
                val allSelected = (0 until coreCount).toSet()
                Prefs.setFloatingCoreIndices(this, allSelected)
            }
            .show()
    }

    // ---------- 主题 ----------

    private fun setupThemePicker() {
        val radioGroup = findViewById<RadioGroup>(R.id.rgTheme)
        val checkedId = when (Prefs.getThemeMode(this)) {
            ThemeMode.LIGHT -> R.id.rbThemeLight
            ThemeMode.DARK -> R.id.rbThemeDark
            else -> R.id.rbThemeSystem
        }
        radioGroup.check(checkedId)
        radioGroup.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.rbThemeLight -> ThemeMode.LIGHT
                R.id.rbThemeDark -> ThemeMode.DARK
                else -> ThemeMode.FOLLOW_SYSTEM
            }
            Prefs.setThemeMode(this, mode)
            val nightMode = when (mode) {
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    // ---------- 莫奈取色 ----------

    private fun setupMonetSwitch() {
        switchMonet = findViewById(R.id.switchMonet)
        val monetContainer = findViewById<LinearLayout>(R.id.llMonet)
        // Android 12 以下不支持动态取色，隐藏开关并继续使用原配色
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            monetContainer.visibility = View.GONE
            return
        }
        switchMonet.isChecked = Prefs.isMonetEnabled(this)
        switchMonet.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setMonetEnabled(this, isChecked)
            // 防抖：快速连续切换时只重建一次，避免每次切换都整页重建导致界面频繁卡顿
            monetRecreateHandler.removeCallbacks(monetRecreateRunnable)
            monetRecreateHandler.postDelayed(monetRecreateRunnable, MONET_RECREATE_DELAY_MS)
        }
    }

    // ---------- Root ----------

    private fun updateRootStatus() {
        val granted = RootShell.granted || Prefs.isRootGranted(this)
        tvRootStatus.text = getString(if (granted) R.string.root_granted else R.string.root_missing)
    }

    private fun reRequestRoot() {
        tvRootStatus.text = getString(R.string.root_missing)
        Thread {
            val granted = RootShell.requestRoot()
            runOnUiThread {
                Prefs.setRootGranted(this, granted)
                updateRootStatus()
                if (!granted) {
                    Toast.makeText(this, R.string.root_missing_toast, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ---------- Shizuku ----------

    private fun updateShizukuStatus() {
        tvShizukuStatus.text = getString(
            when {
                !ShizukuShell.isAvailable() -> R.string.shizuku_not_running
                !ShizukuShell.isGranted() -> R.string.shizuku_no_permission
                else -> R.string.shizuku_granted
            }
        )
    }

    private fun requestShizuku() {
        when {
            !ShizukuShell.isAvailable() -> {
                Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_SHORT).show()
            }
            ShizukuShell.isGranted() -> {
                updateShizukuStatus()
            }
            else -> {
                ShizukuShell.requestPermission(REQ_SHIZUKU)
            }
        }
    }

    // ---------- 关于 ----------

    /** 使用系统默认浏览器打开哔哩哔哩主页 */
    private fun openBilibili() {
        val uri = Uri.parse(getString(R.string.about_bilibili_url))
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.about_bilibili_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 打开 GitHub 开源仓库（MIT 协议） */
    private fun openSource() {
        val uri = Uri.parse(getString(R.string.about_open_source_url))
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.about_bilibili_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQ_NOTIFY = 2001
        private const val REQ_SHIZUKU = 2002
        /** 莫奈取色开关防抖重建延迟：等待开关动画结束，并将连续切换合并为一次重建 */
        private const val MONET_RECREATE_DELAY_MS = 300L
    }
}