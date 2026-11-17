package com.ntcrt.pro

import android.content.Context
import android.graphics.Bitmap
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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 精密示波器级 NTC R-T 特性曲线画布
 * 支持：线性/对数 Y 轴切换、公差包络填充、双曲线叠加对比、触摸十字准星、1600x1000 高清报告 PNG 离屏渲染
 */
class NtcCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var points: List<RtPoint> = emptyList()
        private set
    var comparePoints: List<RtPoint> = emptyList()
        private set
    var useLogScale: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var showToleranceBand: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var showCompareCurve: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var selectedIndex: Int = -1
        private set

    var onPointSelected: ((primary: RtPoint?, compare: RtPoint?) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0F1A")
        style = Paint.Style.FILL
    }
    private val plotBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D1422")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val majorGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C273D")
        style = Paint.Style.STROKE
        strokeWidth = dp(0.9f)
    }
    private val minorGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#131C2E")
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(4f)), 0f)
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = sp(10.5f)
        typeface = Typeface.MONOSPACE
    }
    private val bandFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val upperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val lowerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val comparePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F43F5E")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(4f)), 0f)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4400E5FF")
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val nomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val anchor25Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66F59E0B")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B300E5FF")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val dotOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4400E5FF")
        style = Paint.Style.FILL
    }
    private val dotInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }
    private val dotCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textSize = sp(10f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val plotRect = RectF()

    fun setCurveData(primary: List<RtPoint>, compare: List<RtPoint> = emptyList()) {
        points = primary
        comparePoints = compare
        selectedIndex = if (primary.isNotEmpty()) {
            // 默认选中最接近 25℃ 的点
            primary.indices.minByOrNull { abs(primary[it].tempC - 25.0) } ?: 0
        } else {
            -1
        }
        notifySelection()
        invalidate()
    }

    fun selectTemperature(tempC: Double) {
        if (points.isEmpty()) return
        val idx = points.indices.minByOrNull { abs(points[it].tempC - tempC) } ?: return
        if (idx != selectedIndex) {
            selectedIndex = idx
            notifySelection()
            invalidate()
        }
    }

    private fun notifySelection() {
        if (selectedIndex in points.indices) {
            val p = points[selectedIndex]
            val c = if (showCompareCurve && comparePoints.isNotEmpty()) {
                comparePoints.minByOrNull { abs(it.tempC - p.tempC) }
            } else null
            onPointSelected?.invoke(p, c)
        } else {
            onPointSelected?.invoke(null, null)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty() || plotRect.width() <= 0f) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateSelectionFromX(event.x, haptic = true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateSelectionFromX(event.x, haptic = false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateSelectionFromX(touchX: Float, haptic: Boolean) {
        val tMin = points.first().tempC
        val tMax = points.last().tempC
        val span = (tMax - tMin).coerceAtLeast(1e-6)
        val clamped = ((touchX - plotRect.left) / plotRect.width()).coerceIn(0f, 1f)
        val targetT = tMin + clamped * span
        val nearest = points.indices.minByOrNull { abs(points[it].tempC - targetT) } ?: return
        if (nearest != selectedIndex) {
            selectedIndex = nearest
            if (haptic) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            notifySelection()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawChartScene(
            canvas = canvas,
            widthPx = width.toFloat(),
            heightPx = height.toFloat(),
            scaleFactor = 1f,
            exportHeader = null
        )
    }

    /**
     * 渲染 1600×1000 高清工程报告图用于导出与分享
     */
    fun renderExportBitmap(spec: NtcSpec, compareSpec: NtcSpec?): Bitmap {
        val w = 1600
        val h = 1000
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val header = ExportHeaderInfo(spec, compareSpec)
        drawChartScene(canvas, w.toFloat(), h.toFloat(), scaleFactor = 1.65f, exportHeader = header)
        return bmp
    }

    private data class ExportHeaderInfo(val spec: NtcSpec, val compareSpec: NtcSpec?)

    private fun drawChartScene(
        canvas: Canvas,
        widthPx: Float,
        heightPx: Float,
        scaleFactor: Float,
        exportHeader: ExportHeaderInfo?
    ) {
        canvas.drawRect(0f, 0f, widthPx, heightPx, bgPaint)
        if (points.size < 2) return

        val topHeaderOffset = if (exportHeader != null) dp(82f) * scaleFactor else dp(12f)
        val footerOffset = if (exportHeader != null) dp(42f) * scaleFactor else dp(28f)
        val padLeft = dp(56f) * scaleFactor
        val padRight = dp(18f) * scaleFactor

        plotRect.set(padLeft, topHeaderOffset, widthPx - padRight, heightPx - footerOffset)
        if (plotRect.width() <= 10f || plotRect.height() <= 10f) return

        // 标题与图例（仅导出 PNG 报表时绘制顶部工程头）
        if (exportHeader != null) {
            drawExportHeader(canvas, widthPx, topHeaderOffset, scaleFactor, exportHeader)
        }

        canvas.drawRoundRect(plotRect, dp(6f), dp(6f), plotBgPaint)
        canvas.drawRoundRect(plotRect, dp(6f), dp(6f), borderPaint)

        val tMin = points.first().tempC
        val tMax = points.last().tempC
        val tSpan = (tMax - tMin).coerceAtLeast(1e-6)

        // 计算 Y 轴范围
        val yValues = ArrayList<Double>(points.size * 3)
        for (p in points) {
            yValues.add(p.rNomOhm)
            if (showToleranceBand) {
                yValues.add(p.rMinOhm)
                yValues.add(p.rMaxOhm)
            }
        }
        if (showCompareCurve) {
            for (c in comparePoints) yValues.add(c.rNomOhm)
        }

        val rawMinY = (yValues.minOrNull() ?: 1.0).coerceAtLeast(1e-6)
        val rawMaxY = (yValues.maxOrNull() ?: 10.0).coerceAtLeast(rawMinY * 1.05)

        val (yMinBound, yMaxBound) = if (useLogScale) {
            val lMin = log10(rawMinY)
            val lMax = log10(rawMaxY)
            val pad = ((lMax - lMin) * 0.08).coerceAtLeast(0.08)
            (lMin - pad) to (lMax + pad)
        } else {
            val span = rawMaxY - rawMinY
            val padTop = span * 0.08
            val padBot = span * 0.06
            (rawMinY - padBot).coerceAtLeast(0.0) to (rawMaxY + padTop)
        }

        fun mapX(t: Double): Float {
            return plotRect.left + ((t - tMin) / tSpan).toFloat() * plotRect.width()
        }

        fun mapY(rOhm: Double): Float {
            val v = if (useLogScale) log10(rOhm.coerceAtLeast(1e-9)) else rOhm
            val norm = ((v - yMinBound) / (yMaxBound - yMinBound).coerceAtLeast(1e-9)).toFloat()
            return plotRect.bottom - norm.coerceIn(0f, 1f) * plotRect.height()
        }

        // 网格与刻度
        axisTextPaint.textSize = sp(10.5f) * scaleFactor
        val yTicks = 5
        for (i in 0..yTicks) {
            val frac = i / yTicks.toFloat()
            val py = plotRect.bottom - frac * plotRect.height()
            if (i in 1 until yTicks) {
                canvas.drawLine(plotRect.left, py, plotRect.right, py, majorGridPaint)
            }
            if (i < yTicks) {
                val midPy = plotRect.bottom - ((i + 0.5f) / yTicks.toFloat()) * plotRect.height()
                canvas.drawLine(plotRect.left, midPy, plotRect.right, midPy, minorGridPaint)
            }
            val valY = yMinBound + frac * (yMaxBound - yMinBound)
            val ohmVal = if (useLogScale) 10.0.pow(valY) else valY
            val label = formatAxisOhm(ohmVal)
            axisTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(label, plotRect.left - dp(6f) * scaleFactor, py + dp(3.5f) * scaleFactor, axisTextPaint)
        }

        val xTicks = 5
        axisTextPaint.textAlign = Paint.Align.CENTER
        for (i in 0..xTicks) {
            val frac = i / xTicks.toFloat()
            val px = plotRect.left + frac * plotRect.width()
            if (i in 1 until xTicks) {
                canvas.drawLine(px, plotRect.top, px, plotRect.bottom, majorGridPaint)
            }
            val tVal = tMin + frac * tSpan
            val label = "${NtcMathEngine.formatTemp(tVal)}°"
            if (i == 0) axisTextPaint.textAlign = Paint.Align.LEFT
            else if (i == xTicks) axisTextPaint.textAlign = Paint.Align.RIGHT
            else axisTextPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(label, px, plotRect.bottom + dp(16f) * scaleFactor, axisTextPaint)
        }

        // 25℃ 参考线
        if (25.0 in tMin..tMax) {
            val x25 = mapX(25.0)
            canvas.drawLine(x25, plotRect.top, x25, plotRect.bottom, anchor25Paint)
            badgePaint.textSize = sp(9.5f) * scaleFactor
            badgePaint.color = Color.parseColor("#F59E0B")
            badgePaint.textAlign = Paint.Align.LEFT
            val tx = (x25 + dp(4f) * scaleFactor).coerceAtMost(plotRect.right - dp(42f) * scaleFactor)
            canvas.drawText("25℃", tx, plotRect.top + dp(13f) * scaleFactor, badgePaint)
        }

        canvas.save()
        canvas.clipRect(plotRect)

        // 公差包络填充
        if (showToleranceBand) {
            val bandPath = Path()
            val upperPath = Path()
            val lowerPath = Path()
            points.forEachIndexed { idx, pt ->
                val x = mapX(pt.tempC)
                val yMax = mapY(pt.rMaxOhm)
                if (idx == 0) {
                    bandPath.moveTo(x, yMax)
                    upperPath.moveTo(x, yMax)
                } else {
                    bandPath.lineTo(x, yMax)
                    upperPath.lineTo(x, yMax)
                }
            }
            for (idx in points.indices.reversed()) {
                val pt = points[idx]
                val x = mapX(pt.tempC)
                val yMin = mapY(pt.rMinOhm)
                bandPath.lineTo(x, yMin)
                if (idx == points.lastIndex) lowerPath.moveTo(x, yMin) else lowerPath.lineTo(x, yMin)
            }
            bandPath.close()

            bandFillPaint.shader = LinearGradient(
                0f, plotRect.top, 0f, plotRect.bottom,
                Color.parseColor("#3300E5FF"),
                Color.parseColor("#0A00E5FF"),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(bandPath, bandFillPaint)

            upperPaint.strokeWidth = dp(1.4f) * scaleFactor
            lowerPaint.strokeWidth = dp(1.4f) * scaleFactor
            canvas.drawPath(upperPath, upperPaint)
            canvas.drawPath(lowerPath, lowerPaint)
        }

        // 对比叠加曲线
        if (showCompareCurve && comparePoints.size >= 2) {
            val cmpPath = Path()
            comparePoints.forEachIndexed { idx, pt ->
                val x = mapX(pt.tempC)
                val y = mapY(pt.rNomOhm)
                if (idx == 0) cmpPath.moveTo(x, y) else cmpPath.lineTo(x, y)
            }
            comparePaint.strokeWidth = dp(1.8f) * scaleFactor
            canvas.drawPath(cmpPath, comparePaint)
        }

        // 主标称曲线（发光外层 + 锐利内芯）
        val nomPath = Path()
        points.forEachIndexed { idx, pt ->
            val x = mapX(pt.tempC)
            val y = mapY(pt.rNomOhm)
            if (idx == 0) nomPath.moveTo(x, y) else nomPath.lineTo(x, y)
        }
        glowPaint.strokeWidth = dp(6f) * scaleFactor
        nomPaint.strokeWidth = dp(2.4f) * scaleFactor
        canvas.drawPath(nomPath, glowPaint)
        canvas.drawPath(nomPath, nomPaint)

        // 当前选中十字准星与光点
        if (selectedIndex in points.indices) {
            val sel = points[selectedIndex]
            val sx = mapX(sel.tempC)
            val sy = mapY(sel.rNomOhm)
            crosshairPaint.strokeWidth = dp(1f) * scaleFactor
            canvas.drawLine(sx, plotRect.top, sx, plotRect.bottom, crosshairPaint)
            canvas.drawLine(plotRect.left, sy, plotRect.right, sy, crosshairPaint)

            canvas.drawCircle(sx, sy, dp(9f) * scaleFactor, dotOuterPaint)
            canvas.drawCircle(sx, sy, dp(4.5f) * scaleFactor, dotInnerPaint)
            canvas.drawCircle(sx, sy, dp(2f) * scaleFactor, dotCorePaint)
        }

        canvas.restore()

        if (exportHeader != null) {
            val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = sp(9.5f) * scaleFactor
                typeface = Typeface.MONOSPACE
            }
            canvas.drawText(
                "Generated by NTC R-T Pro Lab  |  Scale: ${if (useLogScale) "Log10(Y)" else "Linear"}  |  B-Equation: R(T)=R25·exp(B·(1/T-1/298.15))",
                plotRect.left,
                heightPx - dp(12f) * scaleFactor,
                footPaint
            )
        }
    }

    private fun drawExportHeader(
        canvas: Canvas,
        widthPx: Float,
        headerHeight: Float,
        scale: Float,
        info: ExportHeaderInfo
    ) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            textSize = sp(14f) * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            textSize = sp(10.5f) * scale
            typeface = Typeface.MONOSPACE
        }
        val spec = info.spec
        canvas.drawText(
            "${spec.manufacturer} — 温度阻值特性曲线 (R-T Curve)",
            dp(24f) * scale,
            dp(28f) * scale,
            titlePaint
        )
        canvas.drawText(
            "料号: ${spec.partNumber}  |  R25=${NtcMathEngine.formatResistanceAuto(spec.r25Ohm)} (±${spec.r25TolerancePct}%)  |  B=${spec.bValue.roundToInt()}K  |  范围: ${NtcMathEngine.formatTemp(spec.tMin)}~${NtcMathEngine.formatTemp(spec.tMax)}℃",
            dp(24f) * scale,
            dp(50f) * scale,
            subPaint
        )
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = sp(9.5f) * scale
            typeface = Typeface.MONOSPACE
        }
        val legendText = buildString {
            append("━━ 标称 R_nom   --- 上限 R_max   --- 下限 R_min")
            if (showCompareCurve && info.compareSpec != null) {
                append("   --- 对比: ${info.compareSpec.label}")
            }
        }
        canvas.drawText(legendText, dp(24f) * scale, headerHeight - dp(12f) * scale, legendPaint)
    }

    private fun formatAxisOhm(ohm: Double): String {
        return when {
            ohm >= 1_000_000.0 -> String.format(Locale.US, "%.1fM", ohm / 1_000_000.0)
            ohm >= 1_000.0 -> String.format(Locale.US, "%.1fk", ohm / 1_000.0)
            ohm >= 100.0 -> String.format(Locale.US, "%.0f", ohm)
            ohm >= 10.0 -> String.format(Locale.US, "%.1f", ohm)
            else -> String.format(Locale.US, "%.2f", ohm)
        }
    }
}
