package com.debarun.cretaremote.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One fill-up as the driver logs it.
 * [full] = the tank was filled to the brim (pump auto-cut). Partial top-ups leave it false.
 * [pricePerL] = 0 when unknown.
 */
data class Fill(val ts: Long, val odoKm: Double, val litres: Double, val pricePerL: Double, val full: Boolean) {
    val amount: Double get() = litres * pricePerL
}

/** One fuel-gauge reading from a parked car: odometer and litres in the tank (gauge % x tank size). */
data class GaugePoint(val ts: Long, val odoKm: Double, val litres: Double)

/**
 * The fuel burnt over one stretch of driving.
 * Between two brim-full fill-ups ([exact]) everything poured in after the first one, partial top-ups included, up to and including the second
 * is exactly what the car burnt, so km / litres is the real economy.
 * Otherwise a stretch runs from one fuel-gauge reading to another and the litres burnt are: litres at the start + litres poured in between - litres at the end.
 * That needs no brim-full fill-up, but it is only as good as the gauge, so [errL] is the assumed error in litres.
 */
data class Leg(
    val fromTs: Long,
    val toTs: Long,
    val fromOdo: Double,
    val toOdo: Double,
    val litres: Double,
    val cost: Double,
    val fills: Int,
    val valid: Boolean,
    val note: String,
    val exact: Boolean = true,
    val errL: Double = PUMP_ERR_L,
) {
    val km: Double get() = toOdo - fromOdo
    val kmpl: Double get() = if (litres > 0) km / litres else 0.0
    val days: Double get() = max(0.0, (toTs - fromTs) / 86_400_000.0)
}

internal const val PUMP_ERR_L = 0.7

/** Fuel and money for some distance, using one economy and one price so every screen agrees. [measured] = economy came from real full-tank stretches. */
data class TripCost(val litres: Double, val cost: Double?, val kmpl: Double, val price: Double?, val measured: Boolean)

data class TripQuote(val km: Double, val litres: Double, val cost: Double?, val costLow: Double?, val costHigh: Double?)

data class LedgerStats(
    val fills: List<Fill> = emptyList(),          // oldest first
    val legs: List<Leg> = emptyList(),            // oldest first, valid and flagged
    val kmplAll: Double? = null,                  // distance-weighted over every valid leg
    val kmplRecent: Double? = null,               // last RECENT_LEGS valid legs pooled
    val recentLegs: Int = 0,
    val recentExact: Int = 0,                     // how many of those were brim-to-brim; the rest were measured with the gauge
    val errPct: Double? = null,                   // assumed error of the recent figure, in percent
    val kmplBest: Double? = null,
    val kmplWorst: Double? = null,
    val index: Int? = null,                       // 100 = your own average, recent vs everything before it
    val litresTotal: Double = 0.0,
    val spendTotal: Double = 0.0,
    val priceAvg: Double? = null,                 // weighted by litres, over priced fill-ups
    val priceLast: Double? = null,
    val priceIsFallback: Boolean = false,
    val costPerKm: Double? = null,                // actual: spend over valid legs / their km
    val costPerKmNow: Double? = null,             // latest price / recent economy
    val kmPerDay: Double? = null,
    val costPerDay: Double? = null,
    val costPerMonth: Double? = null,
    val openKm: Double? = null,                   // driven since the last full fill-up
    val openLitresAdded: Double = 0.0,            // partial top-ups since the last full fill-up
    val estLitres: Double? = null,                // ledger-based estimate of what is in the tank now
    val provisionalKmpl: Double? = null,          // rough economy since the last brim-full fill, from the gauge, until a real stretch exists
    val provisionalLitres: Double? = null,        // litres burnt since that fill by the same estimate
    val hint: String? = null,                     // why numbers are missing
) {
    val usable: Boolean get() = kmplRecent != null
    /** Real economy when there is one, otherwise the rough gauge-based figure. */
    val kmplNow: Double? get() = kmplRecent ?: provisionalKmpl
}

