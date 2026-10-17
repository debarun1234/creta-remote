package com.debarun.cretaremote.data

import java.util.Calendar

/** Transmission options offered on the 2020-2023 Creta. */
enum class Transmission(val label: String, val detail: String) {
    MT6("6-speed manual", "6MT"),
    IVT("IVT (CVT)", "Intelligent Variable Transmission"),
    AT6("6-speed automatic", "Torque-converter 6AT"),
    DCT7("7-speed DCT", "Dual-clutch 7DCT"),
}

/**
 * The three engines the 2022 Creta was sold with in India. Figures are Hyundai's published numbers;
 * the app cannot read the engine from the car, so the owner picks it in Settings.
 */
enum class Powertrain(
    val label: String,
    val engine: String,
    val displacement: String,
    val power: String,
    val torque: String,
    val fuel: String,
    val transmissions: List<Transmission>,
) {
    PETROL_MPI(
        "1.5 Petrol", "1.5 L naturally aspirated, 4-cyl MPi", "1,497 cc",
        "115 PS @ 6,300 rpm", "144 Nm @ 4,500 rpm", "Petrol",
        listOf(Transmission.MT6, Transmission.IVT),
    ),
    DIESEL_CRDI(
        "1.5 Diesel", "1.5 L turbo-diesel, 4-cyl CRDi", "1,493 cc",
        "115 PS @ 4,000 rpm", "250 Nm @ 1,500-2,750 rpm", "Diesel",
        listOf(Transmission.MT6, Transmission.AT6),
    ),
    PETROL_TGDI(
        "1.4 Turbo Petrol", "1.4 L turbocharged, 4-cyl T-GDi", "1,353 cc",
        "140 PS @ 6,000 rpm", "242 Nm @ 1,500-3,200 rpm", "Petrol",
        listOf(Transmission.DCT7),
    );

    companion object {
        fun of(name: String): Powertrain? = entries.firstOrNull { it.name == name }
    }
}

/** Body / chassis figures shared by every 2022 Creta. */
object CretaSpecs {
    val common: List<Pair<String, String>> = listOf(
        "Length" to "4,300 mm",
        "Width" to "1,790 mm",
        "Height" to "1,635 mm",
        "Wheelbase" to "2,610 mm",
        "Ground clearance" to "190 mm",
        "Boot space" to "433 L",
        "Fuel tank" to "50 L",
        "Tyres (SX)" to "215/60 R17",
        "Drive" to "Front-wheel drive",
        "Seats" to "5",
    )
}

data class VinInfo(
    val vin: String,
    val valid: Boolean,
    val maker: String?,
    val modelYear: Int?,
    val serial: String?,
    val problem: String?,
)

object Vin {
    private const val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY" // A=2010 ... Y=2030; digits 1-9 = 2001-2009

    fun clean(raw: String): String = raw.uppercase().filter { it.isLetterOrDigit() }.take(17)

    fun decode(raw: String): VinInfo {
        val v = clean(raw)
        if (v.isEmpty()) return VinInfo(v, false, null, null, null, null)
        if (v.length < 17) return VinInfo(v, false, null, null, null, "${17 - v.length} more characters")
        if (v.any { it == 'I' || it == 'O' || it == 'Q' }) return VinInfo(v, false, null, null, null, "A VIN never contains I, O or Q")
        val maker = when (v.take(3)) {
            "MAL" -> "Hyundai Motor India Ltd."
            "KMH" -> "Hyundai Motor Company (Korea)"
            else -> null
        }
        val c = v[9]
        val year = when {
            c in '1'..'9' -> 2000 + (c - '0')
            YEAR_CODES.indexOf(c) >= 0 -> 2010 + YEAR_CODES.indexOf(c)
            else -> null
        }
        return VinInfo(v, true, maker, year, v.takeLast(6), null)
    }
}

// ------------------------------------------------------------------ service

data class ServiceLog(val ts: Long, val km: Double, val note: String, val cost: Double?)

enum class DueState { NONE, OK, SOON, DUE }

data class ServiceDue(
    val state: DueState,
    val nextKm: Double? = null,
    val nextDateMs: Long? = null,
    val kmLeft: Double? = null,
    val daysLeft: Long? = null,
)

object Service {
    private const val DAY = 86_400_000L

    /** Next service = whichever comes first of last-service km + interval, or last-service date + interval months. */
    fun due(last: ServiceLog?, odoKm: Double?, now: Long, intervalKm: Int, intervalMonths: Int): ServiceDue {
        if (last == null) return ServiceDue(DueState.NONE)
        val nextKm = last.km + intervalKm
        val nextDate = Calendar.getInstance().apply { timeInMillis = last.ts; add(Calendar.MONTH, intervalMonths) }.timeInMillis
        val kmLeft = odoKm?.let { nextKm - it }
        val daysLeft = Math.floorDiv(nextDate - now, DAY)
        val state = when {
            (kmLeft != null && kmLeft <= 0) || daysLeft <= 0 -> DueState.DUE
            (kmLeft != null && kmLeft < 1000) || daysLeft < 30 -> DueState.SOON
            else -> DueState.OK
        }
        return ServiceDue(state, nextKm, nextDate, kmLeft, daysLeft)
    }
}
