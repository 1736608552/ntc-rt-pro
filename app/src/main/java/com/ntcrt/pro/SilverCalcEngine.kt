package com.ntcrt.pro

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

enum class SilverShapeMode {
    CIRCLE, // 圆片: R1 * r1^2 = R2 * r2^2
    SQUARE  // 方片: 边长 a = sqrt(R1 * pi * r1^2 / R2)
}

/**
 * 4 填 3 自动感应计算结果
 */
data class SilverSolveResult(
    val filledCount: Int,
    val solvedFieldIndex: Int?, // 1..4 对应自动填入的字段，null 表示非 3 项或处于校验态
    val solvedValue: Double?,
    val formattedValue: String?,
    val title: String,
    val displayValue: String,
    val formulaText: String,
    val isError: Boolean = false
)

/**
 * NTC 圆片银面直径与方片边长核心计算引擎
 */
object SilverCalcEngine {

    fun solveCircle(
        vR1: Double?,
        vr1: Double?,
        vR2: Double?,
        vr2: Double?,
        autoField: Int?
    ): SilverSolveResult {
        val hasR1 = vR1 != null && vR1 > 0.0 && autoField != 1
        val hasr1 = vr1 != null && vr1 > 0.0 && autoField != 2
        val hasR2 = vR2 != null && vR2 > 0.0 && autoField != 3
        val hasr2 = vr2 != null && vr2 > 0.0 && autoField != 4
        val count = listOf(hasR1, hasr1, hasR2, hasr2).count { it }

        return when (count) {
            3 -> when {
                !hasr2 -> {
                    val res = sqrt((vr1!! * vr1 * vR1!!) / vR2!!)
                    val fmt = formatNumber(res)
                    SilverSolveResult(
                        filledCount = 3,
                        solvedFieldIndex = 4,
                        solvedValue = res,
                        formattedValue = fmt,
                        title = "已填入：目标直径 2 (r₂)",
                        displayValue = "$fmt mm",
                        formulaText = "r₂ = √(R₁ × r₁² / R₂)\n= √(${formatNumber(vR1)} × ${formatNumber(vr1)}² / ${formatNumber(vR2)}) ≈ $fmt mm"
                    )
                }
                !hasR2 -> {
                    val res = (vR1!! * vr1!! * vr1) / (vr2!! * vr2)
                    val fmt = formatNumber(res)
                    SilverSolveResult(
                        filledCount = 3,
                        solvedFieldIndex = 3,
                        solvedValue = res,
                        formattedValue = fmt,
                        title = "已填入：目标阻值 2 (R₂)",
                        displayValue = "$fmt Ω",
                        formulaText = "R₂ = (R₁ × r₁²) / r₂²\n= (${formatNumber(vR1)} × ${formatNumber(vr1)}²) / ${formatNumber(vr2)}² ≈ $fmt Ω"
                    )
                }
                !hasr1 -> {
                    val res = sqrt((vr2!! * vr2 * vR2!!) / vR1!!)
                    val fmt = formatNumber(res)
                    SilverSolveResult(
                        filledCount = 3,
                        solvedFieldIndex = 2,
                        solvedValue = res,
                        formattedValue = fmt,
                        title = "已填入：原始直径 1 (r₁)",
                        displayValue = "$fmt mm",
                        formulaText = "r₁ = √(R₂ × r₂² / R₁)\n= √(${formatNumber(vR2)} × ${formatNumber(vr2)}² / ${formatNumber(vR1)}) ≈ $fmt mm"
                    )
                }
                else -> {
                    val res = (vR2!! * vr2!! * vr2) / (vr1!! * vr1)
                    val fmt = formatNumber(res)
                    SilverSolveResult(
                        filledCount = 3,
                        solvedFieldIndex = 1,
                        solvedValue = res,
                        formattedValue = fmt,
                        title = "已填入：原始阻值 1 (R₁)",
                        displayValue = "$fmt Ω",
                        formulaText = "R₁ = (R₂ × r₂²) / r₁²\n= (${formatNumber(vR2)} × ${formatNumber(vr2)}²) / ${formatNumber(vr1)}² ≈ $fmt Ω"
                    )
                }
            }
            4 -> {
                val left = vR1!! * vr1!! * vr1
                val right = vR2!! * vr2!! * vr2
                val pct = if (left != 0.0) (abs(left - right) / left) * 100.0 else 0.0
                SilverSolveResult(
                    filledCount = 4,
                    solvedFieldIndex = null,
                    solvedValue = null,
                    formattedValue = null,
                    title = "4 项均已填写 · 校验模式",
                    displayValue = "相对误差: ${formatNumber(pct)}%",
                    formulaText = "R₁·r₁² = ${formatNumber(left)}  |  R₂·r₂² = ${formatNumber(right)}"
                )
            }
            else -> SilverSolveResult(
                filledCount = count,
                solvedFieldIndex = null,
                solvedValue = null,
                formattedValue = null,
                title = "待输入（圆片公式：R₁ × r₁² = R₂ × r₂²）",
                displayValue = "已填 $count 项，还差 ${3 - count} 项",
                formulaText = "任意输入 3 个正数，留空项将直接自动计算填入。"
            )
        }
    }

