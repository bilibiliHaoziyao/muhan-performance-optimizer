package com.muhan.socbatteryinfo.util

import android.content.Context
import android.content.SharedPreferences

/** 悬浮窗可显示项目的 key */
object FloatingItems {
    const val CPU = "cpu"
    const val CORE = "core"
    const val GPU = "gpu"
    const val SOC_TEMP = "soc_temp"
    const val BATTERY_TEMP = "battery_temp"
    const val POWER = "power"
    const val LEVEL = "level"
    const val REFRESH = "refresh"

    val ALL = listOf(CPU, CORE, GPU, SOC_TEMP, BATTERY_TEMP, POWER, LEVEL, REFRESH)
}

/** 主题模式 */
object ThemeMode {
    const val FOLLOW_SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
}

/** 显示方式：悬浮窗 / 实时通知 / 小米超级岛 / vivo 原子通知 多选一 */
object DisplayMode {
    const val NONE = 0          // 关闭
    const val FLOATING = 1      // 悬浮窗
    const val LIVE_UPDATE = 2   // Android 实时通知
    const val ISLAND = 3        // 小米超级岛
    const val VIVO_ATOMIC = 4   // vivo 原子岛 / 原子通知
}

/** 应用设置存储 */
object Prefs {

    private const val NAME = "soc_battery_prefs"
    private const val KEY_DISPLAY_MODE = "display_mode"
    private const val KEY_DUAL_CELL = "dual_cell"
    private const val KEY_FLOATING_ITEMS = "floating_items"
    private const val KEY_FLOATING_CORE_INDICES = "floating_core_indices"
    private const val KEY_ROOT_GRANTED = "root_granted"
    private const val KEY_THEME = "theme"
    private const val KEY_WELCOME_SHOWN = "welcome_shown"
    private const val KEY_MONET_ENABLED = "monet_enabled"
    private const val KEY_PERM_HINT_SHOWN = "perm_hint_shown"

    // 运存优化
    private const val KEY_AUTO_CLEAN_ENABLED = "auto_clean_enabled"
    private const val KEY_CLEAN_THRESHOLD = "clean_threshold"
    private const val KEY_CLEAN_WHITELIST = "clean_whitelist"
    private const val KEY_CLEAN_STAT_DATE = "clean_stat_date"
    private const val KEY_CLEAN_STAT_COUNT = "clean_stat_count"

    // 旧版本开关（用于迁移到显示方式）
    private const val KEY_NOTIFY_ENABLED = "notify_enabled"
    private const val KEY_FLOATING_ENABLED = "floating_enabled"

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 当前显示方式；未设置时从旧开关迁移（悬浮窗 -> FLOATING，通知 -> LIVE_UPDATE） */
    fun getDisplayMode(context: Context): Int {
        val prefs = sp(context)
        if (!prefs.contains(KEY_DISPLAY_MODE)) {
            return when {
                prefs.getBoolean(KEY_FLOATING_ENABLED, false) -> DisplayMode.FLOATING
                prefs.getBoolean(KEY_NOTIFY_ENABLED, false) -> DisplayMode.LIVE_UPDATE
                else -> DisplayMode.NONE
            }
        }
        return prefs.getInt(KEY_DISPLAY_MODE, DisplayMode.NONE)
    }

    fun setDisplayMode(context: Context, mode: Int) {
        sp(context).edit().putInt(KEY_DISPLAY_MODE, mode).apply()
    }

    fun isDualCell(context: Context): Boolean =
        sp(context).getBoolean(KEY_DUAL_CELL, false)

    fun setDualCell(context: Context, dual: Boolean) {
        sp(context).edit().putBoolean(KEY_DUAL_CELL, dual).apply()
    }

    fun getFloatingItems(context: Context): Set<String> {
        val prefs = sp(context)
        if (!prefs.contains(KEY_FLOATING_ITEMS)) {
            return FloatingItems.ALL.toSet()
        }
        return prefs.getStringSet(KEY_FLOATING_ITEMS, emptySet()) ?: emptySet()
    }

    fun setFloatingItems(context: Context, items: Set<String>) {
        sp(context).edit().putStringSet(KEY_FLOATING_ITEMS, items.toSet()).apply()
    }

