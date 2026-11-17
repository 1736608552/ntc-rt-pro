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
    fun verifyBTolerancePropagationWidensAwayFrom25C() {
        val spec = NtcSpec(
            r25Ohm = 10000.0,
            bValue = 3950.0,
            r25TolerancePct = 1.0,
            bTolerancePct = 2.0,
            includeBTolerance = true
        )
        val (minAt25, maxAt25) = NtcMathEngine.resistanceBounds(25.0, spec)
        assertEquals(9900.0, minAt25, 1e-6)
        assertEquals(10100.0, maxAt25, 1e-6)

        val (minAt85, maxAt85) = NtcMathEngine.resistanceBounds(85.0, spec)
        val nomAt85 = NtcMathEngine.nominalResistance(85.0, 10000.0, 3950.0)
        assertTrue(minAt85 < nomAt85 * 0.99)
        assertTrue(maxAt85 > nomAt85 * 1.01)
    }
}
