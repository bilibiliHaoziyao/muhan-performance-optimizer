package com.muhan.socbatteryinfo.util

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * 运存优化工具：
 * - usagePercent() 读取运存占用率（/proc/meminfo）
 * - cleanBackgroundProcesses() 清理后台进程（跳过自身、白名单与前台应用）
 * - hasUsageStatsPermission() 是否已授权「使用情况访问」权限
 *
 * 清理策略分级（Android 14+ 普通应用调用 killBackgroundProcesses 只能杀自己）：
 *   1. Root 可用    → am kill --user 0 <pkg>
 *   2. Shizuku 可用 → am kill --user 0 <pkg>（ADB Shell 权限）
 *   3. 普通权限     → ActivityManager.killBackgroundProcesses()（Android 13 及以下有效）
 */
object MemoryCleaner {

    /** 清理失败原因 */
    enum class FailureReason {
        /** 当前系统版本与权限组合不支持批量清理（Android 14+ 且没有 Root/Shizuku） */
        UNSUPPORTED,
        /** 没有可清理的候选应用 */
        NO_CANDIDATES
    }

    /** 清理结果统计 */
    data class CleanResult(
        val attempted: Int,
        val succeeded: Int,
        val skipped: Int,
        val failed: Int,
        val reason: FailureReason? = null
    ) {
        /** 清理路径：root / shizuku / legacy */
        var path: String = "legacy"
    }

    /** 是否已授权「使用情况访问」权限（用于更准确地识别后台应用） */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /** 当前运存占用率（0-100），读取失败返回 null */
    fun usagePercent(): Float? {
        val ram = StorageReader.readRam()
        val total = ram.totalBytes ?: return null
        val available = ram.availableBytes ?: return null
        if (total <= 0) return null
        return ((total - available).toFloat() / total * 100f).coerceIn(0f, 100f)
    }

    /** 已用运存（字节），读取失败返回 null */
    fun usedBytes(): Long? {
        val ram = StorageReader.readRam()
        val total = ram.totalBytes ?: return null
        val available = ram.availableBytes ?: return null
        return total - available
    }

    /** 总运存（字节），读取失败返回 null */
    fun totalBytes(): Long? = StorageReader.readRam().totalBytes

    /**
     * 清理后台进程。
     * Android 14 起 killBackgroundProcesses 对第三方应用只能杀自身进程，
     * 因此这里按 Root → Shizuku → 普通 API 的顺序分级执行：
     * - Root/Shizuku：通过 shell `am kill --user 0 <pkg>` 清理，并统计真实结果
     * - 普通 API：仅 Android 13 及以下能对第三方应用生效；Android 14+ 直接返回 UNSUPPORTED
     *
     * @param whitelist 白名单包名（不清理）
     */
    fun cleanBackgroundProcesses(context: Context, whitelist: Set<String>): CleanResult {
        val self = context.packageName
        val foreground = currentForegroundPackages(context)
        val candidates = installedThirdPartyPackages(context)
            .filter { it != self && it !in whitelist && it !in foreground }

        if (candidates.isEmpty()) {
            return CleanResult(0, 0, 0, 0, FailureReason.NO_CANDIDATES)
        }

        // Android 14+ 普通 API 只能杀自己，必须走 Root/Shizuku
        val needShell = Build.VERSION.SDK_INT >= 34

        return when {
            RootShell.granted -> cleanViaShell(candidates, useRoot = true)
            ShizukuShell.isGranted() -> cleanViaShell(candidates, useRoot = false)
            needShell -> CleanResult(
                attempted = candidates.size,
                succeeded = 0,
                skipped = 0,
                failed = candidates.size,
                reason = FailureReason.UNSUPPORTED
            )
            else -> cleanViaLegacyApi(context, candidates)
        }
    }

    /** 通过 Root/Shizuku shell 执行 am kill，逐个统计真实结果 */
    private fun cleanViaShell(candidates: List<String>, useRoot: Boolean): CleanResult {
        val result = CleanResult(candidates.size, 0, 0, 0)
        result.path = if (useRoot) "root" else "shizuku"
        // 拼成一条命令批量执行，减少每次调用的 IPC 开销
        val script = candidates.joinToString("; ") { pkg ->
            "am kill --user 0 $pkg"
        }
        val out = if (useRoot) RootShell.exec(script) else ShizukuShell.exec(script)
        return if (out != null) {
            // am kill 对未运行应用静默成功，无报错输出视为全部成功
            result.copy(succeeded = candidates.size)
        } else {
            // shell 执行失败（超时等），回退到逐个执行以便统计
            var ok = 0
            var fail = 0
            for (pkg in candidates) {
                val single = if (useRoot) {
                    RootShell.exec("am kill --user 0 $pkg")
                } else {
                    ShizukuShell.exec("am kill --user 0 $pkg")
                }
                if (single != null) ok++ else fail++
            }
            result.copy(succeeded = ok, failed = fail)
        }
    }

    /** Android 13 及以下：通过 ActivityManager.killBackgroundProcesses 清理 */
    private fun cleanViaLegacyApi(context: Context, candidates: List<String>): CleanResult {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var ok = 0
        var fail = 0
        for (pkg in candidates) {
            try {
                am.killBackgroundProcesses(pkg)
                ok++
            } catch (_: Exception) {
                fail++
            }
        }
        return CleanResult(
            attempted = candidates.size,
            succeeded = ok,
            skipped = 0,
            failed = fail
        )
    }

    /** 当前处于前台/可见的应用（不清理），优先使用情况访问，其次运行进程判断 */
    private fun currentForegroundPackages(context: Context): Set<String> {
        val result = mutableSetOf<String>()
        if (hasUsageStatsPermission(context)) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val events = usm.queryEvents(now - 60_000L, now)
                val event = UsageEvents.Event()
                var last: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        last = event.packageName
                    }
                }
                if (last != null) result.add(last)
            } catch (_: Exception) {
            }
        }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.forEach { info ->
                if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    result.addAll(info.pkgList)
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /** 所有已安装的第三方（非系统）应用包名 */
    private fun installedThirdPartyPackages(context: Context): List<String> {
        return try {
            val pm = context.packageManager
            pm.getInstalledApplications(
                ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            )
                .filter { app ->
                    app.sourceDir != null &&
                        ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                            (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                }
                .map { it.packageName }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