    fun solveSquare(
        vR1: Double?,
        vr1: Double?,
        vR2: Double?,
        vSide: Double?,
        autoField: Int?
    ): SilverSolveResult {
        val hasR1 = vR1 != null && vR1 > 0.0 && autoField != 1
        val hasr1 = vr1 != null && vr1 > 0.0 && autoField != 2
        val hasR2 = vR2 != null && vR2 > 0.0 && autoField != 3
        val hasSide = vSide != null && vSide > 0.0 && autoField != 4
        val count = listOf(hasR1, hasr1, hasR2, hasSide).count { it }

        return when (count) {
            3 -> when {
                !hasSide -> {
                    val res = sqrt((vR1!! * PI * vr1!! * vr1) / vR2!!)
                    val fmt = formatNumber(res)
                    SilverSolveResult(
                        filledCount = 3,
                        solvedFieldIndex = 4,
                        solvedValue = res,
                        formattedValue = fmt,
                        title = "已填入：方片边长 (a)",
                        displayValue = "$fmt mm",
                        formulaText = "边长 = √(R₁ × πr₁² / R₂)\n= √(${formatNumber(vR1)} × π × ${formatNumber(vr1)}² / ${formatNumber(vR2)}) ≈ $fmt mm"
                    )
                }
                !hasR2 -> {
                    val res = (vR1!! * PI * vr1!! * vr1) / (vSide!! * vSide)
                    val fmt = formatNumber(res)
                    SilverSolveResult(
                        filledCount = 3,
                        solvedFieldIndex = 3,
                        solvedValue = res,
                        formattedValue = fmt,
                        title = "已填入：目标阻值 2 (R₂)",
                        displayValue = "$fmt Ω",
                        formulaText = "R₂ = (R₁ × πr₁²) / 边长²\n= (${formatNumber(vR1)} × π × ${formatNumber(vr1)}²) / ${formatNumber(vSide)}² ≈ $fmt Ω"
                    )
                }
                !hasr1 -> {
                    val res = sqrt((vR2!! * vSide!! * vSide) / (PI * vR1!!))
                    val fmt = formatNumber(res)
                    SilverSolveResult(
                        filledCount = 3,
                        solvedFieldIndex = 2,
                        solvedValue = res,
                        formattedValue = fmt,
                        title = "已填入：圆半径 1 (r₁)",
                        displayValue = "$fmt mm",
                        formulaText = "r₁ = √(R₂ × 边长² / (π × R₁))\n= √(${formatNumber(vR2)} × ${formatNumber(vSide)}² / (π × ${formatNumber(vR1)})) ≈ $fmt mm"
                    )
                }
                else -> {
                    val res = (vR2!! * vSide!! * vSide) / (PI * vr1!! * vr1)
                    val fmt = formatNumber(res)
                    SilverSolveResult(
                        filledCount = 3,
                        solvedFieldIndex = 1,
                        solvedValue = res,
                        formattedValue = fmt,
                        title = "已填入：原始阻值 1 (R₁)",
                        displayValue = "$fmt Ω",
                        formulaText = "R₁ = (R₂ × 边长²) / (π × r₁²)\n= (${formatNumber(vR2)} × ${formatNumber(vSide)}²) / (π × ${formatNumber(vr1)}²) ≈ $fmt Ω"
                    )
                }
            }
            4 -> {
                val left = vR1!! * PI * vr1!! * vr1
                val right = vR2!! * vSide!! * vSide
                val pct = if (left != 0.0) (abs(left - right) / left) * 100.0 else 0.0
                SilverSolveResult(
                    filledCount = 4,
                    solvedFieldIndex = null,
                    solvedValue = null,
                    formattedValue = null,
                    title = "4 项均已填写 · 校验模式",
                    displayValue = "相对误差: ${formatNumber(pct)}%",
                    formulaText = "R₁·πr₁² = ${formatNumber(left)}  |  R₂·a² = ${formatNumber(right)}"
                )
            }
            else -> SilverSolveResult(
                filledCount = count,
                solvedFieldIndex = null,
                solvedValue = null,
                formattedValue = null,
                title = "待输入（方片公式：边长 = √(R₁ × πr₁² / R₂)）",
                displayValue = "已填 $count 项，还差 ${3 - count} 项",
                formulaText = "任意输入 3 个正数，留空项将直接自动计算填入。"
            )
        }
    }

    fun formatNumber(value: Double): String {
        return if (value.isNaN() || value.isInfinite()) {
            "NaN"
        } else {
            BigDecimal(value)
                .setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        }
    }
}
