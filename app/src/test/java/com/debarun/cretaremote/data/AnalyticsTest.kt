package com.debarun.cretaremote.data

import com.debarun.cretaremote.bluelink.CarState
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AnalyticsTest {
    private val day = 86_400_000L
    private val now = 1_792_000_000_000L

    /** 20 days at 35 km/day in a car doing exactly 14 km/L with a 50 L tank, refuelled when low. */
    private fun history(): List<Snapshot> {
        val out = mutableListOf<Snapshot>()
        var odo = 20_000.0
        var fuel = 95.0
        var t = now - 20 * day
        repeat(20) {
            repeat(4) {
                val km = 35.0 / 4
                odo += km
                fuel -= km / 14.0 / 50.0 * 100.0
                t += day / 4
                if (fuel < 25.0) fuel = 92.0
                out += Snapshot(t, odo, fuel.roundToLong().toDouble(), null, locked = true, engine = false)
            }
        }
        return out
    }

    @Test
    fun economyIsRecoveredFromHistory() {
        val snaps = history()
        val car = CarState(fuelPct = snaps.last().fuel, dteKm = 300.0, odometerKm = snaps.last().odo)
        val ins = Analytics.compute(snaps, car, Settings(tankLitres = 50.0), emptyList(), now)
        assertEquals(1, ins.refuels.size)
        assertNotNull(ins.kmplUsed)
        assertEquals(14.0, ins.kmplUsed!!, 0.6)
    }

    @Test
    fun noHistoryFallsBackToCarEstimateAndNeverInventsADailyAverage() {
        val car = CarState(fuelPct = 60.0, dteKm = 420.0)
        val ins = Analytics.compute(emptyList(), car, Settings(tankLitres = 50.0), emptyList(), now)
        assertEquals("car's own estimate", ins.usedSource)
        assertEquals(14.0, ins.kmplUsed!!, 0.01)
        assertNull(ins.avgDailyKm)
        assertNull(ins.daysLeft)
    }
}
