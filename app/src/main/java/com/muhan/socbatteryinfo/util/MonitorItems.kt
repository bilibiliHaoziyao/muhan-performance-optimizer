package com.muhan.socbatteryinfo.util

import android.content.Context
import com.muhan.socbatteryinfo.R

/** 一项监测内容的展示数据：标签 + 值 */
data class MonitorLine(val label: String, val value: String)

/**
 * 按用户勾选的显示项生成内容行。
 *
 * 悬浮窗与小米超级岛共用，保证同一份选择在两处显示一致。
 */
object MonitorItems {

    /** 生成当前选中项的所有内容行（按 FloatingItems.ALL 顺序） */
    fun selectedLines(context: Context, battery: BatteryData): List<MonitorLine> {
        val items = Prefs.getFloatingItems(context)
        if (items.isEmpty()) return emptyList()
        val lines = mutableListOf<MonitorLine>()
        for (key in FloatingItems.ALL) {
            if (key !in items) continue
            lineFor(context, key, battery)?.let { lines.addAll(it) }
        }
        return lines
    }

    private fun lineFor(context: Context, key: String, battery: BatteryData): List<MonitorLine>? {
        return when (key) {
            FloatingItems.CPU -> {
                val usage = CpuUsageSampler.sample()
                val freq = CpuReader.readFreqMhz()
                if (usage == null && freq == null) null
                else {
                    val main = usage?.let { String.format("%.0f%%", it) } ?: "--"
                    val sub = freq?.let { String.format("%.2fGHz", it / 1000f) } ?: "--"
                    listOf(MonitorLine("CPU", "$main $sub"))
                }
            }
            FloatingItems.CORE -> {
                val cores = CpuCoreSampler.sample()
                if (cores.isEmpty()) null
                else {
                    val selected = Prefs.getFloatingCoreIndices(context)
                    val filtered = if (selected.isNotEmpty()) {
                        cores.filter { it.index in selected }
                    } else {
                        cores.toList()
                    }
                    filtered.map { snap ->
                        val u = snap.usagePercent?.let { String.format("%.0f%%", it) } ?: "--"
                        MonitorLine("C${snap.index}", u)
                    }
                }
            }
            FloatingItems.GPU -> {
                val gpu = GpuReader.read()
                val busy = gpu.busyPercent?.let { String.format("%.0f%%", it) }
                val freq = gpu.freqMhz?.let { String.format("%dMHz", it) }
                when {
                    busy != null && freq != null -> listOf(MonitorLine("GPU", "$busy $freq"))
                    busy != null -> listOf(MonitorLine("GPU", busy))
                    freq != null -> listOf(MonitorLine("GPU", freq))
                    else -> listOf(
                        MonitorLine(
                            "GPU",
                            context.getString(if (RootShell.granted) R.string.unsupported else R.string.needs_root)
                        )
                    )
                }
            }
            FloatingItems.SOC_TEMP -> {
                val t = ThermalReader.readSocTemperature() ?: battery.temperatureC?.plus(3f)
                t?.let { listOf(MonitorLine("SoC", String.format("%.1f°C", it))) }
            }
            FloatingItems.BATTERY_TEMP -> {
                battery.temperatureC?.let { listOf(MonitorLine("电池", String.format("%.1f°C", it))) }
            }
            FloatingItems.POWER -> {
                battery.powerWatts(Prefs.isDualCell(context))
                    ?.let { listOf(MonitorLine("功率", String.format("%.1fW", it))) }
            }
            FloatingItems.LEVEL -> {
                battery.capacityPercent?.let { listOf(MonitorLine("电量", "$it%")) }
            }
            FloatingItems.REFRESH -> {
                listOf(MonitorLine("刷新", String.format("%.0fHz", ScreenReader.read(context).refreshRate)))
            }
            else -> null
        }
    }
}