    fun getFloatingCoreIndices(context: Context): Set<Int> {
        val prefs = sp(context)
        if (!prefs.contains(KEY_FLOATING_CORE_INDICES)) return emptySet()
        val strSet = prefs.getStringSet(KEY_FLOATING_CORE_INDICES, emptySet()) ?: emptySet()
        return strSet.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun setFloatingCoreIndices(context: Context, indices: Set<Int>) {
        val strSet = indices.map { it.toString() }.toSet()
        sp(context).edit().putStringSet(KEY_FLOATING_CORE_INDICES, strSet).apply()
    }

    fun isRootGranted(context: Context): Boolean =
        sp(context).getBoolean(KEY_ROOT_GRANTED, false)

    fun setRootGranted(context: Context, granted: Boolean) {
        sp(context).edit().putBoolean(KEY_ROOT_GRANTED, granted).apply()
    }

    fun getThemeMode(context: Context): String =
        sp(context).getString(KEY_THEME, ThemeMode.FOLLOW_SYSTEM) ?: ThemeMode.FOLLOW_SYSTEM

    fun setThemeMode(context: Context, mode: String) {
        sp(context).edit().putString(KEY_THEME, mode).apply()
    }

    /** 是否启用莫奈取色（动态取色，仅 Android 12+ 生效），默认开启 */
    fun isMonetEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_MONET_ENABLED, true)

    fun setMonetEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_MONET_ENABLED, enabled).apply()
    }

    /** Root/Shizuku 缺失提示是否已展示过（仅首次进入时提示，避免每次启动打扰） */
    fun isPermHintShown(context: Context): Boolean =
        sp(context).getBoolean(KEY_PERM_HINT_SHOWN, false)

    fun setPermHintShown(context: Context) {
        sp(context).edit().putBoolean(KEY_PERM_HINT_SHOWN, true).apply()
    }

    /** 应用主题（全局生效，需在 Activity onCreate 之前调用） */
    fun applyTheme(context: Context) {
        val nightMode = when (getThemeMode(context)) {
            ThemeMode.LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun isWelcomeShown(context: Context): Boolean =
        sp(context).getBoolean(KEY_WELCOME_SHOWN, false)

    fun setWelcomeShown(context: Context) {
        sp(context).edit().putBoolean(KEY_WELCOME_SHOWN, true).apply()
    }

    // ---------- 运存优化 ----------

    /** 默认白名单：QQ、微信 */
    private val DEFAULT_CLEAN_WHITELIST = setOf("com.tencent.mobileqq", "com.tencent.mm")

    /** 自动清理运存开关（默认开启） */
    fun isAutoCleanEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_CLEAN_ENABLED, true)

    fun setAutoCleanEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_CLEAN_ENABLED, enabled).apply()
    }

    /** 自动清理触发阈值（运存占用百分比，默认 80） */
    fun getCleanThreshold(context: Context): Int =
        sp(context).getInt(KEY_CLEAN_THRESHOLD, 80)

    fun setCleanThreshold(context: Context, threshold: Int) {
        sp(context).edit().putInt(KEY_CLEAN_THRESHOLD, threshold.coerceIn(50, 95)).apply()
    }

    /** 清理白名单（默认包含 QQ、微信） */
    fun getCleanWhitelist(context: Context): Set<String> {
        val prefs = sp(context)
        if (!prefs.contains(KEY_CLEAN_WHITELIST)) return DEFAULT_CLEAN_WHITELIST
        return prefs.getStringSet(KEY_CLEAN_WHITELIST, emptySet()) ?: emptySet()
    }

    fun setCleanWhitelist(context: Context, packages: Set<String>) {
        sp(context).edit().putStringSet(KEY_CLEAN_WHITELIST, packages.toSet()).apply()
    }

    /** 今日清理次数统计（按日期归零） */
    fun getCleanStatCount(context: Context): Int {
        val prefs = sp(context)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        return if (prefs.getString(KEY_CLEAN_STAT_DATE, null) == today) {
            prefs.getInt(KEY_CLEAN_STAT_COUNT, 0)
        } else {
            0
        }
    }

    fun incrementCleanStat(context: Context) {
        val prefs = sp(context)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val count = if (prefs.getString(KEY_CLEAN_STAT_DATE, null) == today) {
            prefs.getInt(KEY_CLEAN_STAT_COUNT, 0)
        } else {
            0
        }
        prefs.edit()
            .putString(KEY_CLEAN_STAT_DATE, today)
            .putInt(KEY_CLEAN_STAT_COUNT, count + 1)
            .apply()
    }
}