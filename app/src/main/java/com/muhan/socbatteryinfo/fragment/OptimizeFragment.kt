package com.muhan.socbatteryinfo.fragment

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.muhan.socbatteryinfo.AppManageActivity
import com.muhan.socbatteryinfo.R
import com.muhan.socbatteryinfo.service.OptimizeService
import com.muhan.socbatteryinfo.util.MemoryCleaner
import com.muhan.socbatteryinfo.util.Prefs
import com.muhan.socbatteryinfo.util.StorageReader
import com.muhan.socbatteryinfo.view.LineChartView
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * 优化 Tab：运存监控与自动清理。
 * 支持阈值调节、白名单（默认 QQ/微信）、一键清理与后台监控服务（每 3 分钟检查）。
 */
class OptimizeFragment : Fragment() {

    private lateinit var tvRamUsage: TextView
    private lateinit var tvRamDetail: TextView
    private lateinit var tvRamAvailable: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var chartRam: LineChartView
    private lateinit var swAutoClean: MaterialSwitch
    private lateinit var tvThreshold: TextView
    private lateinit var sbThreshold: SeekBar
    private lateinit var tvCleanStat: TextView
    private lateinit var btnCleanNow: Button
    private lateinit var tvWhitelist: TextView
    private lateinit var btnAddWhitelist: Button
    private lateinit var btnBatteryPermission: Button
    private lateinit var btnUsageAccess: Button
    private lateinit var btnAppManage: Button

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val ramHistory = ArrayDeque<Float>()
    private val MAX_POINTS = 60

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshRam()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_optimize, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvRamUsage = view.findViewById(R.id.tvRamUsage)
        tvRamDetail = view.findViewById(R.id.tvRamDetail)
        tvRamAvailable = view.findViewById(R.id.tvRamAvailable)
        tvServiceStatus = view.findViewById(R.id.tvServiceStatus)
        chartRam = view.findViewById(R.id.chartRam)
        chartRam.maxValue = 100f
        chartRam.minValue = 0f
        swAutoClean = view.findViewById(R.id.swAutoClean)
        tvThreshold = view.findViewById(R.id.tvThreshold)
        sbThreshold = view.findViewById(R.id.sbThreshold)
        tvCleanStat = view.findViewById(R.id.tvCleanStat)
        btnCleanNow = view.findViewById(R.id.btnCleanNow)
        tvWhitelist = view.findViewById(R.id.tvWhitelist)
        btnAddWhitelist = view.findViewById(R.id.btnAddWhitelist)
        btnBatteryPermission = view.findViewById(R.id.btnBatteryPermission)
        btnUsageAccess = view.findViewById(R.id.btnUsageAccess)
        btnAppManage = view.findViewById(R.id.btnAppManage)

        // 自动清理开关与阈值
        val threshold = Prefs.getCleanThreshold(requireContext())
        sbThreshold.progress = threshold - 50
        updateThresholdText(threshold)
        sbThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = progress + 50
                updateThresholdText(value)
                Prefs.setCleanThreshold(requireContext(), value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        swAutoClean.isChecked = Prefs.isAutoCleanEnabled(requireContext())
        swAutoClean.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAutoCleanEnabled(requireContext(), isChecked)
            if (isChecked) {
                startOptimizeService()
            } else {
                stopOptimizeService()
            }
            updateServiceStatus()
        }

        btnCleanNow.setOnClickListener { cleanNow() }
        btnAddWhitelist.setOnClickListener { showWhitelistPicker() }
        tvWhitelist.setOnClickListener { showWhitelistPicker() }
        btnBatteryPermission.setOnClickListener { requestIgnoreBatteryOptimizations() }
        btnUsageAccess.setOnClickListener { requestUsageAccess() }
        btnAppManage.setOnClickListener {
            startActivity(Intent(requireContext(), AppManageActivity::class.java))
        }

        updateWhitelist()
        updateServiceStatus()
        updateBatteryPermissionText()
        updateUsageAccessText()
        updateCleanStat()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        refreshRam()
        handler.postDelayed(refreshRunnable, 1000L)
        updateServiceStatus()
        updateBatteryPermissionText()
        updateUsageAccessText()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    // ---------- 运存刷新 ----------

    private fun refreshRam() {
        val usage = MemoryCleaner.usagePercent()
        if (usage != null) {
            tvRamUsage.text = String.format("%.0f%%", usage)
            ramHistory.addLast(usage)
            while (ramHistory.size > MAX_POINTS) ramHistory.removeFirst()
            chartRam.setHistory(ramHistory.toList())
        } else {
            tvRamUsage.text = "--"
        }
        val used = MemoryCleaner.usedBytes()
        val total = MemoryCleaner.totalBytes()
        if (used != null && total != null) {
            tvRamDetail.text = String.format(
                "已用 %s / 总计 %s",
                StorageReader.formatBytes(used),
                StorageReader.formatBytes(total)
            )
            tvRamAvailable.text = getString(
                R.string.optimize_ram_available,
                StorageReader.formatBytes(total - used)
            )
        }
    }

    private fun updateThresholdText(value: Int) {
        tvThreshold.text = getString(R.string.optimize_threshold_value, value)
    }

    private fun updateCleanStat() {
        tvCleanStat.text = getString(
            R.string.optimize_clean_stat,
            Prefs.getCleanStatCount(requireContext())
        )
    }