object FuelLedger {
    const val MIN_LEG_KM = 40.0
    const val MIN_KMPL = 4.0
    const val MAX_KMPL = 30.0
    const val RECENT_LEGS = 3
    private const val OUTLIER_HIGH = 1.5   // x median
    private const val OUTLIER_LOW = 0.55
    const val MIN_GAUGE_KM = 250.0          // a gauge-to-gauge stretch needs this much driving, so the gauge error is small next to the fuel burnt
    private const val GAUGE_ERR_L = 1.5     // assumed error of one gauge reading
    private const val GAUGE_LOW = 0.08      // readings near empty or near full are not linear, so they are not used as a reference
    private const val GAUGE_HIGH = 0.92
    private const val NEAR_FILL_KM = 3.0    // a reading this close to a fill-up may be from before or after pouring
    private const val RISE_L = 4.0          // a gauge rise this big with no logged fill-up means an unlogged refuel

    /** Returns an error message, or null when the entry is sane. [existing] must not contain the entry being edited. */
    fun validate(existing: List<Fill>, f: Fill, tankL: Double, now: Long = System.currentTimeMillis()): String? {
        if (f.litres <= 0.0) return "Enter the litres you filled."
        if (f.litres > tankL + 0.01) return "That is more than the ${tankL.toInt()} L tank. Check the litres, or change the tank size in Settings."
        if (f.odoKm <= 0.0) return "Enter the odometer reading at the pump."
        if (f.pricePerL !in 0.0..500.0) return "Price per litre looks wrong."
        if (f.ts > now + 3_600_000L) return "That date is in the future."
        val before = existing.filter { it.ts < f.ts }.maxByOrNull { it.ts }
        val after = existing.filter { it.ts > f.ts }.minByOrNull { it.ts }
        if (before != null && f.odoKm < before.odoKm) return "Odometer is lower than your earlier fill-up (${before.odoKm.toLong()} km)."
        if (after != null && f.odoKm > after.odoKm) return "Odometer is higher than your later fill-up (${after.odoKm.toLong()} km)."
        return null
    }

    /** Builds legs between consecutive full fill-ups. Fills are ordered by odometer then time, so a mistyped date cannot reorder the car's history. */
    fun legs(fills: List<Fill>, fallbackPrice: Double, gauge: List<GaugePoint> = emptyList(), tankL: Double = 0.0, now: GaugePoint? = null): List<Leg> {
        val sorted = fills.sortedWith(compareBy({ it.odoKm }, { it.ts }))
        val fullIdx = sorted.indices.filter { sorted[it].full }
        val out = mutableListOf<Leg>()
        for (k in 1 until fullIdx.size) {
            val a = fullIdx[k - 1]
            val b = fullIdx[k]
            val inside = sorted.subList(a + 1, b + 1)
            val litres = inside.sumOf { it.litres }
            val cost = inside.sumOf { it.litres * (if (it.pricePerL > 0) it.pricePerL else fallbackPrice) }
            val km = sorted[b].odoKm - sorted[a].odoKm
            val kmpl = if (litres > 0) km / litres else 0.0
            val note = when {
                km < MIN_LEG_KM -> "Too short to measure (under ${MIN_LEG_KM.toInt()} km)"
                kmpl !in MIN_KMPL..MAX_KMPL -> "Looks wrong. A fill-up may be missing or mistyped"
                else -> ""
            }
            out += Leg(sorted[a].ts, sorted[b].ts, sorted[a].odoKm, sorted[b].odoKm, litres, cost, inside.size, note.isEmpty(), note)
        }
        // stretches that are not bracketed by two brim-full fills are measured from the fuel gauge
        if (tankL > 0 && sorted.isNotEmpty()) out += gaugeLegs(sorted, gauge, now, tankL, fallbackPrice)
        out.sortWith(compareBy({ it.fromOdo }, { it.toOdo }))
        // drop statistical outliers (e.g. a forgotten fill makes one leg look far too good and its neighbour far too bad)
        val ok = out.filter { it.valid }
        if (ok.size >= 3) {
            val med = ok.map { it.kmpl }.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
            return out.map {
                when {
                    !it.valid -> it
                    it.kmpl > med * OUTLIER_HIGH -> it.copy(valid = false, note = "Far better than your usual. A fill-up may be missing")
                    it.kmpl < med * OUTLIER_LOW -> it.copy(valid = false, note = "Far worse than your usual. Check the entries")
                    else -> it
                }
            }
        }
        return out
    }

