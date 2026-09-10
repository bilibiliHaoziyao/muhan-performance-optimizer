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
import com.muhan.socbatteryinfo.util.ScreenReader

class ScreenFragment : Fragment() {

    private lateinit var tvSize: TextView
    private lateinit var tvDensity: TextView
    private lateinit var tvDpResolution: TextView
    private lateinit var tvResolution: TextView
    private lateinit var tvRefresh: TextView

    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_screen, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvSize = view.findViewById(R.id.tvScreenSizeValue)
        tvDensity = view.findViewById(R.id.tvDensityValue)
        tvDpResolution = view.findViewById(R.id.tvDpResolutionValue)
        tvResolution = view.findViewById(R.id.tvResolutionValue)
        tvRefresh = view.findViewById(R.id.tvRefreshValue)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun refresh() {
        val info = ScreenReader.read(requireContext())
        tvSize.text = if (info.sizeInch != null) {
            String.format("%.1f 英寸", info.sizeInch)
        } else {
            getString(R.string.unsupported)
        }
        tvDensity.text = String.format("%d dpi", info.densityDpi)
        tvDpResolution.text = String.format("%.0f × %.0f dp", info.widthDp, info.heightDp)
        tvResolution.text = "${info.widthPx} × ${info.heightPx}"
        tvRefresh.text = String.format("%.0f Hz", info.refreshRate)
    }
}