package com.muhan.socbatteryinfo.fragment

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.muhan.socbatteryinfo.R
import com.muhan.socbatteryinfo.util.BatteryReader
import com.muhan.socbatteryinfo.util.CpuUsageSampler
import com.muhan.socbatteryinfo.util.NetworkInfo
import com.muhan.socbatteryinfo.util.NetworkReader
import com.muhan.socbatteryinfo.util.Prefs
import com.muhan.socbatteryinfo.util.ScreenReader
import com.muhan.socbatteryinfo.util.SocInfoReader
import com.muhan.socbatteryinfo.util.StorageReader
import com.muhan.socbatteryinfo.util.SystemInfoReader
import com.muhan.socbatteryinfo.util.ThermalReader
import com.muhan.socbatteryinfo.view.LineChartView
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * 概览仪表盘首页：卡片化实时展示电量 / CPU / 内存 / 温度 / 刷新率 / 网络，
 * 以及 CPU、电量、功率实时曲线。
 */
class OverviewFragment : Fragment() {

    private lateinit var tvDeviceTitle: TextView
    private lateinit var tvDeviceSub: TextView

    private lateinit var tvTileBattery: TextView
    private lateinit var tvTileCpu: TextView
    private lateinit var tvTileRam: TextView
    private lateinit var tvTileTemp: TextView
    private lateinit var tvTileRefresh: TextView
    private lateinit var tvTileNetwork: TextView

    private lateinit var chartCpu: LineChartView
    private lateinit var chartBattery: LineChartView
    private lateinit var chartPower: LineChartView

    private lateinit var tvChartCpuValue: TextView
    private lateinit var tvChartBatteryValue: TextView
    private lateinit var tvChartPowerValue: TextView

    private lateinit var tvNetworkDetail: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val cpuHistory = ArrayDeque<Float>()
    private val batteryHistory = ArrayDeque<Float>()
    private val powerHistory = ArrayDeque<Float>()
    private val MAX_POINTS = 60

