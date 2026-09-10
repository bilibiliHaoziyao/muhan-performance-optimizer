package com.muhan.socbatteryinfo.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.muhan.socbatteryinfo.R
import com.muhan.socbatteryinfo.util.SystemInfoReader
import kotlin.concurrent.thread

class SystemFragment : Fragment() {

    private lateinit var tvAndroidVersion: TextView
    private lateinit var tvApiLevel: TextView
    private lateinit var tvOsName: TextView
    private lateinit var tvSystemVersion: TextView
    private lateinit var tvBuildTime: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvSecurityPatch: TextView
    private lateinit var tvKernel: TextView
    private lateinit var tvDeviceModel: TextView
    private lateinit var tvManufacturer: TextView
    private lateinit var tvBootloader: TextView

    private val handler = Handler(Looper.getMainLooper())

    /** 开机时长每 30 秒刷新一次 */
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            updateUptime()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_system, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvAndroidVersion = view.findViewById(R.id.tvAndroidVersionValue)
        tvApiLevel = view.findViewById(R.id.tvApiLevelValue)
        tvOsName = view.findViewById(R.id.tvOsNameValue)
        tvSystemVersion = view.findViewById(R.id.tvSystemVersionValue)
        tvBuildTime = view.findViewById(R.id.tvBuildTimeValue)
        tvUptime = view.findViewById(R.id.tvUptimeValue)
        tvSecurityPatch = view.findViewById(R.id.tvSecurityPatchValue)
        tvKernel = view.findViewById(R.id.tvKernelValue)
        tvDeviceModel = view.findViewById(R.id.tvDeviceModelValue)
        tvManufacturer = view.findViewById(R.id.tvManufacturerValue)
        tvBootloader = view.findViewById(R.id.tvBootloaderValue)

        bindStaticInfo()
        updateUptime()
    }

    override fun onResume() {
        super.onResume()
        handler.post(uptimeRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(uptimeRunnable)
    }

    private fun bindStaticInfo() {
        tvAndroidVersion.text = getString(R.string.sys_android_value, SystemInfoReader.androidVersion)
        tvApiLevel.text = "API ${SystemInfoReader.apiLevel}"
        tvBuildTime.text = SystemInfoReader.buildTime
        tvSecurityPatch.text = SystemInfoReader.securityPatch
        tvKernel.text = SystemInfoReader.kernelVersion

        // 以下信息可能需要 getprop（shell 调用），放后台线程读取
        thread {
            val osName = SystemInfoReader.osName
            val systemVersion = SystemInfoReader.systemVersion
            val deviceModel = SystemInfoReader.deviceModel
            val manufacturer = SystemInfoReader.manufacturer
            val bootloaderUnlocked = SystemInfoReader.isBootloaderUnlocked()
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                tvOsName.text = osName
                tvSystemVersion.text = systemVersion
                tvDeviceModel.text = deviceModel
                tvManufacturer.text = manufacturer
                tvBootloader.text = when (bootloaderUnlocked) {
                    true -> getString(R.string.sys_bootloader_unlocked)
                    false -> getString(R.string.sys_bootloader_locked)
                    null -> getString(R.string.unsupported)
                }
            }
        }
    }

    private fun updateUptime() {
        tvUptime.text = SystemInfoReader.formatUptime(SystemInfoReader.uptimeMillis())
    }
}
