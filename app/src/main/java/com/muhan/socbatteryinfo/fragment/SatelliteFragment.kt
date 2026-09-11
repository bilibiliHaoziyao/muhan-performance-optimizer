package com.muhan.socbatteryinfo.fragment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.muhan.socbatteryinfo.R
import com.muhan.socbatteryinfo.util.ShellExec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 卫星 Tab：展示手机连接的卫星（按北斗 / GPS / GLONASS / Galileo 等统计颗数）、
 * 纬度 / 经度 / 高度 / 海平面高度（NMEA 解析）/ 速度（km/h），以及卫星通信支持情况。
 */
class SatelliteFragment : Fragment() {

    private lateinit var tvFixStatus: TextView
    private lateinit var tvLocationTime: TextView
    private lateinit var tvGpsHint: TextView
    private lateinit var btnRequestLocation: Button
    private lateinit var tvSatList: TextView
    private lateinit var tvSatTotal: TextView
    private lateinit var tvLatitude: TextView
    private lateinit var tvLongitude: TextView
    private lateinit var tvAltitude: TextView
    private lateinit var tvMslAltitude: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvSatComm: TextView

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var gnssCallback: GnssStatus.Callback? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var locationListener: LocationListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_satellite, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        locationManager =
            requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager

        tvFixStatus = view.findViewById(R.id.tvFixStatus)
        tvLocationTime = view.findViewById(R.id.tvLocationTime)
        tvGpsHint = view.findViewById(R.id.tvGpsHint)
        btnRequestLocation = view.findViewById(R.id.btnRequestLocation)
        tvSatList = view.findViewById(R.id.tvSatList)
        tvSatTotal = view.findViewById(R.id.tvSatTotal)
        tvLatitude = view.findViewById(R.id.tvLatitude)
        tvLongitude = view.findViewById(R.id.tvLongitude)
        tvAltitude = view.findViewById(R.id.tvAltitude)
        tvMslAltitude = view.findViewById(R.id.tvMslAltitude)
        tvSpeed = view.findViewById(R.id.tvSpeed)
        tvSatComm = view.findViewById(R.id.tvSatComm)

        updateSatelliteComm()

        btnRequestLocation.setOnClickListener { requestLocationPermission() }

