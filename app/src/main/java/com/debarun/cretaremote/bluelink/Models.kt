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
