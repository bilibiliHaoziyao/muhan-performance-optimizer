package com.muhan.socbatteryinfo.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.muhan.socbatteryinfo.R
import com.muhan.socbatteryinfo.util.BatteryReader
import com.muhan.socbatteryinfo.util.Prefs
import java.util.concurrent.Executors

class BatteryFragment : Fragment() {

    private lateinit var tvCapacity: TextView
    private lateinit var tvLevel: TextView
    private lateinit var tvPower: TextView
    private lateinit var tvBatteryTemp: TextView
    private var switchDualCell: SwitchCompat? = null

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
        return inflater.inflate(R.layout.fragment_battery, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvCapacity = view.findViewById(R.id.tvCapacityValue)
        tvLevel = view.findViewById(R.id.tvLevelValue)
        tvPower = view.findViewById(R.id.tvPowerValue)
        tvBatteryTemp = view.findViewById(R.id.tvBatteryTempValue)
        switchDualCell = view.findViewById(R.id.switchDualCell)
        switchDualCell?.isChecked = Prefs.isDualCell(requireContext())
        switchDualCell?.setOnCheckedChangeListener { _, checked ->
            Prefs.setDualCell(requireContext(), checked)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!dataLoaded) {
            val loading = getString(R.string.loading)
            tvCapacity.text = loading
            tvLevel.text = loading
            tvPower.text = loading
            tvBatteryTemp.text = loading
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
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun refresh() {
        val dualCell = switchDualCell?.isChecked ?: false
        executor.execute {
            val data = BatteryReader.read(requireContext())

            handler.post {
                if (!isAdded) return@post

                dataLoaded = true

                tvCapacity.text = when {
                    data.designCapacityMah > 0 -> String.format("%.0f mAh", data.designCapacityMah)
                    data.remainingMah != null -> String.format("约 %.0f mAh（剩余）", data.remainingMah)
                    else -> getString(R.string.unsupported)
                }

                val percent = data.capacityPercent
                val remaining = data.remainingMah
                val levelText = StringBuilder()
                if (percent != null) levelText.append(percent).append('%')
                if (remaining != null) {
                    if (levelText.isNotEmpty()) levelText.append("（剩余 ")
                    else levelText.append("剩余 ")
                    levelText.append(String.format("%.0f mAh", remaining)).append('）')
                }
                tvLevel.text = if (levelText.isNotEmpty()) levelText.toString() else getString(R.string.unsupported)

                val watts = data.powerWatts(dualCell)
                tvPower.text = if (watts != null) {
                    String.format("%.1f W", watts)
                } else {
                    getString(R.string.unsupported)
                }

                tvBatteryTemp.text = if (data.temperatureC != null) {
                    String.format("%.1f °C", data.temperatureC)
                } else {
                    getString(R.string.unsupported)
                }
            }
        }
    }
}
