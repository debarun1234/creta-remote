package com.debarun.cretaremote.data

import com.debarun.cretaremote.bluelink.CarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalyticsTest {
    private val now = 1_792_000_000_000L

    @Test
    fun measuredStretchesDriveTheEconomyUsed() {
        val ledger = LedgerStats(kmplRecent = 14.0, recentLegs = 2, recentExact = 2)
        val car = CarState(fuelPct = 60.0, dteKm = 420.0, odometerKm = 20_000.0)
        val ins = Analytics.compute(emptyList(), car, Settings(tankLitres = 50.0), emptyList(), ledger, now)
        assertEquals(14.0, ins.kmplUsed!!, 0.001)
        assertEquals("your last 2 full-tank stretches", ins.usedSource)
        assertEquals(30.0 * 14.0, ins.predictedRangeKm!!, 0.001)
    }

    @Test
    fun gaugeMeasuredStretchesAreLabelledAsSuch() {
        val ledger = LedgerStats(kmplRecent = 13.5, recentLegs = 3, recentExact = 0)
        val ins = Analytics.compute(emptyList(), CarState(fuelPct = 60.0), Settings(tankLitres = 50.0), emptyList(), ledger, now)
        assertEquals("your last 3 stretches, measured with the fuel gauge between fill-ups", ins.usedSource)
    }

    @Test
    fun noHistoryFallsBackToCarEstimateAndNeverInventsADailyAverage() {
        val car = CarState(fuelPct = 60.0, dteKm = 420.0)
        val ins = Analytics.compute(emptyList(), car, Settings(tankLitres = 50.0), emptyList(), LedgerStats(), now)
        assertEquals("the car's own estimate. Log fill-ups for real figures", ins.usedSource)
        assertEquals(14.0, ins.kmplUsed!!, 0.01)
        assertNull(ins.avgDailyKm)
        assertNull(ins.daysLeft)
    }
}
