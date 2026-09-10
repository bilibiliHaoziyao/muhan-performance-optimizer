package com.muhan.socbatteryinfo.util

import android.os.Build

/**
 * CPU 利用率采样器。
 * 单例对象：主界面与悬浮窗共享同一份缓存，避免各自独立采样导致结果不一致。
 *
 * 读取策略依次尝试（参考 top / dumpsys cpuinfo 命令方式，并以 /proc/stat 兜底）：
 * 1. /proc/stat 两次采样差值（最准确，无需 shell）；
 * 2. `top` 命令解析整体空闲比（需 shell：普通 sh → Root → Shizuku）；
 * 3. `dumpsys cpuinfo` 解析 TOTAL 行（需 shell：普通 sh → Root → Shizuku）。
 *
 * 计算在独立后台线程进行，`sample()` 仅返回缓存值，避免阻塞主线程。
 */
object CpuUsageSampler {
    private const val SAMPLE_INTERVAL_MS = 1000L

    // /proc/stat 差值采样状态（只在后台线程读写）
    private var prevIdle = -1L
    private var prevTotal = -1L

    @Volatile
    private var cachedUsage: Float? = null

    private var started = false

    /** 返回 CPU 总体利用率百分比；首次采样完成前会短暂等待（≤300ms）以便立即返回 */
    fun sample(): Float? {
        ensureStarted()
        cachedUsage?.let { return it }
        // 首采尚未完成：短暂等待后台线程（首采约 200ms）尽快返回可用值
        for (i in 0 until 30) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                return cachedUsage
            }
            cachedUsage?.let { return it }
        }
        return cachedUsage
    }

    private fun ensureStarted() {
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread({
            while (true) {
                val usage = compute()
                if (usage != null) cachedUsage = usage
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "CpuUsageSampler").apply {
            isDaemon = true
            start()
        }
    }

    private fun compute(): Float? {
        sampleProcStat()?.let { return it }
        sampleTop()?.let { return it }
        return sampleDumpsys()
    }

    /** /proc/stat 两次采样差值，计算总体利用率；首次采样会做一次短暂二次采样立即返回 */
    private fun sampleProcStat(): Float? {
        val stat = readStat() ?: return null
        val cpuLine = stat.lines().firstOrNull { it.trim().startsWith("cpu ") } ?: return null
        val parts = cpuLine.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return null
        val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
        val total = parts.sum()
        if (prevIdle < 0) {
            // 首采：短暂间隔后二次采样，立即得到可用利用率，避免首屏等待
            prevIdle = idle
            prevTotal = total
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return null
            }
            val stat2 = readStat() ?: return null
            val cpuLine2 = stat2.lines().firstOrNull { it.trim().startsWith("cpu ") } ?: return null
            val parts2 = cpuLine2.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts2.size < 4) return null
            val idle2 = parts2[3] + (parts2.getOrNull(4) ?: 0L)
            val total2 = parts2.sum()
            val dIdle = idle2 - prevIdle
            val dTotal = total2 - prevTotal
            prevIdle = idle2
            prevTotal = total2
            if (dTotal <= 0) return null
            return ((dTotal - dIdle).toFloat() / dTotal * 100f).coerceIn(0f, 100f)
        }
        val dIdle = idle - prevIdle
        val dTotal = total - prevTotal
        prevIdle = idle
        prevTotal = total
        if (dTotal <= 0) return null
        return ((dTotal - dIdle).toFloat() / dTotal * 100f).coerceIn(0f, 100f)
    }

    /** 优先普通权限读 /proc/stat，失败则尝试 Shizuku，再失败尝试 root */
    private fun readStat(): String? {
        ThermalReader.readFile("/proc/stat")?.let { return it }
        ShizukuShell.exec("cat /proc/stat")?.let { return it }
        if (RootShell.granted) {
            RootShell.exec("cat /proc/stat")?.let { return it }
        }
        return null
    }

    /**
     * 解析 `top -n 1` 输出中的整体空闲占比行：
     * 形如 `800%cpu  27%user  ...  438%idle ...`，利用率 = (total - idle) / total * 100。
     */
    private fun sampleTop(): Float? {
        val out = ShellExec.exec("top -b -n 1")
            ?: ShellExec.exec("top -n 1")
            ?: return null
        val line = out.lines().firstOrNull {
            it.contains("cpu", ignoreCase = true) &&
                it.contains("idle", ignoreCase = true) &&
                it.contains("user", ignoreCase = true)
        } ?: return null
        val idle = Regex("(\\d+(?:\\.\\d+)?)%\\s*idle", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        val total = Regex("(\\d+(?:\\.\\d+)?)%\\s*cpu", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.get(1)?.toFloatOrNull()
        if (total == null || total <= 0f) return null
        val usage = (total - idle) / total * 100f
        return usage.takeIf { it in 0f..100f }
    }

    /** 解析 `dumpsys cpuinfo` 输出中的 `X% TOTAL:` 行，X 即整体利用率 */
    private fun sampleDumpsys(): Float? {
        val out = ShellExec.exec("dumpsys cpuinfo") ?: return null
        val line = out.lines().firstOrNull { it.contains("TOTAL") } ?: return null
        val pct = Regex("([\\d.]+)%").find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        return pct.takeIf { it in 0f..100f }
    }
}

/** CPU 频率读取 */
object CpuReader {

    /** 返回所有在线核心平均频率（MHz，即 kHz/1000） */
    fun readFreqMhz(): Int? {
        var sumKhz = 0L
        var count = 0
        for (cpu in 0 until 32) {
            val khz = readCpuFreqKhz(cpu) ?: continue
            sumKhz += khz
            count++
        }
        if (count == 0) return null
        return (sumKhz / count / 1000L).toInt()
    }

    /** 返回 CPU 核心数量，读取 /proc/cpuinfo 中的 processor 字段计数 */
    fun readCoreCount(): Int {
        val info = ThermalReader.readFile("/proc/cpuinfo") ?: return Runtime.getRuntime().availableProcessors()
        val count = Regex("^processor\\s*:", RegexOption.MULTILINE).findAll(info).count()
        return if (count > 0) count else Runtime.getRuntime().availableProcessors()
    }

    /** 返回 CPU 指令集架构，如 arm64-v8a 或 x86_64 */
    fun readAbi(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS?.firstOrNull() ?: Build.CPU_ABI ?: "未知"
        } else {
            @Suppress("DEPRECATION")
            Build.CPU_ABI ?: "未知"
        }
    }

    /** 返回单个核心的频率（MHz），读取失败返回 null */
    fun readFreqMhzForCore(cpu: Int): Int? {
        val khz = readCpuFreqKhz(cpu) ?: return null
        return (khz / 1000L).toInt()
    }

    private fun readCpuFreqKhz(cpu: Int): Long? {
        val candidates = listOf(
            "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq",
            "/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_cur_freq"
        )
        for (path in candidates) {
            ThermalReader.readFile(path)?.toLongOrNull()?.let { if (it > 0) return it }
            ShizukuShell.exec("cat $path")?.toLongOrNull()?.let { if (it > 0) return it }
            if (RootShell.granted) {
                RootShell.exec("cat $path")?.toLongOrNull()?.let { if (it > 0) return it }
            }
        }
        return null
    }
}

