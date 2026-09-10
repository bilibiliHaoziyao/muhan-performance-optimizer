package com.muhan.socbatteryinfo.util

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.pow
import kotlin.math.sqrt

/** 屏幕信息快照 */
data class ScreenInfo(
    val widthPx: Int,
    val heightPx: Int,
    val sizeInch: Float?,
    val refreshRate: Float,
    val densityDpi: Int,
    val widthDp: Float,
    val heightDp: Float
)

/** 屏幕尺寸、分辨率、刷新率读取 */
object ScreenReader {

    fun read(context: Context): ScreenInfo {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)

        val sizeInch = if (dm.xdpi > 0f && dm.ydpi > 0f) {
            sqrt(
                (dm.widthPixels / dm.xdpi).toDouble().pow(2) +
                    (dm.heightPixels / dm.ydpi).toDouble().pow(2)
            ).toFloat()
        } else null

        val refreshRate = when {
            // API 30+ 的 refreshRate 返回当前实际通过动态刷新率技术正在使用的刷新率
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> display.refreshRate
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> display.mode?.refreshRate ?: display.refreshRate
            else -> {
                @Suppress("DEPRECATION")
                display.refreshRate
            }
        }

        val densityDpi = dm.densityDpi
        val widthDp = dm.widthPixels / dm.density
        val heightDp = dm.heightPixels / dm.density

        return ScreenInfo(dm.widthPixels, dm.heightPixels, sizeInch, refreshRate, densityDpi, widthDp, heightDp)
    }
}