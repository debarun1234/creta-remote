package com.debarun.cretaremote.bluelink

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A car registered on the Bluelink account. */
data class Vehicle(
    val id: String,
    val name: String,
    val model: String,
    val vin: String,
    val regDate: String,
    val ccs2: Int,
)

/** Authenticated session: bearer token + the push-registration device id Bluelink binds commands to. */
data class Session(
    val accessToken: String,
    val deviceId: String,
    val issuedAt: Long,
    val controlToken: String? = null,
    val controlExpiry: Long = 0L,
)

data class Quad(val fl: Boolean = false, val fr: Boolean = false, val rl: Boolean = false, val rr: Boolean = false) {
    val any get() = fl || fr || rl || rr
}

/** Short text for widgets and chips: "34 · 34 · 33 · 33 psi", "Low: FL RR", "Check pressure", "OK" or "Not reported". */
fun CarState.tyreSummary(): String {
    val low = listOfNotNull("FL".takeIf { tyreWarn.fl }, "FR".takeIf { tyreWarn.fr }, "RL".takeIf { tyreWarn.rl }, "RR".takeIf { tyreWarn.rr })
    return when {
        !tyreReported -> "Not reported"
        low.isNotEmpty() -> "Low: " + low.joinToString(" ")
        tyreWarnAny -> "Check pressure"
        tyrePsi.all { it != null } -> tyrePsi.joinToString(" · ") { "${it!!.toInt()}" } + " psi"
        else -> "OK"
    }
}

val CarState.tyreAttention: Boolean get() = tyreWarn.any || tyreWarnAny

/** Everything the app shows about the car at one instant. Nullable = Bluelink did not report it. */
data class CarState(
    val updatedAt: Long? = null,
    val engineOn: Boolean? = null,
    val locked: Boolean? = null,
    val doorsOpen: Quad = Quad(),
    val windowsOpen: Quad = Quad(),
    val hoodOpen: Boolean = false,
    val trunkOpen: Boolean = false,
    val fuelPct: Double? = null,
    val lowFuel: Boolean = false,
    val dteKm: Double? = null,
    val odometerKm: Double? = null,
    val batteryPct: Int? = null,
    val climateOn: Boolean = false,
    val defrostOn: Boolean = false,
    val setTempC: Double? = null,
    val tyreWarn: Quad = Quad(),
    val tyreWarnAny: Boolean = false,
    /** Pressure in psi per wheel (FL, FR, RL, RR) when the car reports numbers; null per wheel otherwise. */
    val tyrePsi: List<Double?> = listOf(null, null, null, null),
    /** False when Bluelink sent neither pressure numbers nor warning lamps, so "OK" would be a guess. */
    val tyreReported: Boolean = false,
    val headlampsOn: Boolean = false,
    val washerLow: Boolean = false,
    val brakeFluidLow: Boolean = false,
    val smartKeyLow: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val locTime: Long? = null,
)

data class TripSummary(
    val distKm: Double,
    val driveMin: Int,
    val idleMin: Int,
    val avgKmh: Double,
    val maxKmh: Double,
)

data class TripEntry(
    val startHhmmss: String,
    val distKm: Double?,
    val driveMin: Int?,
    val avgKmh: Double?,
    val maxKmh: Double?,
    val endHhmmss: String? = null,
    val idleMin: Int? = null,
    /** The trip exactly as Bluelink sent it, so the UI can show which fields the car really reports. */
    val raw: String? = null,
)

data class TripDay(val day: String, val count: Int, val summary: TripSummary?, val trips: List<TripEntry>)

enum class ActionResult { SUCCESS, FAILED, PENDING, TIMEOUT, UNKNOWN }

class BluelinkException(message: String, val code: String? = null, val auth: Boolean = false, val deviceId: Boolean = false) :
    Exception(message)

// ---------- tolerant JSON helpers (Bluelink payloads vary by model/firmware) ----------

