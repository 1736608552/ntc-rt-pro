package com.ntcrt.pro

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * NTC 热敏电阻规格参数配置
 */
data class NtcSpec(
    val manufacturer: String = "",
    val partNumber: String = "",
    val r25Ohm: Double = 5.0,
    val bValue: Double = 4100.0,
    val r25TolerancePct: Double = 17.0,
    val bTolerancePct: Double = 1.0,
    val includeBTolerance: Boolean = false,
    val tMin: Double = 20.0,
    val tMax: Double = 36.5,
    val tStep: Double = 0.5
)

/**
 * 单个温度点的计算结果
 */
data class RtPoint(
    val tempC: Double,
    val rNomOhm: Double,
    val rMinOhm: Double,
    val rMaxOhm: Double,
    val alphaPctPerC: Double
)

/**
 * 阻值反推温度结果
 */
data class ReverseTempResult(
    val tempNomC: Double,
    val tempLowC: Double,
    val tempHighC: Double,
    val alphaPctPerC: Double
)

/**
 * 分压电路仿真单点结果
 */
data class DividerPoint(
    val tempC: Double,
    val rNtcOhm: Double,
    val vOut: Double,
    val adcCode: Int
)

/**
 * NTC 精密计算引擎：B 值方程、双向反推、误差传播、分压仿真及报表生成
 */
object NtcMathEngine {

    const val KELVIN_OFFSET = 273.15
    const val T25_KELVIN = 25.0 + KELVIN_OFFSET

    fun defaultSpec(): NtcSpec = NtcSpec(
        manufacturer = "",
        partNumber = "",
        r25Ohm = 5.0,
        bValue = 4100.0,
        r25TolerancePct = 17.0,
        bTolerancePct = 1.0,
        includeBTolerance = false,
        tMin = 20.0,
        tMax = 36.5,
        tStep = 0.5
    )

    /**
     * 标准 B 值公式计算标称阻值 (Ω)
     */
    fun nominalResistance(tempC: Double, r25Ohm: Double, bValue: Double): Double {
        require(r25Ohm > 0.0) { "R25 必须大于 0" }
        require(bValue > 0.0) { "B 值必须大于 0" }
        val tk = tempC + KELVIN_OFFSET
        require(tk > 0.0) { "温度必须高于绝对零度" }
        return r25Ohm * exp(bValue * (1.0 / tk - 1.0 / T25_KELVIN))
    }

    /**
     * 计算单点阻值上下限（支持仅 R25 固定公差，或叠加 B 值公差的精确极值传播）
     */
    fun resistanceBounds(tempC: Double, spec: NtcSpec): Pair<Double, Double> {
        val tolR = (spec.r25TolerancePct / 100.0).coerceIn(0.0, 0.99)
        val rNom = nominalResistance(tempC, spec.r25Ohm, spec.bValue)
        if (!spec.includeBTolerance || spec.bTolerancePct <= 0.0) {
            return (rNom * (1.0 - tolR)) to (rNom * (1.0 + tolR))
        }
        val tolB = (spec.bTolerancePct / 100.0).coerceIn(0.0, 0.99)
        val r25Min = spec.r25Ohm * (1.0 - tolR)
        val r25Max = spec.r25Ohm * (1.0 + tolR)
        val bMin = spec.bValue * (1.0 - tolB)
        val bMax = spec.bValue * (1.0 + tolB)

        val candidates = listOf(
            nominalResistance(tempC, r25Min, bMin),
            nominalResistance(tempC, r25Min, bMax),
            nominalResistance(tempC, r25Max, bMin),
            nominalResistance(tempC, r25Max, bMax)
        )
        return candidates.min() to candidates.max()
    }

    /**
     * 温度电阻系数 α = -B / T^2 × 100 (%/℃)
     */
    fun alphaPercentPerC(tempC: Double, bValue: Double): Double {
        val tk = tempC + KELVIN_OFFSET
        if (tk <= 0.0) return 0.0
        return -(bValue / (tk * tk)) * 100.0
    }

    /**
     * 生成完整温度-阻值特性序列（限幅保护避免极端步进卡顿）
     */
    fun generateTable(spec: NtcSpec, maxPoints: Int = 2001): List<RtPoint> {
        val step = if (spec.tStep <= 0.0) 0.5 else spec.tStep
        val low = minOf(spec.tMin, spec.tMax)
        val high = maxOf(spec.tMin, spec.tMax)
        val rawCount = ((high - low) / step).toInt() + 1
        val count = rawCount.coerceIn(1, maxPoints)
        val effectiveStep = if (rawCount > maxPoints && count > 1) (high - low) / (count - 1) else step

        val list = ArrayList<RtPoint>(count)
        for (i in 0 until count) {
            val rawT = low + i * effectiveStep
            val tempC = ((rawT * 1000.0).roundToInt() / 1000.0).coerceAtMost(high + 1e-6)
            val rNom = nominalResistance(tempC, spec.r25Ohm, spec.bValue)
            val (rMin, rMax) = resistanceBounds(tempC, spec)
            val alpha = alphaPercentPerC(tempC, spec.bValue)
            list.add(RtPoint(tempC, rNom, rMin, rMax, alpha))
        }
        if (list.isNotEmpty() && abs(list.last().tempC - high) > 1e-4 && rawCount <= maxPoints) {
            val remainder = high - list.last().tempC
            if (remainder >= effectiveStep * 0.99) {
                val rNom = nominalResistance(high, spec.r25Ohm, spec.bValue)
                val (rMin, rMax) = resistanceBounds(high, spec)
                list.add(RtPoint(high, rNom, rMin, rMax, alphaPercentPerC(high, spec.bValue)))
            }
        }
        return list
    }

