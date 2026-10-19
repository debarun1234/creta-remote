package com.debarun.cretaremote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelLedgerTest {
    private val hour = 3_600_000L

    private class Run(val fills: List<Fill>, val gauge: List<GaugePoint>, val odo: Double, val level: Double)

    /**
     * A 50 L tank, a car doing exactly 14 km/L and only partial top-ups of 25 L, never a brim-full fill-up.
     * The gauge is read every 25 km. [unloggedAtStep] adds 20 L to the tank that the driver never logged.
     */
    private fun partialOnly(unloggedAtStep: Int = -1): Run {
        val fills = mutableListOf(Fill(1_000_000_000L, 10_000.0, 20.0, 100.0, false))
        val gauge = mutableListOf<GaugePoint>()
        var odo = 10_000.0
        var level = 30.0
        var ts = 1_000_000_000L
        for (step in 1..60) {
            odo += 25.0
            level -= 25.0 / 14.0
            ts += hour
            if (step == unloggedAtStep) level += 20.0
            if (level < 12.0) {
                fills += Fill(ts, odo, 25.0, 100.0, false)
                level += 25.0
                ts += 60_000L
            } else {
                gauge += GaugePoint(ts, odo, level)
            }
        }
        return Run(fills, gauge, odo, level)
    }

    @Test
    fun partialFillsAloneMeasureEconomyFromTheGauge() {
        val r = partialOnly()
        val s = FuelLedger.stats(r.fills, r.odo, 50.0, 100.0, r.level, r.gauge)
        assertTrue(s.legs.isNotEmpty())
        assertTrue(s.legs.none { it.exact })
        assertEquals(14.0, s.kmplRecent!!, 0.05)
        assertEquals(0, s.recentExact)
        assertNotNull(s.errPct)
        assertTrue(s.legs.all { it.km >= FuelLedger.MIN_GAUGE_KM })
    }

    @Test
    fun noGaugeHistoryMeansNoGuessing() {
        val r = partialOnly()
        val s = FuelLedger.stats(r.fills, null, 50.0, 100.0, null, emptyList())
        assertNull(s.kmplRecent)
        assertTrue(s.legs.isEmpty())
    }

    @Test
    fun anUnloggedRefuelNeverSitsInsideAStretch() {
        val r = partialOnly(unloggedAtStep = 20)
        val jumpOdo = 10_000.0 + 25.0 * 20 - 12.5
        val s = FuelLedger.stats(r.fills, r.odo, 50.0, 100.0, r.level, r.gauge)
        assertTrue(s.legs.none { it.fromOdo < jumpOdo && it.toOdo > jumpOdo })
        assertEquals(14.0, s.kmplRecent!!, 0.05)
    }

    @Test
    fun brimToBrimStretchesStayExactAndIgnoreTheGauge() {
        val fills = listOf(
            Fill(1_000L, 1_000.0, 40.0, 100.0, true),
            Fill(2_000L, 1_200.0, 12.0, 100.0, false),
            Fill(3_000L, 1_450.0, 18.0, 100.0, true),
        )
        // a wildly wrong gauge must not change an exact stretch
        val gauge = listOf(GaugePoint(1_500L, 1_100.0, 10.0), GaugePoint(2_500L, 1_300.0, 45.0))
        val s = FuelLedger.stats(fills, 1_450.0, 50.0, 100.0, null, gauge)
        val exact = s.legs.filter { it.exact }
        assertEquals(1, exact.size)
        assertEquals(15.0, exact[0].kmpl, 0.001)
        assertEquals(15.0, s.kmplRecent!!, 0.001)
        assertEquals(s.recentLegs, s.recentExact)
        assertTrue(s.legs.size == exact.size)
    }

    @Test
    fun aBrimFillAfterPartialsClosesTheGaugeStretchWithAnExactEnd() {
        val r = partialOnly()
        val last = r.fills.last()
        // the driver fills to the brim 300 km later; the pump takes exactly what 300 km at 14 km/L plus the gauge level implies
        val odoBrim = r.odo + 300.0
        val levelBefore = r.level - 300.0 / 14.0
        val brim = Fill(r.fills.last().ts + 100 * hour, odoBrim, 50.0 - levelBefore, 100.0, true)
        val s = FuelLedger.stats(r.fills + brim, odoBrim, 50.0, 100.0, 50.0, r.gauge)
        assertEquals(14.0, s.kmplRecent!!, 0.1)
        assertTrue(s.legs.last().toOdo == odoBrim)
        assertFalse(s.legs.last().exact)
        assertTrue(last.odoKm < odoBrim)
    }
}