/** Breadth-first search for the first value stored under [key] at any depth. */
internal fun Any?.deep(key: String): Any? {
    val queue = ArrayDeque<Any?>()
    queue.add(this)
    while (queue.isNotEmpty()) {
        when (val n = queue.removeFirst()) {
            is JSONObject -> {
                if (n.has(key) && !n.isNull(key)) return n.get(key)
                val it = n.keys()
                while (it.hasNext()) queue.add(n.opt(it.next()))
            }
            is JSONArray -> for (i in 0 until n.length()) queue.add(n.opt(i))
        }
    }
    return null
}

internal fun Any?.num(): Double? = when (this) {
    is Number -> toDouble()
    is String -> trim().toDoubleOrNull()
    else -> null
}

internal fun Any?.flag(): Boolean? = when (this) {
    is Boolean -> this
    is Number -> toInt() != 0
    is String -> when (trim().lowercase()) {
        "true", "1", "on", "open", "locked" -> true
        "false", "0", "off", "closed", "unlocked" -> false
        else -> null
    }
    else -> null
}

internal fun Any?.obj(key: String): Any? = (this as? JSONObject)?.opt(key)

/** Parses "20261016141500" (IST, no zone info) or an RFC-1123 GMT date. */
internal fun parseBluelinkTime(v: Any?): Long? {
    val s = (v as? String)?.trim() ?: return null
    return try {
        if (s.length >= 14 && s.take(14).all { it.isDigit() }) {
            val f = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            f.parse(s.take(14))?.time
        } else {
            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            f.parse(s)?.time
        }
    } catch (e: Exception) {
        null
    }
}

/** Bar, psi or kPa to psi; null when it is not a plausible tyre pressure. */
internal fun normalisePsi(v: Double): Double? = when {
    v in 0.5..6.0 -> v * 14.5038
    v in 10.0..80.0 -> v
    v in 80.0..600.0 -> v * 0.145038
    else -> null
}

private val TYRE_KEY = Regex("tire|tyre")
private val PRESS_KEY = Regex("press|psi|kpa")
private val NOT_PRESSURE = Regex("lamp|warn|unit|status|flag|time")
private val WHEEL_KEYS = listOf(
    Regex("front.?left|\\bfl\\b|fl$"), Regex("front.?right|\\bfr\\b|fr$"),
    Regex("(rear|back).?left|\\brl\\b|rl$"), Regex("(rear|back).?right|\\brr\\b|rr$"),
)

/** Walks the whole payload for numeric leaves whose key path names a tyre and a pressure, and files them by wheel. */
internal fun scanPressure(root: Any?): List<Double?> {
    val out = arrayOfNulls<Double>(4)
    fun visit(path: String, n: Any?) {
        when (n) {
            is JSONObject -> n.keys().forEach { k -> visit("$path.$k", n.opt(k)) }
            is JSONArray -> for (i in 0 until n.length()) visit("$path.$i", n.opt(i))
            else -> {
                val v = n.num() ?: return
                val p = path.lowercase()
                if (!TYRE_KEY.containsMatchIn(p) || !PRESS_KEY.containsMatchIn(p) || NOT_PRESSURE.containsMatchIn(p)) return
                val i = WHEEL_KEYS.indexOfFirst { it.containsMatchIn(p) }
                if (i >= 0 && out[i] == null) out[i] = normalisePsi(v)
            }
        }
    }
    visit("", root)
    return out.toList()
}

