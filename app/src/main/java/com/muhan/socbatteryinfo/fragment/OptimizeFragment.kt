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
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.muhan.socbatteryinfo.R
import com.muhan.socbatteryinfo.service.OptimizeService
import com.muhan.socbatteryinfo.util.MemoryCleaner
import com.muhan.socbatteryinfo.util.Prefs
import com.muhan.socbatteryinfo.util.StorageReader
import com.muhan.socbatteryinfo.view.LineChartView
import java.util.ArrayDeque

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

    private val handler = Handler(Looper.getMainLooper())
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
        btnAddWhitelist.setOnClickListener { showAddWhitelistDialog() }
        tvWhitelist.setOnClickListener { showWhitelistPicker() }
        btnBatteryPermission.setOnClickListener { requestIgnoreBatteryOptimizations() }

        updateWhitelist()
        updateServiceStatus()
        updateBatteryPermissionText()
        updateCleanStat()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        refreshRam()
        handler.postDelayed(refreshRunnable, 1000L)
        updateServiceStatus()
        updateBatteryPermissionText()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(refreshRunnable)
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
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            val cleared = MemoryCleaner.cleanBackgroundProcesses(
                requireContext(),
                Prefs.getCleanWhitelist(requireContext())
            )
            if (cleared > 0) Prefs.incrementCleanStat(requireContext())
            handler.post {
                if (!isAdded) return@post
                Toast.makeText(
                    requireContext(),
                    if (cleared > 0) {
                        getString(R.string.optimize_clean_result, cleared)
                    } else {
                        getString(R.string.optimize_clean_none)
                    },
                    Toast.LENGTH_SHORT
                ).show()
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
        if (requestCode == REQ_NOTIFY && swAutoClean.isChecked) {
            startOptimizeService()
        }
    }

    private fun updateServiceStatus() {
        val running = isServiceRunning(OptimizeService::class.java)
        tvServiceStatus.text = getString(
            if (running) R.string.optimize_service_running else R.string.optimize_service_stopped
        )
    }

    private fun isServiceRunning(service: Class<*>): Boolean {
        val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
        return am.getRunningServices(100).any { it.service.className == service.name }
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

    private fun showAddWhitelistDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.optimize_whitelist_add_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.optimize_whitelist_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.optimize_whitelist_add) { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.optimize_whitelist_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val whitelist = Prefs.getCleanWhitelist(requireContext()).toMutableSet()
                whitelist.add(pkg)
                Prefs.setCleanWhitelist(requireContext(), whitelist)
                updateWhitelist()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWhitelistPicker() {
        val whitelist = Prefs.getCleanWhitelist(requireContext()).toList()
        if (whitelist.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.optimize_whitelist)
            .setItems(whitelist.toTypedArray()) { _, which ->
                showRemoveWhitelistDialog(whitelist[which])
            }
            .show()
    }

    private fun showRemoveWhitelistDialog(pkg: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(pkg)
            .setMessage(R.string.optimize_whitelist_remove)
            .setPositiveButton(R.string.optimize_whitelist_remove) { _, _ ->
                val whitelist = Prefs.getCleanWhitelist(requireContext()).toMutableSet()
                whitelist.remove(pkg)
                Prefs.setCleanWhitelist(requireContext(), whitelist)
                updateWhitelist()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
