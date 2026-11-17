package com.ntcrt.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtcMathEngineTest {

    @Test
    fun verifyDefault5OhmTableExactValues() {
        val spec = NtcMathEngine.defaultSpec()
        val points = NtcMathEngine.generateTable(spec)
        assertEquals(34, points.size)

        val p20 = points.first { it.tempC == 20.0 }
        assertEquals(6.321677, p20.rNomOhm, 1e-5)
        assertEquals(5.246992, p20.rMinOhm, 1e-5)
        assertEquals(7.396362, p20.rMaxOhm, 1e-5)

        val p25 = points.first { it.tempC == 25.0 }
        assertEquals(5.0, p25.rNomOhm, 1e-9)
        assertEquals(4.15, p25.rMinOhm, 1e-9)
        assertEquals(5.85, p25.rMaxOhm, 1e-9)

        val p35 = points.first { it.tempC == 35.0 }
        assertEquals(3.200090, p35.rNomOhm, 1e-5)
        assertEquals(2.656075, p35.rMinOhm, 1e-5)
        assertEquals(3.744106, p35.rMaxOhm, 1e-5)

        val p365 = points.first { it.tempC == 36.5 }
        assertEquals(3.000342, p365.rNomOhm, 1e-5)
        assertEquals(2.490284, p365.rMinOhm, 1e-5)
        assertEquals(3.510400, p365.rMaxOhm, 1e-5)
    }

    @Test
    fun verifyReverseTemperatureAndSolveBValue() {
        val spec = NtcMathEngine.defaultSpec()
        val rAt30 = NtcMathEngine.nominalResistance(30.0, spec.r25Ohm, spec.bValue)
        val rev = NtcMathEngine.reverseSolveTemperature(rAt30, spec)
        assertEquals(30.0, rev.tempNomC, 1e-6)
        assertTrue(rev.tempLowC < rev.tempNomC)
        assertTrue(rev.tempHighC > rev.tempNomC)

        val rAt20 = NtcMathEngine.nominalResistance(20.0, 5.0, 4100.0)
        val rAt35 = NtcMathEngine.nominalResistance(35.0, 5.0, 4100.0)
        val bSolved = NtcMathEngine.solveBValue(20.0, rAt20, 35.0, rAt35)
        assertEquals(4100.0, bSolved, 1e-6)
    }

    @Test
    fun verifyAsymmetricR25AndBTolerances() {
        val spec = NtcSpec(
            r25Ohm = 100.0,
            bValue = 4000.0,
            r25TolPlusPct = 15.0,
            r25TolMinusPct = 5.0,
            bTolPlusPct = 3.0,
            bTolMinusPct = 1.0
        )
        // 25℃ 下 B 公差影响为 0，严格等于 -5% 与 +15%
        val (min25, max25) = NtcMathEngine.resistanceBounds(25.0, spec)
        assertEquals(95.0, min25, 1e-6)
        assertEquals(115.0, max25, 1e-6)

        // 低温 0℃ (<25℃)：最大值取 (R25_max=115, B_max=4120)，最小值取 (R25_min=95, B_min=3960)
        val (min0, max0) = NtcMathEngine.resistanceBounds(0.0, spec)
        assertEquals(NtcMathEngine.nominalResistance(0.0, 95.0, 3960.0), min0, 1e-6)
        assertEquals(NtcMathEngine.nominalResistance(0.0, 115.0, 4120.0), max0, 1e-6)

        // 高温 60℃ (>25℃)：最大值取 (R25_max=115, B_min=3960)，最小值取 (R25_min=95, B_max=4120)
        val (min60, max60) = NtcMathEngine.resistanceBounds(60.0, spec)
        assertEquals(NtcMathEngine.nominalResistance(60.0, 95.0, 4120.0), min60, 1e-6)
        assertEquals(NtcMathEngine.nominalResistance(60.0, 115.0, 3960.0), max60, 1e-6)
    }
}
