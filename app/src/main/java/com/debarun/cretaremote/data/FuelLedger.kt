package com.debarun.cretaremote.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One fill-up as the driver logs it.
 * [full] = the tank was filled to the brim (pump auto-cut). Partial top-ups leave it false.
 * [pricePerL] = 0 when unknown.
 */
data class Fill(val ts: Long, val odoKm: Double, val litres: Double, val pricePerL: Double, val full: Boolean) {
    val amount: Double get() = litres * pricePerL
}

/**
 * The fuel burnt between two consecutive FULL fill-ups.
 * Everything poured in after the first full fill-up (partial top-ups included) up to and including the second full fill-up
 * is exactly what the car burnt over that distance, so km / litres is the real economy of that stretch.
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
) {
    val km: Double get() = toOdo - fromOdo
    val kmpl: Double get() = if (litres > 0) km / litres else 0.0
    val days: Double get() = max(0.0, (toTs - fromTs) / 86_400_000.0)
}

/** Fuel and money for some distance, using one economy and one price so every screen agrees. [measured] = economy came from real full-tank stretches. */
data class TripCost(val litres: Double, val cost: Double?, val kmpl: Double, val price: Double?, val measured: Boolean)

data class TripQuote(val km: Double, val litres: Double, val cost: Double?, val costLow: Double?, val costHigh: Double?)

data class LedgerStats(
    val fills: List<Fill> = emptyList(),          // oldest first
    val legs: List<Leg> = emptyList(),            // oldest first, valid and flagged
    val kmplAll: Double? = null,                  // distance-weighted over every valid leg
    val kmplRecent: Double? = null,               // last RECENT_LEGS valid legs pooled
    val recentLegs: Int = 0,
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
    fun legs(fills: List<Fill>, fallbackPrice: Double): List<Leg> {
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

    fun stats(fills: List<Fill>, currentOdo: Double?, tankL: Double, settingsPrice: Double, gaugeLitres: Double? = null): LedgerStats {
        val sorted = fills.sortedWith(compareBy({ it.odoKm }, { it.ts }))
        if (sorted.isEmpty()) return LedgerStats(hint = "Log your next fill-up. Fill to the brim each time you can, and the app works out real economy.")

        val priced = sorted.filter { it.pricePerL > 0 }
        val pricedLitres = priced.sumOf { it.litres }
        val priceAvg = if (pricedLitres > 0) priced.sumOf { it.amount } / pricedLitres else null
        val fallback = priceAvg ?: settingsPrice
        val byTime = sorted.sortedBy { it.ts }
        val lastPriced = byTime.lastOrNull { it.pricePerL > 0 }?.pricePerL
        val priceLast = lastPriced ?: settingsPrice.takeIf { it > 0 }
        val spend = sorted.sumOf { it.litres * (if (it.pricePerL > 0) it.pricePerL else fallback) }

        val legs = legs(sorted, fallback)
        val ok = legs.filter { it.valid }

        fun pooled(l: List<Leg>): Double? = l.sumOf { it.litres }.takeIf { it > 0 }?.let { l.sumOf { x -> x.km } / it }

        val all = pooled(ok)
        val recentList = ok.takeLast(RECENT_LEGS)
        val recent = pooled(recentList)
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
            fullCount == 0 -> "Log one fill-up filled to the brim. It becomes the baseline."
            fullCount == 1 -> "Fill to the brim once more. Then the litres you add measure your real economy."
            else -> "Stretches between full tanks look wrong (too short or implausible). Check the entries."
        }

        return LedgerStats(
            fills = sorted, legs = legs,
            kmplAll = all, kmplRecent = recent, recentLegs = recentList.size,
            kmplBest = ok.maxOfOrNull { it.kmpl }, kmplWorst = ok.minOfOrNull { it.kmpl },
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
