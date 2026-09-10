package com.muhan.socbatteryinfo.util

import android.os.Build

/**
 * 读取手机 SoC 型号。
 * 优先使用 Android 12 之后的系统字段，否则从常见内核接口 /proc/cpuinfo、
 * /sys/devices/soc0/machine 或 Build.HARDWARE 等回退，最后才使用厂商 + 机型。
 */
object SocInfoReader {

    fun getSocModel(context: android.content.Context): String {
        // Android 12+ 提供专用字段
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manufacturer = Build.SOC_MANUFACTURER
            val model = Build.SOC_MODEL
            if (!model.isNullOrBlank() && model.lowercase() != "unknown") {
                return listOf(manufacturer, model)
                    .filter { !it.isNullOrBlank() && it.lowercase() != "unknown" }
                    .joinToString(" ")
                    .trim()
            }
        }

        // 常见高通/联发科机器名路径
        ThermalReader.readFile("/sys/devices/soc0/machine")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.trim() }

        // /proc/cpuinfo 中 Hardware 字段
        val cpuInfo = ThermalReader.readFile("/proc/cpuinfo") ?: ""
        for (line in cpuInfo.lines()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            if (line.substring(0, idx).trim().equals("Hardware", ignoreCase = true)) {
                val value = line.substring(idx + 1).trim()
                if (value.isNotBlank()) return value
            }
        }

        // 回退到 Build 字段
        val hardware = Build.HARDWARE
        if (!hardware.isNullOrBlank() && hardware.lowercase() != "unknown") {
            return hardware
        }

        val board = Build.BOARD
        if (!board.isNullOrBlank() && board.lowercase() != "unknown") {
            return board
        }

        return "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "未知" }
    }
}