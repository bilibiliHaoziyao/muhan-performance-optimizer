package com.muhan.socbatteryinfo.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** 一次读取得到的电池数据快照 */
data class BatteryData(
    val designCapacityMah: Double,
    val remainingMah: Double?,
    val capacityPercent: Int?,
    val voltageMv: Double?,
    val currentUa: Double?,
    val temperatureC: Float?,
    val isCharging: Boolean
) {
    /**
     * 充电功率（W）。
     * 优先使用电池包电压（双电芯机型需要把电芯电压乘以串数）；
     * 电压读取失败时才回退到「单电芯 ×2」的粗略估算。
     */
    fun powerWatts(dualCell: Boolean): Double? {
        val mv = voltageMv ?: return null
        val ua = currentUa ?: return null
        // 电流符号因机型而异，这里用绝对值，再依据充电状态决定正负：
        // 充电为正，放电（功耗）为负
        val voltageV = Math.abs(mv) / 1000.0
        val currentA = Math.abs(ua) / 1_000_000.0
        // 双电芯串联机型：voltageMv 是单个电芯电压，需要乘 2 才是电池包电压
        val packVoltageV = if (dualCell) voltageV * 2.0 else voltageV
        val magnitude = packVoltageV * currentA
        return if (isCharging) magnitude else -magnitude
    }
}

/** 电池健康度：当前满充容量 vs 设计容量 */
data class BatteryHealth(
    val fullMah: Double,
    val designMah: Double,
    val percent: Int
)

object BatteryReader {

    fun read(context: Context): BatteryData {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        // 电量百分比
        val percentRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val capacityPercent = if (percentRaw != Int.MIN_VALUE) percentRaw else null

        // 剩余电量（µAh -> mAh）
        val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val remainingMah = if (counter != Int.MIN_VALUE) counter / 1000.0 else null

        // 电流（µA，符号表示方向）
        val currentRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentUa = if (currentRaw != Int.MIN_VALUE) currentRaw.toDouble() else null

        // 电池广播（温度、充电状态也依赖它）
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // 电压：优先 sysfs voltage_now（电池包电压，单位 µV），回退到电池广播 EXTRA_VOLTAGE（mV）
        // sysfs 的 voltage_now 通常为电池包整体电压，双电芯机型无需再做倍乘
        val voltageMv = readPackVoltageMv() ?: run {
            val v = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            if (v > 0) v.toDouble() else null
        }

        // 电池温度：EXTRA_TEMPERATURE 单位为 0.1°C，失败则从热区读取
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        var temperatureC: Float? = null
        if (tempTenths != Int.MIN_VALUE) {
            val t = tempTenths / 10f
            if (t > -40f && t < 150f) temperatureC = t
        }
        if (temperatureC == null) {
            temperatureC = ThermalReader.readBatteryTemperature()
        }

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val designMah = readDesignCapacityMah(context)

        return BatteryData(designMah, remainingMah, capacityPercent, voltageMv, currentUa, temperatureC, isCharging)
    }

    fun getBatteryTemperature(context: Context): Float? = read(context).temperatureC

    /** 电池健康度：charge_full（当前满充容量）÷ charge_full_design（设计容量） */
    fun readHealth(): BatteryHealth? {
        val full = ThermalReader.readFile("/sys/class/power_supply/battery/charge_full")?.toDoubleOrNull()
        val design = ThermalReader.readFile("/sys/class/power_supply/battery/charge_full_design")?.toDoubleOrNull()
        if (full == null || design == null || design <= 0 || full <= 0) return null
        val pct = (full / design * 100).toInt().coerceIn(0, 100)
        return BatteryHealth(full / 1000.0, design / 1000.0, pct) // µAh -> mAh
    }

    /** 从 sysfs 读取电池包电压（µV），返回 mV；读取失败返回 null */
    private fun readPackVoltageMv(): Double? {
        val paths = listOf(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/battery/voltage_avg",
            "/sys/class/power_supply/bms/voltage_now"
        )
        for (path in paths) {
            val raw = ThermalReader.readFile(path)?.toDoubleOrNull() ?: continue
            // voltage_now 单位为 µV，转换为 mV；个别 ROM 直接给 mV，做范围修正
            var mv = raw / 1000.0
            if (mv < 1000 || mv > 20000) {
                mv = raw
            }
            if (mv in 1000.0..20000.0) return mv
        }
        return null
    }

    /** 设计容量（mAh）：优先隐藏 API PowerProfile，回退内核 sysfs */
    private fun readDesignCapacityMah(context: Context): Double {
        try {
            val clazz = Class.forName("com.android.internal.os.PowerProfile")
            val instance = clazz.getConstructor(Context::class.java).newInstance(context)
            val method = clazz.getMethod("getBatteryCapacity")
            val value = method.invoke(instance)
            if (value is Number) {
                val mah = value.toDouble()
                if (mah > 0) return mah
            }
        } catch (_: Exception) {
        }

        val raw = ThermalReader.readFile("/sys/class/power_supply/battery/charge_full_design")
        val uah = raw?.toDoubleOrNull()
        if (uah != null && uah > 0) return uah / 1000.0

        return 0.0
    }
}