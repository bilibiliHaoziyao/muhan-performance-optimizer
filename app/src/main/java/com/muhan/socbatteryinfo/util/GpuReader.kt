package com.muhan.socbatteryinfo.util

/** GPU 数据：利用率（%）与频率（MHz），以及是否通过 Root 获取 */
data class GpuData(
    val busyPercent: Float?,
    val freqMhz: Int?,
    val viaRoot: Boolean
)

/**
 * GPU 利用率与频率读取。
 * 同时覆盖高通 Adreno（kgsl）与联发科/ARM Mali（misc/mali0、devfreq）两类常见路径。
 * 优先普通权限读取，失败则尝试 Root；仍失败返回 null（界面显示“设备不支持”）。
 */
object GpuReader {

    // 利用率节点：单位均为 0~100 百分比（个别 Mali 老驱动为 0~1024，读取时做兼容换算）
    private val busyPaths = listOf(
        // Mali / Immortalis（联发科等）
        "/sys/class/misc/mali0/device/gpu_utilization",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/class/devfreq/13000000.mali/load",
        "/sys/class/devfreq/13000000.mali/gpu_load",
        // 高通 Adreno
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        // 通用
        "/sys/kernel/gpu/gpu_busy"
    )

    private val freqPaths = listOf(
        // Mali / Immortalis
        "/sys/class/misc/mali0/device/clock",
        "/sys/class/misc/mali0/device/freq",
        "/sys/class/misc/mali0/device/cur_freq",
        "/sys/class/devfreq/13000000.mali/cur_freq",
        // 高通 Adreno
        "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
        "/sys/class/kgsl/kgsl-3d0/gpuclk",
        "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
        "/sys/class/kgsl/kgsl-3d0/clock_mhz"
    )

    fun read(): GpuData {
        var busy = readBusy(nullable = false)
        var freq = readFreq(nullable = false)
        var viaRoot = false

        if (busy == null || freq == null) {
            val shizukuBusy = readBusy(nullable = true, useShizuku = true)
            val shizukuFreq = readFreq(nullable = true, useShizuku = true)
            if (shizukuBusy != null) busy = shizukuBusy
            if (shizukuFreq != null) freq = shizukuFreq
        }

        if (busy == null || freq == null) {
            val rootBusy = readBusy(nullable = true, useRoot = true)
            val rootFreq = readFreq(nullable = true, useRoot = true)
            if (rootBusy != null) { busy = rootBusy; viaRoot = true }
            if (rootFreq != null) { freq = rootFreq; viaRoot = true }
        }
        return GpuData(busy, freq, viaRoot)
    }

    /** gpubusy 返回 "busy total" 两个计数值，需换算成百分比 */
    private val ratioPaths = setOf(
        "/sys/class/kgsl/kgsl-3d0/gpubusy"
    )

    private fun readBusy(nullable: Boolean, useShizuku: Boolean = false, useRoot: Boolean = false): Float? {
        for (path in busyPaths) {
            val raw = readPath(path, useShizuku = useShizuku, useRoot = useRoot) ?: continue
            val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue

            if (path in ratioPaths) {
                val busy = tokens.getOrNull(0)?.toFloatOrNull() ?: continue
                val total = tokens.getOrNull(1)?.toFloatOrNull() ?: continue
                if (total <= 0f) continue
                val pct = busy / total * 100f
                if (pct in 0f..100f) return pct
                continue
            }

            val value = tokens.first().toFloatOrNull() ?: continue
            val pct = when {
                value in 0f..100f -> value
                value in 100f..1024f -> value / 1024f * 100f
                else -> continue
            }
            if (pct in 0f..100f) return pct
        }
        return null
    }

    private fun readFreq(nullable: Boolean, useShizuku: Boolean = false, useRoot: Boolean = false): Int? {
        for (path in freqPaths) {
            val raw = readPath(path, useShizuku = useShizuku, useRoot = useRoot) ?: continue
            val value = raw.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: continue
            if (value <= 0) continue
            return when {
                value >= 1_000_000L -> (value / 1_000_000L).toInt()
                value >= 1000L -> (value / 1000L).toInt()
                else -> value.toInt()
            }
        }
        return null
    }

    private fun readPath(path: String, useShizuku: Boolean = false, useRoot: Boolean = false): String? {
        return when {
            useRoot -> RootShell.exec("cat $path")
            useShizuku -> ShizukuShell.exec("cat $path")
            else -> ThermalReader.readFile(path)
        }
    }
}