    private class Anchor(val ts: Long, val odo: Double, val level: Double, val pos: Int, val brim: Boolean)

    /**
     * Places where the tank level is known: every brim-full fill (level = tank, after pouring) and every steady gauge reading.
     * [Anchor.pos] = index of the last fill-up whose litres are already in that level.
     */
    private fun anchorsOf(sorted: List<Fill>, gauge: List<GaugePoint>, now: GaugePoint?, tankL: Double): List<Anchor> {
        val firstOdo = sorted.first().odoKm
        val lo = tankL * GAUGE_LOW
        val hi = tankL * GAUGE_HIGH
        val out = mutableListOf<Anchor>()
        sorted.forEachIndexed { i, f -> if (f.full) out += Anchor(f.ts, f.odoKm, tankL, i, true) }
        val pts = (gauge + listOfNotNull(now))
            .filter { p -> p.odoKm >= firstOdo && p.litres in lo..hi && sorted.none { abs(it.odoKm - p.odoKm) < NEAR_FILL_KM } }
            .sortedWith(compareBy({ it.odoKm }, { it.ts }))
        // readings taken at one parked spot become one anchor at their median, which averages out the gauge's wobble
        var k = 0
        while (k < pts.size) {
            var e = k + 1
            while (e < pts.size && pts[e].odoKm - pts[k].odoKm <= 1.0 && abs(pts[e].litres - pts[e - 1].litres) < RISE_L) e++
            val grp = pts.subList(k, e)
            val level = grp.map { it.litres }.sorted()[grp.size / 2]
            val last = grp.last()
            out += Anchor(last.ts, last.odoKm, level, sorted.indexOfLast { it.odoKm < last.odoKm }, false)
            k = e
        }
        return out.sortedWith(compareBy({ it.odo }, { it.ts }))
    }

    /**
     * Gauge-bridged stretches. Walk the anchors in odometer order; brim-to-brim is left to [legs]. Otherwise a stretch ends at the first
     * anchor at least [MIN_GAUGE_KM] further on. Ending on distance, not on litres, keeps a lucky gauge reading from choosing its own end.
     * A gauge rise that no logged fill-up explains means an unlogged refuel, so the walk starts again after it.
     */
    private fun gaugeLegs(sorted: List<Fill>, gauge: List<GaugePoint>, now: GaugePoint?, tankL: Double, fallbackPrice: Double): List<Leg> {
        val an = anchorsOf(sorted, gauge, now, tankL)
        val out = mutableListOf<Leg>()
        fun poured(a: Anchor, b: Anchor): List<Fill> = if (b.pos > a.pos) sorted.subList(a.pos + 1, b.pos + 1) else emptyList()
        var i = 0
        while (i < an.size - 1) {
            val a = an[i]
            if (a.brim) {
                val nextBrim = (i + 1 until an.size).firstOrNull { an[it].brim }
                if (nextBrim != null) { i = nextBrim; continue }
            }
            var prev = a
            var end = -1
            var restart = -1
            var j = i + 1
            while (j < an.size) {
                val b = an[j]
                val unlogged = b.level - prev.level >= RISE_L && sorted.none { it.odoKm >= prev.odo - NEAR_FILL_KM && it.odoKm <= b.odo + NEAR_FILL_KM }
                if (unlogged) { restart = j; break }
                if (b.odo - a.odo >= MIN_GAUGE_KM) { end = j; break }
                if (b.brim) { restart = j; break }   // too short to measure, but a brim-full fill is a better place to start from
                prev = b
                j++
            }
            when {
                end >= 0 -> {
                    val b = an[end]
                    val inside = poured(a, b)
                    val burnt = a.level + inside.sumOf { it.litres } - b.level
                    val priced = inside.filter { it.pricePerL > 0 }
                    val pricedL = priced.sumOf { it.litres }
                    val price = if (pricedL > 0) priced.sumOf { it.amount } / pricedL else fallbackPrice
                    val kmpl = if (burnt > 0) (b.odo - a.odo) / burnt else 0.0
                    val note = if (kmpl !in MIN_KMPL..MAX_KMPL) "Looks wrong. A fill-up may be missing or mistyped" else ""
                    val ea = if (a.brim) PUMP_ERR_L else GAUGE_ERR_L
                    val eb = if (b.brim) PUMP_ERR_L else GAUGE_ERR_L
                    val litres = max(0.0, burnt)
                    out += Leg(a.ts, b.ts, a.odo, b.odo, litres, litres * price, inside.size, note.isEmpty(), note, exact = false, errL = sqrt(ea * ea + eb * eb))
                    i = end
                }
                restart >= 0 -> i = restart
                else -> break
            }
        }
        return out
    }

