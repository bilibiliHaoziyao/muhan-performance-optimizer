package com.muhan.socbatteryinfo.util

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 系统信息读取 */
object SystemInfoReader {

    /** Android 版本名，如 Android 15 */
    val androidVersion: String
        get() = Build.VERSION.RELEASE ?: "未知"

    /** API 版本号，如 API 35 */
    val apiLevel: Int
        get() = Build.VERSION.SDK_INT

    /** 系统版本名（厂商定制），如 HyperOS 2.0、MIUI 14、ColorOS 14 等 */
    val systemVersion: String
        get() {
            val names = listOf(
                Build.VERSION.INCREMENTAL,
                roProperty("ro.build.version.incremental"),
                Build.DISPLAY
            )
            return names.firstOrNull { !it.isNullOrBlank() } ?: "未知"
        }

    /** 厂商定制系统标识，如 Xiaomi HyperOS / vivo OriginOS / OPPO ColorOS */
    val osName: String
        get() {
            val props = listOf(
                "ro.mi.os.version.name" to "HyperOS",
                "ro.miui.ui.version.name" to "MIUI",
                "ro.vivo.os.version" to "OriginOS",
                "ro.build.version.opporom" to "ColorOS",
                "ro.build.version.magic" to "MagicOS",
                "ro.build.version.emui" to "EMUI",
                "ro.lenovo.os.version.name" to "ZUI"
            )
            val brand = (Build.MANUFACTURER ?: "").trim()
            for ((prop, label) in props) {
                val ver = SystemPropertiesProxy.get(prop) ?: ShellExec.exec("getprop $prop")
                if (!ver.isNullOrBlank()) {
                    return "$label $ver"
                }
            }
            // 无定制系统标识时回退品牌名
            return if (brand.isNotBlank()) "${brand} Android" else "Android"
        }

    /** 系统构建时间，来自 Build.TIME */
    val buildTime: String
        get() = try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            fmt.format(Date(Build.TIME))
        } catch (_: Exception) {
            "未知"
        }

    /** 构建指纹 */
    val buildFingerprint: String
        get() = Build.FINGERPRINT ?: "未知"

    /** 内核版本，如 5.15.104-android13-4-... */
    val kernelVersion: String
        get() = System.getProperty("os.version") ?: "未知"

    /** 已开机时长（毫秒） */
    fun uptimeMillis(): Long {
        // 优先读取 /proc/uptime（秒，含小数）
        ThermalReader.readFile("/proc/uptime")?.let { content ->
            content.trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()?.let { sec ->
                return (sec * 1000).toLong()
            }
        }
        // 兜底：SystemClock.elapsedRealtime 包含深度睡眠时间
        return android.os.SystemClock.elapsedRealtime()
    }

    /** 格式化开机时长，如 "3 天 4 小时 15 分钟" */
    fun formatUptime(millis: Long): String {
        val totalMinutes = millis / 60000
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes % (60 * 24)) / 60
        val minutes = totalMinutes % 60
        return buildString {
            if (days > 0) append("${days} 天 ")
            if (hours > 0) append("${hours} 小时 ")
            append("${minutes} 分钟")
        }
    }

    /** 设备型号（市场名，如 "小米 15"），读取失败则回退 Build.MODEL */
    val deviceModel: String
        get() {
            val marketName = SystemPropertiesProxy.get("ro.product.marketname")
                ?: roProperty("ro.product.marketname")
            return marketName?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "未知"
        }

    /** 处理器架构，如 arm64-v8a */
    val abi: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS?.firstOrNull() ?: Build.CPU_ABI ?: "未知"
        } else {
            @Suppress("DEPRECATION")
            Build.CPU_ABI ?: "未知"
        }

    /** 安全补丁级别，如 2025-01-05 */
    val securityPatch: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH ?: "未知"
        } else {
            "未知"
        }

    /** 厂商名 */
    val manufacturer: String
        get() = Build.BRAND?.let { it.replaceFirstChar { c -> c.uppercase() } } ?: "未知"

    /** bootloader 是否解锁（尽力判断，读取不到返回 null） */
    fun isBootloaderUnlocked(): Boolean? {
        val value = SystemPropertiesProxy.get("ro.boot.verifiedbootstate")
            ?: SystemPropertiesProxy.get("ro.boot.flash.locked")
            ?: roProperty("ro.boot.verifiedbootstate")
            ?: roProperty("ro.boot.flash.locked")
        return when (value?.trim()) {
            "orange" -> true
            "green", "yellow" -> false
            "0" -> true
            "1" -> false
            else -> null
        }
    }

    /** 读取 system property（通过 getprop，避免反射限制） */
    private fun roProperty(name: String): String? =
        ShellExec.exec("getprop $name")?.takeIf { it.isNotBlank() && it != "\n" }
}

/** SystemProperties 反射代理（仅限应用可读属性） */
private object SystemPropertiesProxy {
    private val getMethod by lazy {
        try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun get(key: String): String? = try {
        getMethod?.invoke(null, key) as? String
    } catch (_: Exception) {
        null
    }
}
