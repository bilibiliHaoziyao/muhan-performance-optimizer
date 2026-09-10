package com.muhan.socbatteryinfo.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import com.muhan.socbatteryinfo.R
import com.muhan.socbatteryinfo.util.RamData
import com.muhan.socbatteryinfo.util.StoragePartition
import com.muhan.socbatteryinfo.util.StorageReader
import java.util.concurrent.Executors

class StorageFragment : Fragment() {

    private lateinit var llContent: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val ramRefreshRunnable = object : Runnable {
        override fun run() {
            refreshRam()
            handler.postDelayed(this, 5000L)
        }
    }

    private var hasInflated = false
    private var partitionsLoaded = false
    private var cachedPartitions: List<StoragePartition> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_storage, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        llContent = view.findViewById(R.id.llStorageContent)
        buildSkeleton()
        hasInflated = true
        showLoadingState()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ramRefreshRunnable)
        refreshRam()
        handler.postDelayed(ramRefreshRunnable, 5000L)
        if (!partitionsLoaded) {
            loadPartitions()
        } else {
            applyPartitions(cachedPartitions)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ramRefreshRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        executor.shutdownNow()
    }

    private fun buildSkeleton() {
        val ctx = requireContext()
        addSectionHeader(getString(R.string.storage_ram_title))

        tvRamTotal = addRamRow(R.string.storage_ram_total)
        tvRamUsed = addRamRow(R.string.storage_ram_used)
        tvRamAvailable = addRamRow(R.string.storage_ram_available)
        tvSwapTotal = addRamRow(R.string.storage_swap_total)
        tvSwapUsed = addRamRow(R.string.storage_swap_used)
        tvSwapAvailable = addRamRow(R.string.storage_swap_available)

        tvSourceHint = addHintView()

        addDivider()

        addSectionHeader(getString(R.string.storage_partitions_title))
        tvPartitionsLoading = addHintView()
        tvPartitionsEmpty = addHintView()
        tvPartitionsHint = addHintView()
        llPartitionsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        llContent.addView(llPartitionsContainer)
    }

    private var tvRamTotal: TextView? = null
    private var tvRamUsed: TextView? = null
    private var tvRamAvailable: TextView? = null
    private var tvSwapTotal: TextView? = null
    private var tvSwapUsed: TextView? = null
    private var tvSwapAvailable: TextView? = null
    private var tvSourceHint: TextView? = null
    private var tvPartitionsLoading: TextView? = null
    private var tvPartitionsEmpty: TextView? = null
    private var tvPartitionsHint: TextView? = null
    private var llPartitionsContainer: LinearLayout? = null

    private data class PartitionViews(
        val item: LinearLayout,
        val mountTv: TextView,
        val detailTv: TextView,
        val bar: ProgressBar,
        val availTv: TextView
    )

    private val partitionViews = mutableListOf<PartitionViews>()

    private fun showLoadingState() {
        val loadingText = getString(R.string.loading)
        tvRamTotal?.text = loadingText
        tvRamUsed?.text = loadingText
        tvRamAvailable?.text = loadingText
        tvSwapTotal?.text = loadingText
        tvSwapUsed?.text = loadingText
        tvSwapAvailable?.text = loadingText
        tvPartitionsLoading?.text = getString(R.string.storage_partitions_loading)
        tvPartitionsLoading?.visibility = View.VISIBLE
        tvPartitionsEmpty?.visibility = View.GONE
        tvPartitionsHint?.visibility = View.GONE
        for (pv in partitionViews) pv.item.visibility = View.GONE
    }

    private fun refreshRam() {
        if (!hasInflated) return

        executor.execute {
            val ram = StorageReader.readRam()

            handler.post {
                if (!isAdded) return@post
                applyRam(ram)
            }
        }
    }

    private fun applyRam(ram: RamData) {
        tvRamTotal?.text = StorageReader.formatBytes(ram.totalBytes)
        val used = ram.totalBytes?.let { total ->
            val avail = ram.availableBytes ?: 0L
            total - avail
        }
        tvRamUsed?.text = StorageReader.formatBytes(used)
        tvRamAvailable?.text = StorageReader.formatBytes(ram.availableBytes)
        val swapUsed = ram.swapTotalBytes?.let { total ->
            val free = ram.swapFreeBytes ?: 0L
            total - free
        }
        tvSwapTotal?.text = StorageReader.formatBytes(ram.swapTotalBytes)
        tvSwapUsed?.text = StorageReader.formatBytes(swapUsed)
        tvSwapAvailable?.text = StorageReader.formatBytes(ram.swapFreeBytes)

        tvSourceHint?.let {
            if (ram.viaRoot || ram.viaShizuku) {
                val src = when {
                    ram.viaRoot -> getString(R.string.via_root)
                    else -> getString(R.string.via_shizuku)
                }
                it.text = getString(R.string.storage_source_hint, src)
                it.visibility = View.VISIBLE
            } else {
                it.visibility = View.GONE
            }
        }
    }

    private fun loadPartitions() {
        if (!hasInflated) return

        executor.execute {
            val partitions = StorageReader.readPartitions()
            cachedPartitions = partitions
            partitionsLoaded = true

            handler.post {
                if (!isAdded) return@post
                applyPartitions(partitions)
            }
        }
    }

    private fun applyPartitions(partitions: List<StoragePartition>) {
        tvPartitionsLoading?.visibility = View.GONE
        val container = llPartitionsContainer ?: return

        if (partitions.isEmpty()) {
            tvPartitionsEmpty?.text = getString(R.string.storage_partitions_empty)
            tvPartitionsEmpty?.visibility = View.VISIBLE
            tvPartitionsHint?.visibility = View.GONE
            for (pv in partitionViews) pv.item.visibility = View.GONE
            return
        }

        tvPartitionsEmpty?.visibility = View.GONE
        if (partitions.any { it.viaRoot || it.viaShizuku }) {
            tvPartitionsHint?.text = getString(R.string.storage_partitions_partial)
            tvPartitionsHint?.visibility = View.VISIBLE
        } else {
            tvPartitionsHint?.visibility = View.GONE
        }

        ensurePartitionCount(partitions.size)
        for ((i, p) in partitions.withIndex()) {
            updatePartitionRow(partitionViews[i], p)
        }
    }

    private fun ensurePartitionCount(needed: Int) {
        val container = llPartitionsContainer ?: return
        while (partitionViews.size < needed) {
            val pv = createPartitionRow()
            partitionViews.add(pv)
            container.addView(pv.item)
        }
        for (i in 0 until partitionViews.size) {
            partitionViews[i].item.visibility = if (i < needed) View.VISIBLE else View.GONE
        }
    }

    private fun createPartitionRow(): PartitionViews {
        val ctx = requireContext()
        val item = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12f), 0, dp(8f))
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val mountTv = TextView(ctx).apply {
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val detailTv = TextView(ctx).apply {
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 13f
            gravity = Gravity.END
        }

        headerRow.addView(mountTv)
        headerRow.addView(detailTv)
        item.addView(headerRow)

        val bar = ProgressBar(
            ctx, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) }
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
            )
        }
        item.addView(bar)

        val availTv = TextView(ctx).apply {
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
            textSize = 12f
            setPadding(0, dp(2f), 0, 0)
        }
        item.addView(availTv)

        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1f)
            ).apply { topMargin = dp(8f) }
            setBackgroundColor(getColor(R.color.divider))
        }
        item.addView(divider)

        return PartitionViews(item, mountTv, detailTv, bar, availTv)
    }

    private fun updatePartitionRow(pv: PartitionViews, p: StoragePartition) {
        val displayName = when (p.mountPoint) {
            "/data" -> getString(R.string.storage_internal)
            "/mnt/expand" -> getString(R.string.storage_sd_card)
            else -> p.mountPoint
        }
        pv.mountTv.text = displayName

        val usedStr = StorageReader.formatBytes(p.usedBytes)
        val totalStr = StorageReader.formatBytes(p.totalBytes)
        pv.detailTv.text = "$usedStr / $totalStr"

        val pct = if (p.totalBytes > 0) {
            (p.usedBytes.toFloat() / p.totalBytes * 100).toInt().coerceIn(0, 100)
        } else 0
        if (pv.bar.progress != pct) {
            pv.bar.progress = pct
        }

        pv.availTv.text = getString(
            R.string.storage_available_format,
            StorageReader.formatBytes(p.availableBytes),
            StorageReader.formatPercent(p.usedBytes, p.totalBytes)
        )
    }

    private fun addSectionHeader(text: String) {
        val tv = TextView(requireContext()).apply {
            this.text = text
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 14f
            setPadding(0, dp(20f), 0, dp(4f))
        }
        llContent.addView(tv)
    }

    private fun addRamRow(labelRes: Int): TextView {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val label = TextView(ctx).apply {
            setText(labelRes)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueTv = TextView(ctx).apply {
            text = "--"
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            textSize = 16f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(label)
        row.addView(valueTv)
        llContent.addView(row)
        return valueTv
    }

    private fun addDivider() {
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1f)
            ).apply {
                topMargin = dp(8f)
                bottomMargin = dp(8f)
            }
            setBackgroundColor(getColor(R.color.divider))
        }
        llContent.addView(divider)
    }

    private fun addHintView(): TextView {
        val tv = TextView(requireContext()).apply {
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
            textSize = 12f
            visibility = View.GONE
            setPadding(0, dp(4f), 0, 0)
        }
        llContent.addView(tv)
        return tv
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun getColor(resId: Int): Int {
        return requireContext().getColor(resId)
    }
}