    /**
     * 阻值反推温度 (℃)
     */
    fun temperatureFromResistance(resistanceOhm: Double, r25Ohm: Double, bValue: Double): Double {
        require(resistanceOhm > 0.0) { "实测阻值必须大于 0" }
        require(r25Ohm > 0.0 && bValue > 0.0) { "R25 与 B 值必须大于 0" }
        val invT = (1.0 / T25_KELVIN) + (1.0 / bValue) * ln(resistanceOhm / r25Ohm)
        require(invT > 0.0) { "阻值超出物理可解范围" }
        return (1.0 / invT) - KELVIN_OFFSET
    }

    /**
     * 根据实测阻值及器件公差带，反算中心温度与可能温度偏差范围
     */
    fun reverseSolveTemperature(resistanceOhm: Double, spec: NtcSpec): ReverseTempResult {
        val tNom = temperatureFromResistance(resistanceOhm, spec.r25Ohm, spec.bValue)
        val tolR = (spec.r25TolerancePct / 100.0).coerceIn(0.0, 0.99)
        val tolB = if (spec.includeBTolerance) (spec.bTolerancePct / 100.0).coerceIn(0.0, 0.99) else 0.0
        val r25Candidates = listOf(spec.r25Ohm * (1.0 - tolR), spec.r25Ohm * (1.0 + tolR))
        val bCandidates = listOf(spec.bValue * (1.0 - tolB), spec.bValue * (1.0 + tolB))
        val temps = ArrayList<Double>(4)
        for (r25c in r25Candidates) {
            for (bc in bCandidates) {
                temps.add(temperatureFromResistance(resistanceOhm, r25c, bc))
            }
        }
        val alpha = alphaPercentPerC(tNom, spec.bValue)
        return ReverseTempResult(
            tempNomC = tNom,
            tempLowC = temps.min(),
            tempHighC = temps.max(),
            alphaPctPerC = alpha
        )
    }

    /**
     * 两点实测法反算 B 值 (K)
     */
    fun solveBValue(t1C: Double, r1Ohm: Double, t2C: Double, r2Ohm: Double): Double {
        require(abs(t1C - t2C) > 1e-6) { "两个测量温度不能相同" }
        require(r1Ohm > 0.0 && r2Ohm > 0.0) { "实测阻值必须大于 0" }
        val tk1 = t1C + KELVIN_OFFSET
        val tk2 = t2C + KELVIN_OFFSET
        return ln(r1Ohm / r2Ohm) / (1.0 / tk1 - 1.0 / tk2)
    }

    /**
     * 计算串联分压电路 Vout 及 ADC 码值
     */
    fun simulateDivider(
        spec: NtcSpec,
        vcc: Double,
        rRefOhm: Double,
        ntcOnTop: Boolean,
        adcBits: Int
    ): List<DividerPoint> {
        val points = generateTable(spec)
        val maxAdc = (1 shl adcBits.coerceIn(8, 16)) - 1
        return points.map { pt ->
            val rNtc = pt.rNomOhm.coerceAtLeast(0.0)
            val denom = rNtc + rRefOhm
            val vOut = if (denom <= 0.0) 0.0 else {
                if (ntcOnTop) vcc * (rRefOhm / denom) else vcc * (rNtc / denom)
            }
            val code = ((vOut / vcc.coerceAtLeast(1e-9)) * maxAdc).roundToInt().coerceIn(0, maxAdc)
            DividerPoint(pt.tempC, pt.rNomOhm, vOut, code)
        }
    }

    fun formatResistanceAuto(ohm: Double): String {
        return when {
            ohm >= 1_000_000.0 -> String.format(Locale.US, "%.4f MΩ", ohm / 1_000_000.0)
            ohm >= 1_000.0 -> String.format(Locale.US, "%.4f kΩ", ohm / 1_000.0)
            ohm >= 100.0 -> String.format(Locale.US, "%.3f Ω", ohm)
            else -> String.format(Locale.US, "%.4f Ω", ohm)
        }
    }

    fun formatResistanceCompact(ohm: Double, divideBy1000: Boolean): String {
        val v = if (divideBy1000) ohm / 1000.0 else ohm
        return when {
            v >= 1000.0 -> String.format(Locale.US, "%.2f", v)
            v >= 100.0 -> String.format(Locale.US, "%.3f", v)
            else -> String.format(Locale.US, "%.4f", v)
        }
    }

