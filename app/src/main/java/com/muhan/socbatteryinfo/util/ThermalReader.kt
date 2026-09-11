package com.muhan.socbatteryinfo.util

import java.io.File

/**
 * 从 /sys/class/thermal 读取温度。
 * 内核约定 temp 单位为毫氏度（millidegree Celsius），个别设备直接返回氏度，故做一次范围修正。
 */
object ThermalReader {

    fun readFile(path: String): String? {
        return try {
            // 直接尝试读取，不用 canRead() 预检：
            // 部分国产 ROM 的 SELinux 允许 open/read 却拦截 access()，导致 canRead() 误返回 false
            File(path).readText().trim()
        } catch (_: Exception) {
            null
        }
    }

    /** SoC 各热区温度详情 */
    data class SocTempDetail(
        /** 最热热区温度（用于概览展示） */
        val maxTemp: Float,
        /** 各热区名称到温度的映射（type -> 温度） */
        val zones: Map<String, Float>
    )

    /**
     * SoC 温度详情：返回最热热区温度及各热区明细。
     * 避免把 GPU/NPU/DDR 等周边热区直接当作"SoC 芯片温度"展示。
     */
    fun readSocTemperatureDetail(): SocTempDetail? {
        val zones = mutableMapOf<String, Float>()
        for (zone in thermalZones()) {
            val type = readFile(zone.path + "/type")?.lowercase() ?: continue
            if (!isSocType(type)) continue
            readZoneTemp(zone.path)?.let { zones[type] = it }
        }
        if (zones.isEmpty()) return null
        val maxTemp = zones.values.maxOrNull() ?: return null
        return SocTempDetail(maxTemp, zones)
    }

    /** SoC 温度：取匹配 SoC 相关热区的最高温度（最热核心） */
    fun readSocTemperature(): Float? {
        return readSocTemperatureDetail()?.maxTemp
    }

    /** 电池温度：优先电池/充电相关热区 */
    fun readBatteryTemperature(): Float? {
        for (zone in thermalZones()) {
            val type = readFile(zone.path + "/type")?.lowercase() ?: continue
            if (type.contains("battery") || type.contains("batt") ||
                type.contains("charger") || type.contains("bms")
            ) {
                readZoneTemp(zone.path)?.let { return it }
            }
        }
        return null
    }

    private fun thermalZones(): List<File> {
        val base = File("/sys/class/thermal")
        if (!base.isDirectory) return emptyList()
        return base.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
            ?.toList() ?: emptyList()
    }

    private fun readZoneTemp(path: String): Float? {
        val raw = readFile("$path/temp")?.toFloatOrNull() ?: return null
        var degrees = raw / 1000f
        if (degrees < -40f || degrees > 150f) {
            degrees = raw
        }
        return if (degrees in -40f..150f) degrees else null
    }

    private fun isSocType(type: String): Boolean {
        if (type.isEmpty()) return false
        val include = arrayOf("soc", "cpu", "tsens", "ap", "cluster", "cpuss", "aoss", "ddr", "gpu", "npu", "quiet")
        val exclude = arrayOf(
            "battery", "batt", "charger", "skin", "wifi", "wlan",
            "modem", "bluetooth", "display", "camera", "flash", "mmw"
        )
        val hasInclude = include.any { type.contains(it) }
        val hasExclude = exclude.any { type.contains(it) }
        return hasInclude && !hasExclude
    }
}