    // ---------- 清理 ----------

    private fun cleanNow() {
        // 进入后台线程前先取 applicationContext，避免 Fragment 已销毁时 requireContext() 崩溃
        val context = requireContext().applicationContext
        val whitelist = Prefs.getCleanWhitelist(context)
        executor.execute {
            val result = MemoryCleaner.cleanBackgroundProcesses(context, whitelist)
            if (result.succeeded > 0) Prefs.incrementCleanStat(context)
            handler.post {
                if (!isAdded) return@post
                val message = when {
                    result.reason == MemoryCleaner.FailureReason.UNSUPPORTED ->
                        getString(R.string.optimize_clean_unsupported)
                    result.reason == MemoryCleaner.FailureReason.NO_CANDIDATES ->
                        getString(R.string.optimize_clean_none)
                    result.succeeded > 0 ->
                        getString(R.string.optimize_clean_result, result.succeeded)
                    else -> getString(R.string.optimize_clean_none)
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                updateCleanStat()
                refreshRam()
            }
        }
    }

    // ---------- 服务 ----------

    private fun startOptimizeService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFY
            )
            return
        }
        ContextCompat.startForegroundService(
            requireContext(),
            Intent(requireContext(), OptimizeService::class.java)
        )
    }

    private fun stopOptimizeService() {
        requireContext().stopService(Intent(requireContext(), OptimizeService::class.java))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (swAutoClean.isChecked) startOptimizeService()
            } else {
                // 通知权限被拒：回滚开关与设置，避免"显示已开启但服务未运行"
                swAutoClean.isChecked = false
                Prefs.setAutoCleanEnabled(requireContext(), false)
                updateServiceStatus()
                Toast.makeText(requireContext(), R.string.notify_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateServiceStatus() {
        // 由服务自身维护运行状态，getRunningServices 在 Android 8+ 对其他应用不可靠
        tvServiceStatus.text = getString(
            if (OptimizeService.isRunning) R.string.optimize_service_running else R.string.optimize_service_stopped
        )
    }

    // ---------- 白名单 ----------

    private fun updateWhitelist() {
        val whitelist = Prefs.getCleanWhitelist(requireContext())
        tvWhitelist.text = if (whitelist.isEmpty()) {
            getString(R.string.optimize_whitelist_empty)
        } else {
            whitelist.joinToString("\n")
        }
    }

    private data class UserApp(val pkg: String, val label: String)

    /** 列出所有第三方（非系统）应用 */
    private fun loadUserApps(context: Context): List<UserApp> {
        return try {
            val pm = context.packageManager
            pm.getInstalledApplications(android.content.pm.ApplicationInfo.FLAG_INSTALLED or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
                .filter { app ->
                    app.sourceDir != null &&
                    app.packageName != context.packageName &&
                    // 第三方应用：非系统应用或更新过的系统应用
                    ((app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                }
                .map { app ->
                    UserApp(
                        app.packageName,
                        pm.getApplicationLabel(app).toString()
                    )
                }
                .sortedBy { it.label.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 可视化白名单管理：列出所有用户安装的应用，勾选加入/移出白名单 */
    private fun showWhitelistPicker() {
        val context = requireContext()
        val loading = AlertDialog.Builder(context)
            .setTitle(R.string.optimize_whitelist_select_title)
            .setMessage(R.string.optimize_whitelist_loading)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        executor.execute {
            val apps = loadUserApps(context)
            val checked = Prefs.getCleanWhitelist(context).toMutableSet()
            handler.post {
                if (!isAdded) return@post
                loading.dismiss()
                val names = apps.map { it.label }
                val selected = BooleanArray(apps.size) { checked.contains(apps[it].pkg) }
                AlertDialog.Builder(context)
                    .setTitle(R.string.optimize_whitelist_select_title)
                    .setMultiChoiceItems(names.toTypedArray(), selected) { _, which, isChecked ->
                        val pkg = apps[which].pkg
                        if (isChecked) checked.add(pkg) else checked.remove(pkg)
                    }
                    .setPositiveButton(R.string.optimize_whitelist_add) { _, _ ->
                        Prefs.setCleanWhitelist(context, checked)
                        updateWhitelist()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    // ---------- 使用情况访问 ----------

    private fun requestUsageAccess() {
        if (MemoryCleaner.hasUsageStatsPermission(requireContext())) {
            Toast.makeText(
                requireContext(),
                R.string.optimize_usage_access_granted,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.optimize_usage_access),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateUsageAccessText() {
        btnUsageAccess.text = getString(
            if (MemoryCleaner.hasUsageStatsPermission(requireContext())) {
                R.string.optimize_usage_access_granted
            } else {
                R.string.optimize_usage_access
            }
        )
    }

    // ---------- 电池优化 ----------

    private fun updateBatteryPermissionText() {
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(requireContext().packageName)
        } else {
            true
        }
        btnBatteryPermission.text = getString(
            if (ignored) R.string.optimize_battery_permission_granted else R.string.optimize_battery_permission
        )
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
            Toast.makeText(
                requireContext(),
                R.string.optimize_battery_permission_granted,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:${requireContext().packageName}")
        )
        startActivity(intent)
    }

    companion object {
        private const val REQ_NOTIFY = 3002
    }
}