/** 单个核心的快照 */
data class CpuCoreSnapshot(
    val index: Int,
    val usagePercent: Float?,
    val freqMhz: Int?
)

/**
 * 分核心 CPU 采样器。
 * 单例对象：独立后台线程每秒采样 /proc/stat 的 cpuN 行，
 * 同时读取每个核心当前频率（kHz → MHz）。
 * 数据缓存于内存，UI 线程直接读取，避免阻塞。
 */
object CpuCoreSampler {
    private const val SAMPLE_INTERVAL_MS = 1000L
    private const val MAX_CORES = 32

    private var coreCount = 0

    // 每个核心的上一次采样状态
    private data class CoreState(
        var prevIdle: Long = -1L,
        var prevTotal: Long = -1L,
        var cachedUsage: Float? = null
    )

    private val states = arrayOfNulls<CoreState>(MAX_CORES)

    @Volatile
    private var cachedSnapshots: Array<CpuCoreSnapshot>? = null

    private var started = false

    /** 返回当前所有核心的快照数组（按 core index 排序），首次采样完成前可能为空数组 */
    fun sample(): Array<CpuCoreSnapshot> {
        ensureStarted()
        return cachedSnapshots ?: emptyArray()
    }

    private fun ensureStarted() {
        synchronized(this) {
            if (started) return
            started = true
        }
        coreCount = CpuReader.readCoreCount()
        Thread({
            while (true) {
                val snap = compute()
                cachedSnapshots = snap
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "CpuCoreSampler").apply {
            isDaemon = true
            start()
        }
    }

    private fun compute(): Array<CpuCoreSnapshot> {
        val stat = readStat() ?: return emptyArray()
        val lines = stat.lines()
        val snapshots = ArrayList<CpuCoreSnapshot>(coreCount)

        for (i in 0 until coreCount) {
            val line = lines.firstOrNull { it.trim().startsWith("cpu$i ") }
            val usage = parseCoreUsage(i, line)
            val freq = CpuReader.readFreqMhzForCore(i)
            snapshots.add(CpuCoreSnapshot(i, usage, freq))
        }

        // 如果没有找到 per-core 行，回退到整体行拆分（罕见 ROM）
        if (snapshots.isEmpty()) {
            val totalLine = lines.firstOrNull { it.trim().startsWith("cpu ") }
            if (totalLine != null) {
                val parts = totalLine.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (parts.size >= 4) {
                    val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
                    val total = parts.sum()
                    val usage = if (total > 0) ((total - idle).toFloat() / total * 100f) else 0f
                    val freq = CpuReader.readFreqMhz()
                    val perCore = usage / coreCount
                    repeat(coreCount) { idx ->
                        snapshots.add(CpuCoreSnapshot(idx, perCore, freq))
                    }
                }
            }
        }
        return snapshots.toTypedArray()
    }

    private fun parseCoreUsage(index: Int, line: String?): Float? {
        if (line == null) return null
        val state = states[index] ?: CoreState().also { states[index] = it }
        val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return null
        val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
        val total = parts.sum()
        if (state.prevIdle < 0) {
            state.prevIdle = idle
            state.prevTotal = total
            return state.cachedUsage
        }
        val dIdle = idle - state.prevIdle
        val dTotal = total - state.prevTotal
        state.prevIdle = idle
        state.prevTotal = total
        if (dTotal <= 0) return state.cachedUsage
        val usage = ((dTotal - dIdle).toFloat() / dTotal * 100f).coerceIn(0f, 100f)
        state.cachedUsage = usage
        return usage
    }

    private fun readStat(): String? {
        ThermalReader.readFile("/proc/stat")?.let { return it }
        ShizukuShell.exec("cat /proc/stat")?.let { return it }
        if (RootShell.granted) {
            RootShell.exec("cat /proc/stat")?.let { return it }
        }
        return null
    }
}