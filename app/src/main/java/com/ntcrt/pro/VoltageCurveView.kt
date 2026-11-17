package com.ntcrt.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs

/**
 * NTC 串联分压 Vout(T) 输出电压响应曲线画布
 */
class VoltageCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<DividerPoint> = emptyList()
    private var vcc: Double = 3.3
    private var selectedIndex: Int = -1
    var onPointSelected: ((DividerPoint?) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0F1A")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A2438")
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = sp(10f)
        typeface = Typeface.MONOSPACE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val halfVccPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44F59E0B")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA10B981")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.FILL
    }

    private val plotRect = RectF()

    fun setDividerData(data: List<DividerPoint>, vccVolt: Double) {
        points = data
        vcc = vccVolt.coerceAtLeast(0.1)
        selectedIndex = if (data.isNotEmpty()) {
            data.indices.minByOrNull { abs(data[it].tempC - 25.0) } ?: 0
        } else -1
        onPointSelected?.invoke(points.getOrNull(selectedIndex))
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty() || plotRect.width() <= 0f) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val tMin = points.first().tempC
                val tMax = points.last().tempC
                val frac = ((event.x - plotRect.left) / plotRect.width()).coerceIn(0f, 1f)
                val targetT = tMin + frac * (tMax - tMin)
                val nearest = points.indices.minByOrNull { abs(points[it].tempC - targetT) } ?: return true
                if (nearest != selectedIndex) {
                    selectedIndex = nearest
                    onPointSelected?.invoke(points[nearest])
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, dp(10f), dp(10f), bgPaint)
        if (points.size < 2) return

        plotRect.set(dp(44f), dp(12f), w - dp(12f), h - dp(24f))
        canvas.drawRect(plotRect, borderPaint)

        for (i in 0..4) {
            val frac = i / 4f
            val py = plotRect.bottom - frac * plotRect.height()
            if (i in 1..3) canvas.drawLine(plotRect.left, py, plotRect.right, py, gridPaint)
            axisPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                String.format(Locale.US, "%.2fV", vcc * frac),
                plotRect.left - dp(5f),
                py + dp(3.5f),
                axisPaint
            )
        }

        val midY = plotRect.top + plotRect.height() * 0.5f
        canvas.drawLine(plotRect.left, midY, plotRect.right, midY, halfVccPaint)

        val tMin = points.first().tempC
        val tMax = points.last().tempC
        val tSpan = (tMax - tMin).coerceAtLeast(1e-6)

        fun mapX(t: Double): Float = plotRect.left + ((t - tMin) / tSpan).toFloat() * plotRect.width()
        fun mapY(v: Double): Float = plotRect.bottom - (v / vcc).toFloat().coerceIn(0f, 1f) * plotRect.height()

        axisPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("${NtcMathEngine.formatTemp(tMin)}°", plotRect.left, h - dp(6f), axisPaint)
        axisPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${NtcMathEngine.formatTemp(tMax)}°", plotRect.right, h - dp(6f), axisPaint)

        val linePath = Path()
        val areaPath = Path()
        points.forEachIndexed { idx, pt ->
            val x = mapX(pt.tempC)
            val y = mapY(pt.vOut)
            if (idx == 0) {
                linePath.moveTo(x, y)
                areaPath.moveTo(x, plotRect.bottom)
                areaPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                areaPath.lineTo(x, y)
            }
        }
        areaPath.lineTo(mapX(points.last().tempC), plotRect.bottom)
        areaPath.close()

        fillPaint.shader = LinearGradient(
            0f, plotRect.top, 0f, plotRect.bottom,
            Color.parseColor("#3310B981"),
            Color.parseColor("#0510B981"),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(areaPath, fillPaint)
        canvas.drawPath(linePath, curvePaint)

        if (selectedIndex in points.indices) {
            val sel = points[selectedIndex]
            val sx = mapX(sel.tempC)
            val sy = mapY(sel.vOut)
            canvas.drawLine(sx, plotRect.top, sx, plotRect.bottom, cursorPaint)
            canvas.drawCircle(sx, sy, dp(4f), dotPaint)
        }
    }
}
