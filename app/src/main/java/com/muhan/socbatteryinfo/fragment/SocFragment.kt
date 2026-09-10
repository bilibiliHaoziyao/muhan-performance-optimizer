package com.muhan.socbatteryinfo.fragment

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.muhan.socbatteryinfo.CpuCoreActivity
import com.muhan.socbatteryinfo.R
import com.muhan.socbatteryinfo.util.BatteryReader
import com.muhan.socbatteryinfo.util.CpuReader
import com.muhan.socbatteryinfo.util.CpuUsageSampler
import com.muhan.socbatteryinfo.util.GpuReader
import com.muhan.socbatteryinfo.util.RootShell
import com.muhan.socbatteryinfo.util.SocInfoReader
import com.muhan.socbatteryinfo.util.ThermalReader
import java.util.concurrent.Executors

class SocFragment : Fragment() {

    private lateinit var tvSocModel: TextView
    private lateinit var tvSocTemp: TextView
    private lateinit var tvCpuCore: TextView
    private lateinit var tvCpuAbi: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvGpu: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var dataLoaded = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_soc, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvSocModel = view.findViewById(R.id.tvSocModelValue)
        tvSocTemp = view.findViewById(R.id.tvSocTempValue)
        tvCpuCore = view.findViewById(R.id.tvCpuCoreValue)
        tvCpuAbi = view.findViewById(R.id.tvCpuAbiValue)
        tvCpu = view.findViewById(R.id.tvCpuValue)
        tvGpu = view.findViewById(R.id.tvGpuValue)

        tvCpu.setOnClickListener {
            startActivity(Intent(requireContext(), CpuCoreActivity::class.java))
        }

        tvSocModel.text = SocInfoReader.getSocModel(requireContext())
        tvCpuCore.text = String.format("%d 核", CpuReader.readCoreCount())
        tvCpuAbi.text = CpuReader.readAbi()
    }

    override fun onResume() {
        super.onResume()
        if (!dataLoaded) {
            val loading = getString(R.string.loading)
            tvSocTemp.text = loading
            tvCpu.text = loading
            tvGpu.text = loading
        }
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
        executor.shutdownNow()
    }

    private fun refresh() {
        executor.execute {
            val socTemp = ThermalReader.readSocTemperature()
            val usage = CpuUsageSampler.sample()
            val freqMhz = CpuReader.readFreqMhz()
            val gpu = GpuReader.read()

            handler.post {
                if (!isAdded) return@post

                dataLoaded = true

                tvSocTemp.text = when {
                    socTemp != null -> String.format("%.1f °C", socTemp)
                    else -> {
                        val batteryTemp = BatteryReader.getBatteryTemperature(requireContext())
                        if (batteryTemp != null) {
                            String.format("%.1f °C（估算）", batteryTemp + 3f)
                        } else {
                            getString(R.string.unsupported)
                        }
                    }
                }

                tvCpu.text = buildString {
                    append(if (usage != null) String.format("%.0f%%", usage) else "--")
                    append(" @ ")
                    append(if (freqMhz != null) String.format("%.2f GHz", freqMhz / 1000f) else "--")
                }

                tvGpu.text = if (gpu.busyPercent == null && gpu.freqMhz == null) {
                    if (RootShell.granted) getString(R.string.unsupported) else getString(R.string.needs_root)
                } else {
                    buildString {
                        if (gpu.busyPercent != null) append(String.format("%.0f%%", gpu.busyPercent))
                        if (gpu.busyPercent != null && gpu.freqMhz != null) append(" @ ")
                        if (gpu.freqMhz != null) append(String.format("%d MHz", gpu.freqMhz))
                    }
                }
            }
        }
    }
}