        if (!hasLocationPermission()) {
            tvFixStatus.text = getString(R.string.satellite_permission_denied)
            tvFixStatus.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.error)
            )
            btnRequestLocation.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            startMonitoring()
        }
    }

    override fun onPause() {
        super.onPause()
        stopMonitoring()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                tvFixStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.on_background)
                )
                btnRequestLocation.visibility = View.GONE
                startMonitoring()
            }
        }
    }

    // ---------- 权限 ----------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
    }

    // ---------- 监听 ----------

    private fun startMonitoring() {
        stopMonitoring()

        // 卫星列表（API 24+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssCallback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    updateSatellites(status)
                }
            }
            try {
                locationManager.registerGnssStatusCallback(gnssCallback!!, handler)
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }

        // NMEA：解析 GGA 获取海平面高度（API 24+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            nmeaListener = OnNmeaMessageListener { message, _ ->
                parseNmea(message)
            }
            try {
                locationManager.addNmeaListener(nmeaListener!!, handler)
            } catch (_: Exception) {
            }
        }

        // 位置更新：纬度 / 经度 / 高度 / 速度
        locationListener = LocationListener { location ->
            onLocation(location)
        }
        try {
            val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                tvGpsHint.visibility = View.VISIBLE
                LocationManager.PASSIVE_PROVIDER
            }
            locationManager.requestLocationUpdates(provider, 1000L, 0f, locationListener!!, handler.looper)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun stopMonitoring() {
        gnssCallback?.let {
            try {
                locationManager.unregisterGnssStatusCallback(it)
            } catch (_: Exception) {
            }
            gnssCallback = null
        }
        nmeaListener?.let {
            try {
                locationManager.removeNmeaListener(it)
            } catch (_: Exception) {
            }
            nmeaListener = null
        }
        locationListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (_: Exception) {
            }
            locationListener = null
        }
    }

    // ---------- 数据更新 ----------

    private fun updateSatellites(status: GnssStatus) {
        val usedByConstellation = mutableMapOf<Int, Int>()
        var usedTotal = 0
        var visibleTotal = 0
        for (i in 0 until status.satelliteCount) {
            if (status.getCn0DbHz(i) > 0f) visibleTotal++
            if (status.usedInFix(i)) {
                usedTotal++
                val c = status.getConstellationType(i)
                usedByConstellation[c] = (usedByConstellation[c] ?: 0) + 1
            }
        }

        val lines = constellationOrder.mapNotNull { c ->
            val n = usedByConstellation[c] ?: 0
            if (n > 0) "${constellationName(c)} $n 颗" else null
        }.toMutableList()
        val unknownCount = usedByConstellation.filterKeys { it !in constellationOrder }.values.sum()
        if (unknownCount > 0) lines.add("其他 $unknownCount 颗")

        tvSatList.text = if (lines.isEmpty()) {
            getString(R.string.satellite_list_empty)
        } else {
            lines.joinToString("\n")
        }
        tvSatTotal.text = getString(R.string.satellite_total, usedTotal, visibleTotal)
    }

    private fun onLocation(location: Location) {
        tvFixStatus.text = getString(R.string.satellite_fix_located)
        tvGpsHint.visibility = View.GONE
        tvLocationTime.text = getString(
            R.string.satellite_update_time,
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        )
        tvLatitude.text = String.format("%.6f°", location.latitude)
        tvLongitude.text = String.format("%.6f°", location.longitude)
        if (location.hasAltitude()) {
            tvAltitude.text = String.format("%.1f m", location.altitude)
        }
        if (location.hasSpeed()) {
            tvSpeed.text = String.format("%.1f km/h", location.speed * 3.6)
        }
    }

    /** 解析 NMEA GGA 句：$xxGGA,...,高度(M),M,大地水准面差距(M),M,... 取第 10 字段为海平面高度 */
    private fun parseNmea(message: String) {
        // 真实 NMEA 句以校验和 *hh 结尾，GGA 是句首标识（如 $GPGGA 或 $BDGGA）
        if (!message.startsWith("$")) return
        if (message.split(",").firstOrNull()?.endsWith("GGA") != true) return
        val parts = message.split(",")
        if (parts.size <= 12) return
        val fixQuality = parts.getOrNull(6)?.toIntOrNull() ?: 0
        val msl = parts.getOrNull(9)?.toDoubleOrNull()
        if (fixQuality > 0 && msl != null) {
            tvMslAltitude.text = String.format("%.1f m", msl)
        }
    }

    private fun updateSatelliteComm() {
        executor.execute {
            val ctx = requireContext()
            val text = detectSatelliteComm(ctx)
            handler.post {
                if (isAdded) tvSatComm.text = text
            }
        }
    }

    /** 卫星通信能力无标准 Android API，通过系统属性 / sysfs 尽力探测，探测不到显示未知 */
    private fun detectSatelliteComm(ctx: Context): String {
        val props = listOf(
            "ro.vendor.satellite.support",
            "ro.vendor.satellite.mode",
            "ro.hardware.satellite",
            "persist.vendor.satellite.support"
        )
        for (p in props) {
            val v = readProp(p)?.trim()
            if (!v.isNullOrBlank() && v.lowercase() != "false" && v != "0") {
                return ctx.getString(R.string.satellite_comm_supported)
            }
        }
        for (path in listOf(
            "/sys/class/satellite",
            "/sys/bus/satellite",
            "/sys/class/modem/satellite"
        )) {
            if (File(path).exists()) {
                return ctx.getString(R.string.satellite_comm_supported)
            }
        }
        return ctx.getString(R.string.satellite_comm_unknown)
    }

    private fun readProp(name: String): String? = ShellExec.execSh("getprop $name")

    private val constellationOrder = listOf(
        GnssStatus.CONSTELLATION_BEIDOU,
        GnssStatus.CONSTELLATION_GPS,
        GnssStatus.CONSTELLATION_GLONASS,
        GnssStatus.CONSTELLATION_GALILEO,
        GnssStatus.CONSTELLATION_QZSS,
        GnssStatus.CONSTELLATION_IRNSS,
        GnssStatus.CONSTELLATION_SBAS
    )

    private fun constellationName(constellation: Int): String = when (constellation) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_BEIDOU -> "北斗"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
        else -> "未知"
    }

    companion object {
        private const val REQ_LOCATION = 3001
    }
}
