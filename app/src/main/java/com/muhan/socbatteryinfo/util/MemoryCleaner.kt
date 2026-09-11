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
 */
object MemoryCleaner {

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
     * 清理后台进程，返回执行清理的应用数量。
     * 策略：枚举所有已安装的第三方应用，跳过自身、白名单与当前前台应用，
     * 逐个执行 killBackgroundProcesses（对未运行的应用为无害空操作）。
     * @param whitelist 白名单包名（不清理）
     */
    fun cleanBackgroundProcesses(context: Context, whitelist: Set<String>): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val self = context.packageName
        val foreground = currentForegroundPackages(context)
        val candidates = installedThirdPartyPackages(context)
            .filter { it != self && it !in whitelist && it !in foreground }
        var count = 0
        for (pkg in candidates) {
            try {
                am.killBackgroundProcesses(pkg)
                count++
            } catch (_: Exception) {
            }
        }
        return count
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
    private fun installedThirdPartyPackages(context: Context): Set<String> {
        return try {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                }
                .map { it.packageName }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
