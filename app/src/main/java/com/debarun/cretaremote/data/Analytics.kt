package com.debarun.cretaremote.data

import com.debarun.cretaremote.bluelink.CarState
import kotlin.math.max

/**
 * Everything derived from stored history plus the live reading.
 * Economy and cost come from the driver's fill-up ledger ([FuelLedger]); the fuel gauge is only used for "what is in the tank now".
 */
data class FuelInsights(
    val litresLeft: Double? = null,        // gauge % x tank size (a coarse reading)
    val kmplCar: Double? = null,           // car's own implied figure: DTE / litres left
    val kmplUsed: Double? = null,
    val usedSource: String = "",
    val ledger: LedgerStats = LedgerStats(),
    val predictedRangeKm: Double? = null,
    val avgDailyKm: Double? = null,
    val daysLeft: Double? = null,
    val emptyAt: Long? = null,
    val refuelBy: Long? = null,
    val costPerKm: Double? = null,
    val costPerDay: Double? = null,
    val dailyKm: List<Pair<String, Double>> = emptyList(), // yyyyMMdd -> km, last 14 days
    val fallbackPrice: Double = 0.0,
    val kmToLimit: Double? = null,         // km until the tank reaches the fuel-alert limit
    val fillAtOdo: Double? = null,         // the odometer reading at which that happens
) {
    /** One economy and one price for every cost on every screen. [ts] = when the distance was driven (null = now). */
    fun tripCost(km: Double, ts: Long? = null): TripCost? = FuelLedger.costOf(km, ts, ledger, fallbackPrice)
}

object Analytics {
    private const val DAY_MS = 86_400_000L

    private fun recentSource(l: LedgerStats): String {
        val n = l.recentLegs
        val plural = if (n == 1) "" else "es"
        return when {
            l.recentExact == n -> "your last $n full-tank stretch$plural"
            l.recentExact == 0 -> "your last $n stretch$plural, measured with the fuel gauge between fill-ups"
            else -> "your last $n stretch$plural, ${n - l.recentExact} of them measured with the fuel gauge"
        }
    }

    fun compute(snaps: List<Snapshot>, car: CarState?, s: Settings, tripDays: List<DayRow>, ledger: LedgerStats, now: Long = System.currentTimeMillis()): FuelInsights {
        val usable = snaps.filter { it.odo != null }.sortedBy { it.ts }

        val litresLeft = car?.fuelPct?.let { it / 100.0 * s.tankLitres }
        val carImplied = if (litresLeft != null && litresLeft > 3.0 && (car?.dteKm ?: 0.0) > 0.0) car!!.dteKm!! / litresLeft else null

        val (used, source) = when {
            ledger.kmplRecent != null -> ledger.kmplRecent to recentSource(ledger)
            ledger.provisionalKmpl != null -> ledger.provisionalKmpl to "a rough estimate since your last fill-up. Keep logging fill-ups for a measured figure"
            carImplied != null -> carImplied to "the car's own estimate. Log fill-ups for real figures"
            else -> null to ""
        }

        // ---- daily distance, last 14 days
        val fmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val today = fmt.format(java.util.Date(now))
        val days = (13 downTo 0).map { fmt.format(java.util.Date(now - it * DAY_MS)) }
        val byDay = tripDays.associate { it.day to it.dist }.toMutableMap()
        if (byDay.isEmpty() && usable.size >= 2) {
            // fall back to odometer deltas when the trip endpoint gave nothing
            for (i in 1 until usable.size) {
                val d = fmt.format(java.util.Date(usable[i].ts))
                val delta = usable[i].odo!! - usable[i - 1].odo!!
                if (delta > 0 && delta < 1500) byDay[d] = (byDay[d] ?: 0.0) + delta
            }
        }
        val daily = days.map { it to (byDay[it] ?: 0.0) }
        val tracked = daily.filter { it.first != today }
        val avgDaily = tracked.sumOf { it.second }.let { total -> if (total > 0.0) total / tracked.size else null } ?: ledger.kmPerDay

        // ---- prediction (gauge litres x logged economy; always an estimate)
        val predictedRange = if (litresLeft != null && used != null) litresLeft * used else null
        val paceOk = predictedRange != null && avgDaily != null && avgDaily > 0.5
        val daysLeft = if (paceOk) predictedRange!! / avgDaily!! else null
        val emptyAt = daysLeft?.let { now + (it * DAY_MS).toLong() }
        // "fill up" = the tank reaching the driver's own fuel-alert limit, not an arbitrary reserve
        val kmToLimit = if (litresLeft != null && used != null) max(0.0, (litresLeft - s.fuelLimitL) * used) else null
        val fillAtOdo = kmToLimit?.let { k -> car?.odometerKm?.plus(k) }
        val refuelBy = if (kmToLimit != null && avgDaily != null && avgDaily > 0.5) now + (kmToLimit / avgDaily * DAY_MS).toLong() else null

        // ---- cost: what you actually paid per km, else latest price over economy
        val price = ledger.priceLast ?: s.fuelPricePerL.takeIf { it > 0 }
        val costPerKm = ledger.costPerKm ?: if (price != null && used != null && used > 0) price / used else null
        val costPerDay = ledger.costPerDay ?: if (costPerKm != null && avgDaily != null) costPerKm * avgDaily else null

        return FuelInsights(
            litresLeft = litresLeft,
            kmplCar = carImplied,
            kmplUsed = used,
            usedSource = source,
            ledger = ledger,
            predictedRangeKm = predictedRange,
            avgDailyKm = avgDaily,
            daysLeft = daysLeft,
            emptyAt = emptyAt,
            refuelBy = refuelBy,
            costPerKm = costPerKm,
            costPerDay = costPerDay,
            dailyKm = daily,
            fallbackPrice = s.fuelPricePerL,
            kmToLimit = kmToLimit,
            fillAtOdo = fillAtOdo,
        )
    }
}
