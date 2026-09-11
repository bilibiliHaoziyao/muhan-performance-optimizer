package com.muhan.socbatteryinfo.util

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * 运存优化工具：
 * - usagePercent() 读取运存占用率（/proc/meminfo）
 * - cleanBackgroundProcesses() 清理后台进程（跳过自身、白名单、前台与系统进程）
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
     * 清理后台进程，返回被清理的应用数量。
     * @param whitelist 白名单包名（不清理）
     */
    fun cleanBackgroundProcesses(context: Context, whitelist: Set<String>): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val self = context.packageName
        val killable = collectKillablePackages(context, am, self, whitelist)
        var count = 0
        for (pkg in killable) {
            try {
                am.killBackgroundProcesses(pkg)
                count++
            } catch (_: Exception) {
            }
        }
        return count
    }

    /** 收集可清理的后台应用包名（去重） */
    private fun collectKillablePackages(
        context: Context,
        am: ActivityManager,
        self: String,
        whitelist: Set<String>
    ): List<String> {
        val result = mutableSetOf<String>()
        val processes = try {
            am.runningAppProcesses ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val foregroundPackages = processes
            .filter { it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND }
            .flatMap { it.pkgList.toList() }
            .toSet()
        // 有「使用情况访问」权限时，排除最近 3 分钟内使用过的应用（避免误杀刚使用过的应用）
        val recentlyUsed = if (hasUsageStatsPermission(context)) {
            recentlyUsedPackages(context, 3 * 60 * 1000L)
        } else {
            emptySet()
        }

        for (info in processes) {
            if (info.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) continue
            for (pkg in info.pkgList) {
                if (pkg in result) continue
                if (pkg == self || pkg in whitelist || pkg in foregroundPackages) continue
                if (pkg in recentlyUsed) continue
                if (isSystemApp(context, pkg)) continue
                result.add(pkg)
            }
        }
        return result.toList()
    }

    /** 查询最近 [windowMs] 毫秒内使用过的应用包名（需「使用情况访问」权限） */
    private fun recentlyUsedPackages(context: Context, windowMs: Long): Set<String> {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - windowMs,
                now
            )
            stats.filter { it.lastTimeUsed >= now - windowMs }
                .map { it.packageName }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun isSystemApp(context: Context, pkg: String): Boolean {
        return try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: Exception) {
            true
        }
    }
}
