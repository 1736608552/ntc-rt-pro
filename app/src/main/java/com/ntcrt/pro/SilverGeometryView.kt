package com.ntcrt.pro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 动态几何比例蓝图视图：左侧原始圆片 → 右侧目标圆片 / 方片
 * 随输入尺寸比例实时缩放图形并绘制工程标注线
 */
class SilverGeometryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var mode: SilverShapeMode = SilverShapeMode.CIRCLE
    private var r1Val: Double? = null
    private var size1Val: Double? = null
    private var r2Val: Double? = null
    private var size2Val: Double? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0F1A")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#141E30")
        style = Paint.Style.STROKE
        strokeWidth = dp(0.7f)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val leftFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2400E5FF")
        style = Paint.Style.FILL
    }
    private val leftStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
    }
    private val rightFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2410B981")
        style = Paint.Style.FILL
    }
    private val rightStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textSize = sp(10.5f)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = sp(9.5f)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    fun updateBlueprint(
        shapeMode: SilverShapeMode,
        r1: Double?,
        size1: Double?,
        r2: Double?,
        size2: Double?
    ) {
        mode = shapeMode
        r1Val = r1
        size1Val = size1
        r2Val = r2
        size2Val = size2
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, dp(12f), dp(12f), bgPaint)

        // 工程网格底纹
        val step = dp(16f)
        var gx = step
        while (gx < w) {
            canvas.drawLine(gx, 0f, gx, h, gridPaint)
            gx += step
        }
        var gy = step
        while (gy < h) {
            canvas.drawLine(0f, gy, w, gy, gridPaint)
            gy += step
        }
        canvas.drawRoundRect(0f, 0f, w, h, dp(12f), dp(12f), borderPaint)

        val cy = h * 0.48f
        val leftCx = w * 0.25f
        val rightCx = w * 0.75f

        val baseRadius = min(w * 0.14f, h * 0.27f)
        val ratio = if (size1Val != null && size1Val!! > 0 && size2Val != null && size2Val!! > 0) {
            // 若方片模式下 size1 是半径而 size2 是边长，则半边长与半径对比更真实
            val rightRef = if (mode == SilverShapeMode.SQUARE) size2Val!! * 0.5 else size2Val!!
            (rightRef / size1Val!!).toFloat().coerceIn(0.45f, 1.38f)
        } else {
            1.0f
        }
        val leftR = if (ratio > 1.0f) baseRadius / ratio.coerceAtMost(1.3f) else baseRadius
        val rightHalf = (leftR * ratio).coerceIn(dp(14f), min(w * 0.17f, h * 0.34f))

        // 左侧原始圆片
        canvas.drawCircle(leftCx, cy, leftR, leftFillPaint)
        canvas.drawCircle(leftCx, cy, leftR, leftStrokePaint)
        if (mode == SilverShapeMode.CIRCLE) {
            // 圆片标直径线
            canvas.drawLine(leftCx - leftR, cy, leftCx + leftR, cy, dimPaint)
        } else {
            // 方片模式标圆半径线 r1
            canvas.drawLine(leftCx, cy, leftCx + leftR, cy, dimPaint)
            canvas.drawCircle(leftCx, cy, dp(2.2f), leftStrokePaint)
        }

        // 中间转换箭头
        val ax1 = w * 0.44f
        val ax2 = w * 0.56f
        canvas.drawLine(ax1, cy, ax2, cy, arrowPaint)
        canvas.drawLine(ax2 - dp(6f), cy - dp(5f), ax2, cy, arrowPaint)
        canvas.drawLine(ax2 - dp(6f), cy + dp(5f), ax2, cy, arrowPaint)

        // 右侧目标圆片或方片
        if (mode == SilverShapeMode.CIRCLE) {
            canvas.drawCircle(rightCx, cy, rightHalf, rightFillPaint)
            canvas.drawCircle(rightCx, cy, rightHalf, rightStrokePaint)
            canvas.drawLine(rightCx - rightHalf, cy, rightCx + rightHalf, cy, dimPaint)
        } else {
            canvas.drawRect(
                rightCx - rightHalf,
                cy - rightHalf,
                rightCx + rightHalf,
                cy + rightHalf,
                rightFillPaint
            )
            canvas.drawRect(
                rightCx - rightHalf,
                cy - rightHalf,
                rightCx + rightHalf,
                cy + rightHalf,
                rightStrokePaint
            )
            canvas.drawLine(rightCx - rightHalf, cy, rightCx + rightHalf, cy, dimPaint)
        }

        // 顶部名称 & 底部尺寸/阻值读数
        val leftRStr = r1Val?.takeIf { it > 0 }?.let { "R₁=${SilverCalcEngine.formatNumber(it)}Ω" } ?: "R₁=?"
        val leftSizeTag = if (mode == SilverShapeMode.CIRCLE) "直径r₁" else "半径r₁"
        val leftSizeStr = size1Val?.takeIf { it > 0 }?.let { "$leftSizeTag=${SilverCalcEngine.formatNumber(it)}mm" } ?: "$leftSizeTag=?"

        val rightRStr = r2Val?.takeIf { it > 0 }?.let { "R₂=${SilverCalcEngine.formatNumber(it)}Ω" } ?: "R₂=?"
        val rightSizeTag = if (mode == SilverShapeMode.CIRCLE) "直径r₂" else "边长a"
        val rightSizeStr = size2Val?.takeIf { it > 0 }?.let { "$rightSizeTag=${SilverCalcEngine.formatNumber(it)}mm" } ?: "$rightSizeTag=?"

        labelPaint.color = Color.parseColor("#00E5FF")
        canvas.drawText(leftRStr, leftCx, dp(16f), labelPaint)
        subLabelPaint.color = Color.parseColor("#F59E0B")
        canvas.drawText(leftSizeStr, leftCx, h - dp(8f), subLabelPaint)

        labelPaint.color = Color.parseColor("#10B981")
        canvas.drawText(rightRStr, rightCx, dp(16f), labelPaint)
        subLabelPaint.color = Color.parseColor("#F59E0B")
        canvas.drawText(rightSizeStr, rightCx, h - dp(8f), subLabelPaint)

        val midModeTxt = if (mode == SilverShapeMode.CIRCLE) "R₁r₁²=R₂r₂²" else "R₁πr₁²=R₂a²"
        subLabelPaint.color = Color.parseColor("#64748B")
        canvas.drawText(midModeTxt, w * 0.5f, cy - dp(12f), subLabelPaint)
    }
}
