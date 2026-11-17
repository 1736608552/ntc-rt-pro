package com.ntcrt.pro

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class SilverCalcEngineTest {

    @Test
    fun verifyCircleFourChooseThree() {
        // R1=54, r1=17.5, R2=110 => r2 ≈ 12.261358
        val res = SilverCalcEngine.solveCircle(54.0, 17.5, 110.0, null, null)
        assertEquals(4, res.solvedFieldIndex)
        assertEquals(12.261358, res.solvedValue!!, 1e-5)

        // 反向算 R1
        val resR1 = SilverCalcEngine.solveCircle(null, 17.5, 110.0, res.solvedValue, null)
        assertEquals(1, resR1.solvedFieldIndex)
        assertEquals(54.0, resR1.solvedValue!!, 1e-5)
    }

    @Test
    fun verifySquareFourChooseThree() {
        val r1 = 5.0
        val rad1 = 4.0
        val r2 = 10.0
        val expectedSide = sqrt((r1 * PI * rad1 * rad1) / r2)
        val resSide = SilverCalcEngine.solveSquare(r1, rad1, r2, null, null)
        assertEquals(4, resSide.solvedFieldIndex)
        assertEquals(expectedSide, resSide.solvedValue!!, 1e-6)

        val resR2 = SilverCalcEngine.solveSquare(r1, rad1, null, expectedSide, null)
        assertEquals(3, resR2.solvedFieldIndex)
        assertEquals(r2, resR2.solvedValue!!, 1e-6)
    }
}