    fun stats(fills: List<Fill>, currentOdo: Double?, tankL: Double, settingsPrice: Double, gaugeLitres: Double? = null, gauge: List<GaugePoint> = emptyList(), nowTs: Long = System.currentTimeMillis()): LedgerStats {
        val sorted = fills.sortedWith(compareBy({ it.odoKm }, { it.ts }))
        if (sorted.isEmpty()) return LedgerStats(hint = "Log your fill-ups, full or partial. The app reads the fuel gauge between them to measure economy. A brim-full fill gives the most exact reading.")

        val priced = sorted.filter { it.pricePerL > 0 }
        val pricedLitres = priced.sumOf { it.litres }
        val priceAvg = if (pricedLitres > 0) priced.sumOf { it.amount } / pricedLitres else null
        val fallback = priceAvg ?: settingsPrice
        val byTime = sorted.sortedBy { it.ts }
        val lastPriced = byTime.lastOrNull { it.pricePerL > 0 }?.pricePerL
        val priceLast = lastPriced ?: settingsPrice.takeIf { it > 0 }
        val spend = sorted.sumOf { it.litres * (if (it.pricePerL > 0) it.pricePerL else fallback) }

        val nowPoint = if (currentOdo != null && gaugeLitres != null) GaugePoint(nowTs, currentOdo, gaugeLitres) else null
        val legs = legs(sorted, fallback, gauge, tankL, nowPoint)
        val ok = legs.filter { it.valid }

        fun pooled(l: List<Leg>): Double? = l.sumOf { it.litres }.takeIf { it > 0 }?.let { l.sumOf { x -> x.km } / it }

        val all = pooled(ok)
        val recentList = ok.takeLast(RECENT_LEGS)
        val recent = pooled(recentList)
        val errPct = recentList.sumOf { it.litres }.takeIf { it > 0 }?.let { l -> sqrt(recentList.sumOf { it.errL * it.errL }) / l * 100.0 }
        val spread = ok.filter { it.exact }.ifEmpty { ok }
        val index = if (ok.size >= 3) {
            val head = pooled(ok.dropLast(2))
            val tail = pooled(ok.takeLast(2))
            if (head != null && tail != null && head > 0) (tail / head * 100.0).roundToInt() else null
        } else null

        val km = ok.sumOf { it.km }
        val days = ok.sumOf { it.days }
        val legCost = ok.sumOf { it.cost }
        val costPerKm = if (km > 0) legCost / km else null
        val kmPerDay = if (days >= 1.0) km / days else null
        val costPerDay = if (days >= 1.0) legCost / days else null

        val lastFullIdx = sorted.indexOfLast { it.full }
        var openKm: Double? = null
        var openLitres = 0.0
        var est: Double? = null
        if (lastFullIdx >= 0) {
            val base = sorted[lastFullIdx]
            openLitres = sorted.drop(lastFullIdx + 1).sumOf { it.litres }
            if (currentOdo != null && currentOdo >= base.odoKm) {
                openKm = currentOdo - base.odoKm
                if (recent != null) est = min(tankL, max(0.0, tankL - openKm / recent + openLitres))
            }
        }

        // Until two brim-full fills exist there is no measured stretch. Give a clearly rough figure so the page is not empty:
        // the tank was full at the last brim fill, so litres burnt = tank + top-ups since - what the gauge shows now.
        var provKmpl: Double? = null
        var provLitres: Double? = null
        if (recent == null && openKm != null && openKm >= 50.0 && gaugeLitres != null) {
            val burnt = tankL + openLitres - gaugeLitres
            if (burnt >= 3.0) {
                val e = openKm / burnt
                if (e in MIN_KMPL..MAX_KMPL) { provKmpl = e; provLitres = burnt }
            }
        }

        val fullCount = sorted.count { it.full }
        val hint = when {
            recent != null && ok.isEmpty().not() -> if (legs.size > ok.size) "Some stretches are left out of the averages because they look wrong." else null
            legs.isNotEmpty() -> "The stretches measured so far look wrong (too short or implausible). Check the fill-up entries and odometer."
            fullCount == 0 -> "Keep logging fill-ups, full or partial. Economy appears after about ${MIN_GAUGE_KM.toInt()} km of driving, measured with the fuel gauge."
            else -> "Keep driving and log your next fill-up. Economy appears after about ${MIN_GAUGE_KM.toInt()} km, or sooner at your next brim-full fill."
        }

        return LedgerStats(
            fills = sorted, legs = legs,
            kmplAll = all, kmplRecent = recent, recentLegs = recentList.size, recentExact = recentList.count { it.exact }, errPct = errPct,
            kmplBest = spread.maxOfOrNull { it.kmpl }, kmplWorst = spread.minOfOrNull { it.kmpl },
            index = index,
            litresTotal = sorted.sumOf { it.litres }, spendTotal = spend,
            priceAvg = priceAvg, priceLast = priceLast, priceIsFallback = lastPriced == null && priceLast != null,
            costPerKm = costPerKm,
            costPerKmNow = (recent ?: provKmpl)?.let { e -> priceLast?.let { it / e } },
            kmPerDay = kmPerDay, costPerDay = costPerDay, costPerMonth = costPerDay?.let { it * 30.4 },
            openKm = openKm, openLitresAdded = openLitres, estLitres = est,
            provisionalKmpl = provKmpl, provisionalLitres = provLitres,
            hint = hint,
        )
    }

