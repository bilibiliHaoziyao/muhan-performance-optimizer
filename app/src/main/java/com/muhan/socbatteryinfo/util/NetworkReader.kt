package com.muhan.socbatteryinfo.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import java.net.Inet4Address

/** 网络状态快照 */
data class NetworkInfo(
    val connected: Boolean,
    val typeLabel: String,
    val ssid: String?,
    val ipv4: String?,
    val rssiDbm: Int?,
    val linkMbps: Int?,
    val carrier: String?,
    val mobileType: String?,
    val signalLevel: Int?
)

/** 网络状态读取：当前连接类型、WiFi 名称/信号、IP、运营商与移动网络制式 */
object NetworkReader {

    fun read(context: Context): NetworkInfo {
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val props = active?.let { cm.getLinkProperties(it) }

        if (caps == null) {
            return NetworkInfo(false, "", null, null, null, null, null, null, null)
        }

        var typeLabel = ""
        var ssid: String? = null
        var rssi: Int? = null
        var linkMbps: Int? = null
        var carrier: String? = null
        var mobileType: String? = null
        var signalLevel: Int? = null

        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> typeLabel = "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> typeLabel = "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> typeLabel = "以太网"
            else -> typeLabel = "网络"
        }

        if (typeLabel == "WiFi") {
            // SSID 在 Android 12+ 可能需要位置权限，读不到时优雅降级为“未知”
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            try {
                val info = wm?.connectionInfo
                if (info != null && info.networkId != -1) {
                    val rawSsid = info.ssid?.trim()
                    ssid = rawSsid?.takeIf {
                        it.isNotBlank() && it != "<unknown ssid>" && it != "unknown ssid" && it != "0x"
                    }
                    if (info.rssi != Int.MAX_VALUE) rssi = info.rssi
                    if (info.linkSpeed > 0) linkMbps = info.linkSpeed
                }
            } catch (_: Exception) {
            }
        } else if (typeLabel == "移动数据") {
            val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            try {
                carrier = tm?.networkOperatorName?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
            }
            try {
                mobileType = tm?.let { mobileTypeLabel(it.dataNetworkType) }
            } catch (_: Exception) {
            }
            try {
                if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    signalLevel = tm.signalStrength?.level?.takeIf { it in 0..4 }
                }
            } catch (_: Exception) {
            }
        }

        val ipv4 = props?.linkAddresses
            ?.mapNotNull { it.address }
            ?.firstOrNull { it is Inet4Address }
            ?.hostAddress

        return NetworkInfo(true, typeLabel, ssid, ipv4, rssi, linkMbps, carrier, mobileType, signalLevel)
    }

    private fun mobileTypeLabel(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "3G H+"
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "未知"
        else -> "移动网络"
    }
}