    @Volatile
    private var refreshInFlight = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!refreshInFlight) {
                refreshInFlight = true
                val ctx = requireContext()
                executor.execute {
                    try {
                        doRefresh(ctx)
                    } finally {
                        refreshInFlight = false
                    }
                }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_overview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvDeviceTitle = view.findViewById(R.id.tvDeviceTitle)
        tvDeviceSub = view.findViewById(R.id.tvDeviceSub)

        tvTileBattery = tileValue(view, R.id.tileBattery, R.string.tile_battery)
        tvTileCpu = tileValue(view, R.id.tileCpu, R.string.tile_cpu)
        tvTileRam = tileValue(view, R.id.tileRam, R.string.tile_ram)
        tvTileTemp = tileValue(view, R.id.tileTemp, R.string.tile_temp)
        tvTileRefresh = tileValue(view, R.id.tileRefresh, R.string.tile_refresh)
        tvTileNetwork = tileValue(view, R.id.tileNetwork, R.string.tile_network)

        chartCpu = view.findViewById(R.id.chartCpu)
        chartBattery = view.findViewById(R.id.chartBattery)
        chartPower = view.findViewById(R.id.chartPower)
        chartCpu.maxValue = 100f
        chartCpu.minValue = 0f
        chartBattery.maxValue = 100f
        chartBattery.minValue = 0f

        tvChartCpuValue = view.findViewById(R.id.tvChartCpuValue)
        tvChartBatteryValue = view.findViewById(R.id.tvChartBatteryValue)
        tvChartPowerValue = view.findViewById(R.id.tvChartPowerValue)

        tvNetworkDetail = view.findViewById(R.id.tvNetworkDetail)

        bindDeviceSummary()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        refresh()
        handler.postDelayed(refreshRunnable, 1000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun tileValue(view: View, tileId: Int, labelRes: Int): TextView {
        val tile = view.findViewById<View>(tileId)
        tile.findViewById<TextView>(R.id.tvTileLabel).text = getString(labelRes)
        return tile.findViewById(R.id.tvTileValue)
    }

    private fun bindDeviceSummary() {
        tvDeviceTitle.text = SystemInfoReader.deviceModel
        tvDeviceSub.text = listOf(
            SystemInfoReader.manufacturer,
            SocInfoReader.getSocModel(requireContext()),
            "Android ${SystemInfoReader.androidVersion}"
        ).joinToString(" · ")
    }

    private fun refresh() {
        val ctx = requireContext()
        executor.execute {
            try {
                doRefresh(ctx)
            } finally {
                refreshInFlight = false
            }
        }
    }

    private fun doRefresh(ctx: Context) {
        val battery = BatteryReader.read(ctx)
        val usage = CpuUsageSampler.sample()
        val ram = StorageReader.readRam()
        val temp = ThermalReader.readSocTemperature()
        val screen = ScreenReader.read(ctx)
        val network = NetworkReader.read(ctx)

        handler.post {
            if (!isAdded) return@post

            // 电量
            tvTileBattery.text = battery.capacityPercent?.let { "$it%" } ?: "--"

            // CPU
            tvTileCpu.text = usage?.let { String.format("%.0f%%", it) } ?: "--"

            // 内存（已用）
            tvTileRam.text = if (ram.totalBytes != null && ram.availableBytes != null) {
                compactBytes(ram.totalBytes - ram.availableBytes)
            } else {
                "--"
            }

            // 温度（优先 SoC，其次电池）
            tvTileTemp.text = (temp ?: battery.temperatureC)
                ?.let { String.format("%.0f°", it) } ?: "--"

            // 刷新率
            tvTileRefresh.text = String.format("%.0fHz", screen.refreshRate)

            // 网络（WiFi 名称 / 制式 / 类型）
            tvTileNetwork.text = when {
                !network.connected -> getString(R.string.network_not_connected)
                network.ssid != null -> network.ssid!!.removeSurrounding("\"")
                network.mobileType != null -> network.mobileType
                else -> network.typeLabel.ifEmpty { getString(R.string.network_unknown) }
            }

            buildNetworkDetail(network)

            // 曲线
            appendHistory(cpuHistory, usage ?: 0f)
            chartCpu.setHistory(cpuHistory.toList())
            tvChartCpuValue.text = usage?.let { String.format("%.0f%%", it) } ?: "--"

            battery.capacityPercent?.let { appendHistory(batteryHistory, it.toFloat()) }
            chartBattery.setHistory(batteryHistory.toList())
            tvChartBatteryValue.text = battery.capacityPercent?.let { "$it%" } ?: "--"

            val powerWatts = battery.powerWatts(Prefs.isDualCell(ctx))
            powerWatts?.let { appendHistory(powerHistory, it.toFloat()) }
            chartPower.setHistory(powerHistory.toList())
            tvChartPowerValue.text = powerWatts?.let { String.format("%.1fW", it) } ?: "--"
        }
    }

    private fun appendHistory(queue: ArrayDeque<Float>, value: Float) {
        queue.addLast(value)
        while (queue.size > MAX_POINTS) queue.removeFirst()
    }

    private fun compactBytes(bytes: Long): String {
        val gb = bytes / 1_073_741_824.0
        return if (gb >= 1.0) String.format("%.1fG", gb) else String.format("%.0fM", bytes / 1_048_576.0)
    }

    private fun buildNetworkDetail(network: NetworkInfo) {
        if (!network.connected) {
            tvNetworkDetail.text = getString(R.string.network_not_connected)
            return
        }
        val lines = mutableListOf<String>()
        lines.add("类型：${network.typeLabel.ifEmpty { getString(R.string.network_unknown) }}")
        network.ssid?.let { lines.add("WiFi：${it.removeSurrounding("\"")}") }
        network.ipv4?.let { lines.add("IP：$it") }
        if (network.rssiDbm != null) {
            lines.add("信号：${network.rssiDbm} dBm（${wifiSignalText(network.rssiDbm)}）")
        }
        network.linkMbps?.let { lines.add("速率：$it Mbps") }
        network.carrier?.let { lines.add("运营商：$it") }
        network.mobileType?.let { lines.add("制式：$it") }
        network.signalLevel?.let { lines.add("信号：$it / 4 格") }
        tvNetworkDetail.text = lines.joinToString("\n")
    }

    private fun wifiSignalText(dbm: Int): String = when {
        dbm >= -50 -> "极强"
        dbm >= -60 -> "强"
        dbm >= -70 -> "一般"
        else -> "弱"
    }
}