    /**
     * The economy that applied at [ts] (null = now): the measured stretch that contains that moment,
     * the pooled average for days before the first measured stretch, and the latest figure for days since the last brim-full fill.
     */
    fun economyAt(ts: Long?, s: LedgerStats): Pair<Double, Boolean>? {
        val now = s.kmplNow
        if (ts == null) return now?.let { it to s.usable }
        s.legs.firstOrNull { it.valid && ts >= it.fromTs && ts <= it.toTs }?.let { return it.kmpl to true }
        val lastFull = s.legs.lastOrNull()?.toTs ?: s.fills.lastOrNull { it.full }?.ts
        if (lastFull != null && ts > lastFull) return now?.let { it to s.usable }
        s.kmplAll?.let { return it to true }
        return now?.let { it to s.usable }
    }

    /** The petrol price in force at [ts] (null = now): the latest priced fill on or before that day, else the earliest known price. */
    fun priceAt(ts: Long?, s: LedgerStats, settingsPrice: Double): Double? {
        val priced = s.fills.filter { it.pricePerL > 0 }.sortedBy { it.ts }
        val fallback = settingsPrice.takeIf { it > 0 }
        if (priced.isEmpty()) return fallback
        if (ts == null) return s.priceLast ?: fallback
        return (priced.lastOrNull { it.ts <= ts + 86_400_000L } ?: priced.first()).pricePerL
    }

    fun costOf(km: Double, ts: Long?, s: LedgerStats, settingsPrice: Double): TripCost? {
        if (km <= 0) return null
        val (e, measured) = economyAt(ts, s) ?: return null
        val litres = km / e
        val p = priceAt(ts, s, settingsPrice)
        return TripCost(litres, p?.let { litres * it }, e, p, measured)
    }

    /** Fuel and money for a drive of [km], from recent economy at the latest price. Range uses your best and worst recent stretch. */
    fun quote(km: Double, s: LedgerStats): TripQuote? {
        val e = s.kmplNow ?: return null
        if (km <= 0) return null
        val recent = s.legs.filter { it.valid }.takeLast(5)
        val best = recent.maxOfOrNull { it.kmpl } ?: e
        val worst = recent.minOfOrNull { it.kmpl } ?: e
        val p = s.priceLast
        val litres = km / e
        return TripQuote(km, litres, p?.let { litres * it }, p?.let { km / best * it }, p?.let { km / worst * it })
    }
}
