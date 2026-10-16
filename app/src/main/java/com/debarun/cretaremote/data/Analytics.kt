package com.debarun.cretaremote.data

import com.debarun.cretaremote.bluelink.CarState
import kotlin.math.max

data class Refuel(val ts: Long, val odoKm: Double?, val litres: Double, val fromPct: Double, val toPct: Double)
data class Cycle(val startTs: Long, val endTs: Long, val km: Double, val litres: Double) {
    val kmpl: Double get() = km / litres
}

/** Everything derived from the stored history plus the live reading. All fuel maths assume the tank size in [Settings]. */
data class FuelInsights(
    val litresLeft: Double? = null,
    val kmplLifetime: Double? = null,
    val kmplRecent: Double? = null,
    val kmplCar: Double? = null,           // car's own implied figure: DTE / litres left
    val kmplUsed: Double? = null,
    val usedSource: String = "",
    val cycles: List<Cycle> = emptyList(),
    val refuels: List<Refuel> = emptyList(),
    val predictedRangeKm: Double? = null,
    val avgDailyKm: Double? = null,
    val daysLeft: Double? = null,
    val emptyAt: Long? = null,
    val refuelBy: Long? = null,
    val costPerKm: Double? = null,
    val costPerDay: Double? = null,
    val dailyKm: List<Pair<String, Double>> = emptyList(), // yyyyMMdd -> km, last 14 days
)

object Analytics {
    private const val REFUEL_JUMP_PCT = 8.0
    private const val RESERVE_KM = 50.0
    private const val DAY_MS = 86_400_000L

    fun compute(snaps: List<Snapshot>, car: CarState?, s: Settings, tripDays: List<DayRow>, now: Long = System.currentTimeMillis()): FuelInsights {
        val usable = snaps.filter { it.fuel != null && it.odo != null }.sortedBy { it.ts }

        // ---- refuel detection + tank cycles
        val refuels = mutableListOf<Refuel>()
        val cycles = mutableListOf<Cycle>()
        var start: Snapshot? = null
        var last: Snapshot? = null
        fun closeCycle() {
            val a = start; val b = last
            if (a != null && b != null) {
                val drop = a.fuel!! - b.fuel!!
                val km = b.odo!! - a.odo!!
                if (drop >= 12.0 && km >= 30.0) cycles += Cycle(a.ts, b.ts, km, drop / 100.0 * s.tankLitres)
            }
        }
        for (cur in usable) {
            val prev = last
            if (prev == null) {
                start = cur
            } else if (cur.fuel!! - prev.fuel!! >= REFUEL_JUMP_PCT) {
                closeCycle()
                refuels += Refuel(cur.ts, cur.odo, (cur.fuel - prev.fuel) / 100.0 * s.tankLitres, prev.fuel, cur.fuel)
                start = cur
            }
            last = cur
        }
        // ongoing cycle counts too (the app already knows these km and litres were really used)
        closeCycle()

        val lifetime = if (cycles.isNotEmpty()) cycles.sumOf { it.km } / cycles.sumOf { it.litres } else null
        val recentCycles = cycles.takeLast(2)
        val recent = if (recentCycles.isNotEmpty()) recentCycles.sumOf { it.km } / recentCycles.sumOf { it.litres } else null

        val litresLeft = car?.fuelPct?.let { it / 100.0 * s.tankLitres }
        val carImplied = if (litresLeft != null && litresLeft > 3.0 && (car?.dteKm ?: 0.0) > 0.0) car!!.dteKm!! / litresLeft else null

        val (used, source) = when {
            recent != null -> recent to "your last fill-ups"
            lifetime != null -> lifetime to "your history"
            carImplied != null -> carImplied to "car's own estimate"
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
        val avgDaily = tracked.sumOf { it.second }.let { total -> if (total > 0.0) total / tracked.size else null }

        // ---- prediction
        val predictedRange = if (litresLeft != null && used != null) litresLeft * used else null
        val daysLeft = if (predictedRange != null && avgDaily != null && avgDaily > 0.5) predictedRange / avgDaily else null
        val emptyAt = daysLeft?.let { now + (it * DAY_MS).toLong() }
        val refuelBy = if (predictedRange != null && avgDaily != null && avgDaily > 0.5) now + (max(0.0, predictedRange - RESERVE_KM) / avgDaily * DAY_MS).toLong() else null

        val costPerKm = if (s.fuelPricePerL > 0 && used != null && used > 0) s.fuelPricePerL / used else null
        val costPerDay = if (costPerKm != null && avgDaily != null) costPerKm * avgDaily else null

        return FuelInsights(
            litresLeft = litresLeft,
            kmplLifetime = lifetime,
            kmplRecent = recent,
            kmplCar = carImplied,
            kmplUsed = used,
            usedSource = source,
            cycles = cycles,
            refuels = refuels.asReversed(),
            predictedRangeKm = predictedRange,
            avgDailyKm = avgDaily,
            daysLeft = daysLeft,
            emptyAt = emptyAt,
            refuelBy = refuelBy,
            costPerKm = costPerKm,
            costPerDay = costPerDay,
            dailyKm = daily,
        )
    }
}
