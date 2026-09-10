package com.muhan.socbatteryinfo.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * 轻量自绘折线图：展示一维数值历史曲线（如 CPU 使用率 / 电量 / 功率）。
 * 带半透明网格与渐变填充，颜色跟随主题（莫奈取色）。
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var history = FloatArray(0)

    /** 最多保留的数据点数（超出的部分自动丢弃旧数据） */
    var maxPoints: Int = 60
        set(value) {
            field = value.coerceAtLeast(2)
            trimHistory()
            invalidate()
        }

    /** 固定显示上限/下限；为 null 时按数据自动缩放 */
    var maxValue: Float? = null
    var minValue: Float? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val lineColor =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
    private val gridColor =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)

    /** 追加一个新数据点（自动淘汰最旧） */
    fun addValue(v: Float) {
        val next = FloatArray(minOf(history.size + 1, maxPoints))
        val keep = next.size - 1
        if (history.size > keep) {
            System.arraycopy(history, history.size - keep, next, 0, keep)
        } else {
            System.arraycopy(history, 0, next, 0, history.size)
        }
        next[next.size - 1] = v
        history = next
        invalidate()
    }

    /** 整体替换历史数据 */
    fun setHistory(values: List<Float>) {
        history = values.takeLast(maxPoints).toFloatArray()
        invalidate()
    }

    fun clear() {
        history = FloatArray(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val n = history.size
        if (n < 2 || w <= 0f || h <= 0f) return

        var lo = minValue ?: history.minOrNull() ?: 0f
        var hi = maxValue ?: history.maxOrNull() ?: 1f
        if (hi - lo < 1f) hi = lo + 1f

        val pad = dp(10f)
        val graphW = w - pad * 2
        val graphH = h - pad * 2

        // 网格：3 条水平线
        gridPaint.color = Color.argb(
            60,
            Color.red(gridColor),
            Color.green(gridColor),
            Color.blue(gridColor)
        )
        for (f in 0..3) {
            val y = pad + graphH * f / 3f
            canvas.drawLine(pad, y, w - pad, y, gridPaint)
        }

        // 折线路径
        val path = Path()
        for (i in 0 until n) {
            val x = pad + graphW * i / (n - 1)
            val ratio = ((history[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
            val y = pad + graphH * (1f - ratio)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // 渐变填充
        val fillPath = Path(path)
        fillPath.lineTo(w - pad, h - pad)
        fillPath.lineTo(pad, h - pad)
        fillPath.close()
        fillPaint.shader = LinearGradient(
            0f, pad, 0f, h - pad,
            Color.argb(64, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        // 曲线
        linePaint.color = lineColor
        canvas.drawPath(path, linePaint)
    }

    private fun trimHistory() {
        if (history.size > maxPoints) {
            history = history.copyOfRange(history.size - maxPoints, history.size)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