    fun formatTemp(tempC: Double): String {
        return if (abs(tempC - tempC.roundToInt()) < 1e-6) {
            String.format(Locale.US, "%.1f", tempC)
        } else {
            String.format(Locale.US, "%.2f", tempC).trimEnd('0').trimEnd('.')
        }
    }

    fun specTitlePrefix(spec: NtcSpec): String {
        val parts = listOf(spec.manufacturer.trim(), spec.partNumber.trim()).filter { it.isNotEmpty() }
        return if (parts.isEmpty()) "NTC R-T 特性分析" else parts.joinToString(" · ")
    }

    fun exportCsv(spec: NtcSpec, points: List<RtPoint>): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.appendLine("# ${specTitlePrefix(spec)}")
        sb.appendLine("# R25(Ω),${spec.r25Ohm},B值(K),${spec.bValue},R25公差(±%),${spec.r25TolerancePct},叠加B值公差,${if (spec.includeBTolerance) "是(±${spec.bTolerancePct}%)" else "否"}")
        sb.appendLine("温度 T(℃),下限 R_min(Ω),标称阻值 R_nom(Ω),上限 R_max(Ω),灵敏度 α(%/℃)")
        for (pt in points) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    "%s,%.6f,%.6f,%.6f,%.4f",
                    formatTemp(pt.tempC), pt.rMinOhm, pt.rNomOhm, pt.rMaxOhm, pt.alphaPctPerC
                )
            )
        }
        return sb.toString()
    }

    fun exportMarkdown(spec: NtcSpec, points: List<RtPoint>): String {
        val sb = StringBuilder()
        sb.appendLine("### ${specTitlePrefix(spec)}")
        sb.appendLine("- 参数：`R25 = ${spec.r25Ohm} Ω (±${spec.r25TolerancePct}%)`，`B = ${spec.bValue} K`")
        sb.appendLine()
        sb.appendLine("| 温度 T (℃) | 下限 R_min (Ω) | 标称 R_nom (Ω) | 上限 R_max (Ω) | α (%/℃) |")
        sb.appendLine("| :---: | :---: | :---: | :---: | :---: |")
        for (pt in points) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    "| %s | %.4f | **%.4f** | %.4f | %.3f |",
                    formatTemp(pt.tempC), pt.rMinOhm, pt.rNomOhm, pt.rMaxOhm, pt.alphaPctPerC
                )
            )
        }
        return sb.toString()
    }

    fun exportCLookupTable(spec: NtcSpec, points: List<RtPoint>): String {
        val sb = StringBuilder()
        val count = points.size
        sb.appendLine("/**")
        sb.appendLine(" * ${specTitlePrefix(spec)} LUT")
        sb.appendLine(" * R25 = ${spec.r25Ohm} Ohm (±${spec.r25TolerancePct}%), B = ${spec.bValue} K")
        sb.appendLine(" * Range: ${formatTemp(spec.tMin)} C .. ${formatTemp(spec.tMax)} C, Step: ${spec.tStep} C")
        sb.appendLine(" */")
        sb.appendLine("#include <stdint.h>")
        sb.appendLine()
        sb.appendLine("#define NTC_LUT_COUNT   $count")
        sb.appendLine(String.format(Locale.US, "#define NTC_T_MIN_C     %.2ff", points.firstOrNull()?.tempC ?: spec.tMin))
        sb.appendLine(String.format(Locale.US, "#define NTC_T_STEP_C    %.3ff", spec.tStep))
        sb.appendLine()
        sb.appendLine("const float ntc_r_nom_ohm[NTC_LUT_COUNT] = {")
        points.chunked(6).forEachIndexed { idx, chunk ->
            val line = chunk.joinToString(", ") { String.format(Locale.US, "%.4ff", it.rNomOhm) }
            val comma = if ((idx + 1) * 6 < count) "," else ""
            sb.appendLine("    $line$comma")
        }
        sb.appendLine("};")
        sb.appendLine()
        sb.appendLine("float ntc_lookup_temp(float r_measured_ohm) {")
        sb.appendLine("    if (r_measured_ohm >= ntc_r_nom_ohm[0]) return NTC_T_MIN_C;")
        sb.appendLine("    if (r_measured_ohm <= ntc_r_nom_ohm[NTC_LUT_COUNT - 1]) {")
        sb.appendLine("        return NTC_T_MIN_C + (NTC_LUT_COUNT - 1) * NTC_T_STEP_C;")
        sb.appendLine("    }")
        sb.appendLine("    for (int i = 0; i < NTC_LUT_COUNT - 1; i++) {")
        sb.appendLine("        float r_hi = ntc_r_nom_ohm[i];")
        sb.appendLine("        float r_lo = ntc_r_nom_ohm[i + 1];")
        sb.appendLine("        if (r_measured_ohm <= r_hi && r_measured_ohm >= r_lo) {")
        sb.appendLine("            float frac = (r_hi - r_measured_ohm) / (r_hi - r_lo);")
        sb.appendLine("            return NTC_T_MIN_C + (i + frac) * NTC_T_STEP_C;")
        sb.appendLine("        }")
        sb.appendLine("    }")
        sb.appendLine("    return 25.0f;")
        sb.appendLine("}")
        return sb.toString()
    }
}
