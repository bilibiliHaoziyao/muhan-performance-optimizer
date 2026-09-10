package com.muhan.socbatteryinfo

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.muhan.socbatteryinfo.util.CpuCoreSampler
import com.muhan.socbatteryinfo.util.CpuCoreSnapshot
import com.muhan.socbatteryinfo.util.CpuReader
import com.muhan.socbatteryinfo.view.LineChartView
import java.util.ArrayDeque

class CpuCoreActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private data class CoreItem(
        val valueTv: TextView,
        val bar: ProgressBar,
        val chart: LineChartView,
        val history: ArrayDeque<Float>
    )
    private val coreItems = mutableListOf<CoreItem>()
    private val MAX_POINTS = 60

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_cpu_core)

        val rootLayout = findViewById<LinearLayout>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.cpu_core_title)

        container = findViewById(R.id.llCoreContainer)
        buildCoreItems()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun buildCoreItems() {
        val count = CpuReader.readCoreCount()
        container.removeAllViews()
        coreItems.clear()
        for (i in 0 until count) {
            coreItems.add(createCoreItem(i))
        }
    }

    private fun createCoreItem(index: Int): CoreItem {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = getString(R.string.cpu_core_label, index)
            textSize = 16f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val value = TextView(this).apply {
            textSize = 16f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        headerRow.addView(title)
        headerRow.addView(value)
        item.addView(headerRow)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4f)
            }
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
            )
            progress = 0
        }
        item.addView(bar)

        val chart = LineChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(60f)
            ).apply {
                topMargin = dp(6f)
            }
            maxValue = 100f
            minValue = 0f
        }
        item.addView(chart)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1f)
            ).apply {
                topMargin = dp(12f)
            }
            setBackgroundColor(getColor(R.color.divider))
        }
        item.addView(divider)

        container.addView(item)
        return CoreItem(value, bar, chart, ArrayDeque())
    }

    private fun refresh() {
        val snapshots = CpuCoreSampler.sample()
        if (snapshots.isEmpty()) return

        val count = minOf(snapshots.size, coreItems.size)
        for (i in 0 until count) {
            val item = coreItems[i]
            val snap = snapshots[i]
            item.valueTv.text = formatSnapshot(snap)
            val pct = snap.usagePercent?.toInt() ?: 0
            item.bar.progress = pct.coerceIn(0, 100)
            val usage = snap.usagePercent ?: 0f
            item.history.addLast(usage)
            while (item.history.size > MAX_POINTS) item.history.removeFirst()
            item.chart.setHistory(item.history.toList())
        }
    }

    private fun formatSnapshot(snap: CpuCoreSnapshot): String {
        val usage = snap.usagePercent?.let { String.format("%.0f%%", it) } ?: "--"
        val freq = snap.freqMhz?.let { String.format("%.2f GHz", it / 1000f) } ?: "--"
        return "$usage @ $freq"
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