object StateParser {
    /** Merges the status, parked-location and maintenance responses into one [CarState]. */
    fun parse(status: JSONObject?, loc: JSONObject?, maint: JSONObject?): CarState {
        fun quad(parent: String, src: Any?): Quad {
            val p = src.deep(parent)
            return Quad(
                p.obj("frontLeft").flag() ?: false,
                p.obj("frontRight").flag() ?: false,
                p.obj("backLeft").flag() ?: false,
                p.obj("backRight").flag() ?: false,
            )
        }

        val dte = status.deep("dte")
        var dteKm = dte.obj("value").num()
        // unit: 1 = km, 3 = miles (Bluelink convention)
        if (dteKm != null && dte.obj("unit").num()?.toInt() == 3) dteKm *= 1.609344

        val air = status.deep("airTemp")
        val tempHex = (air.obj("value") as? String)?.replace("H", "")
        val setTemp = tempHex?.toIntOrNull(16)?.let { if (it in 0..60) 14.0 + it * 0.5 else null }

        val lamp = status.deep("tirePressureLamp")
        val tyre = Quad(
            lamp.obj("tirePressureLampFL").flag() ?: false,
            lamp.obj("tirePressureLampFR").flag() ?: false,
            lamp.obj("tirePressureLampRL").flag() ?: false,
            lamp.obj("tirePressureLampRR").flag() ?: false,
        )

        // numeric pressure, if this car sends it: key names vary by model, units by firmware (psi, bar or kPa)
        fun psi(vararg keys: String): Double? {
            for (k in keys) {
                val raw = status.deep(k)
                val v = (if (raw is JSONObject) raw.opt("value") else raw).num() ?: continue
                normalisePsi(v)?.let { return it }
            }
            return null
        }
        val press = status.deep("tirePressure")
        fun psiOf(vararg keys: String, nested: List<String>): Double? =
            psi(*keys) ?: nested.firstNotNullOfOrNull { n -> press.obj(n).let { (if (it is JSONObject) it.opt("value") else it).num() }?.let(::normalisePsi) }
        val named = listOf(
            psiOf("tirePressureFL", "tirePressureFrontLeft", "frontLeftTirePressure", "tyrePressureFL", "tirePressureValueFL", nested = listOf("frontLeft", "fl", "FL")),
            psiOf("tirePressureFR", "tirePressureFrontRight", "frontRightTirePressure", "tyrePressureFR", "tirePressureValueFR", nested = listOf("frontRight", "fr", "FR")),
            psiOf("tirePressureRL", "tirePressureRearLeft", "rearLeftTirePressure", "tyrePressureRL", "tirePressureValueRL", nested = listOf("rearLeft", "backLeft", "rl", "RL")),
            psiOf("tirePressureRR", "tirePressureRearRight", "rearRightTirePressure", "tyrePressureRR", "tirePressureValueRR", nested = listOf("rearRight", "backRight", "rr", "RR")),
        )
        // anything the named keys missed: look through the whole payload for a tyre + pressure number
        val scanned = if (named.all { it != null }) named else scanPressure(status)
        val tyrePsi = named.mapIndexed { i, v -> v ?: scanned[i] }

        val odo = (maint.deep("odometer").let { if (it is JSONObject) it.opt("value") else it }).num()
            ?: status.deep("odometer").let { if (it is JSONObject) it.opt("value") else it }.num()

        val coord = loc.deep("coord")
        val lat = coord.obj("lat").num()
        val lon = coord.obj("lon").num()

        var battery = status.deep("batSoc").num()?.toInt()
        if (battery == null) battery = status.deep("battery").obj("batSoc").num()?.toInt()

        val headLamp = status.deep("headLamp")

        return CarState(
            updatedAt = parseBluelinkTime(status.deep("time")),
            engineOn = status.deep("engine").flag(),
            locked = status.deep("doorLock").flag(),
            doorsOpen = quad("doorOpen", status),
            windowsOpen = quad("windowOpen", status),
            hoodOpen = status.deep("hoodOpen").flag() ?: false,
            trunkOpen = status.deep("trunkOpen").flag() ?: false,
            fuelPct = status.deep("fuelLevel").num()?.coerceIn(0.0, 100.0),
            lowFuel = status.deep("lowFuelLight").flag() ?: false,
            dteKm = dteKm,
            odometerKm = odo,
            batteryPct = battery,
            climateOn = status.deep("airCtrlOn").flag() ?: false,
            defrostOn = status.deep("defrost").flag() ?: false,
            setTempC = setTemp,
            tyreWarn = tyre,
            tyreWarnAny = lamp.obj("tirePressureLampAll").flag() ?: false,
            tyrePsi = tyrePsi,
            tyreReported = lamp != null || tyrePsi.any { it != null },
            headlampsOn = headLamp.obj("headLampStatus").flag() ?: false,
            washerLow = status.deep("washerFluidStatus").flag() ?: false,
            brakeFluidLow = status.deep("breakOilStatus").flag() ?: false,
            smartKeyLow = status.deep("smartKeyBatteryWarning").flag() ?: false,
            lat = lat,
            lon = lon,
            locTime = parseBluelinkTime(loc.deep("time")),
        )
    }
}
