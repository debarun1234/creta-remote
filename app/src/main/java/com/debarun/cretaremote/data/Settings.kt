package com.debarun.cretaremote.data

import android.content.Context

data class Settings(
    val tankLitres: Double = 50.0,
    val fuelPricePerL: Double = 0.0,
    val confirmBiometric: Boolean = true,
    val climateTempC: Double = 22.0,
    val climateMinutes: Int = 10,
    val onboarded: Boolean = false,
    val vin: String = "",
    val powertrain: String = "",
    val transmission: String = "",
    val modelYear: Int = 2022,
    val serviceKm: Int = 10_000,
    val serviceMonths: Int = 12,
    val notifyCommands: Boolean = true,
    val notifyAlerts: Boolean = true,
    val keepAwake: Boolean = true,
    /** Litres to keep in the tank on this trip; alerts fire as it nears this. */
    val fuelLimitL: Int = FuelWatch.DEFAULT,
    /** When the Bluelink password was last set (ms), 0 = not tracked yet. */
    val passwordSetAt: Long = 0L,
    /** How many days Bluelink lets a password live. Hyundai does not publish this, so the driver sets it. */
    val passwordDays: Int = 90,
)

/** Whole days until the Bluelink password expires (negative once it has), or null when not tracked. */
fun Settings.passwordDaysLeft(now: Long = System.currentTimeMillis()): Int? {
    if (passwordSetAt <= 0L || passwordDays <= 0) return null
    val left = passwordSetAt + passwordDays * 86_400_000L - now
    return Math.floorDiv(left, 86_400_000L).toInt() + if (left % 86_400_000L != 0L) 1 else 0
}

/** "2022 Creta SX" - the year comes from the VIN once one is entered, otherwise from the saved model year. */
fun Settings.carTitle(): String = "${Vin.decode(vin).modelYear ?: modelYear} Creta SX"

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        tankLitres = p.getFloat("tank", 50f).toDouble(),
        fuelPricePerL = p.getFloat("price", 0f).toDouble(),
        confirmBiometric = p.getBoolean("bio", true),
        climateTempC = p.getFloat("temp", 22f).toDouble(),
        climateMinutes = p.getInt("mins", 10),
        onboarded = p.getBoolean("onboarded", false),
        vin = p.getString("vin", "") ?: "",
        powertrain = p.getString("pt", "") ?: "",
        transmission = p.getString("tr", "") ?: "",
        modelYear = p.getInt("year", 2022),
        serviceKm = p.getInt("svcKm", 10_000),
        serviceMonths = p.getInt("svcMonths", 12),
        notifyCommands = p.getBoolean("ncmd", true),
        notifyAlerts = p.getBoolean("nalert", true),
        keepAwake = p.getBoolean("awake", true),
        fuelLimitL = p.getInt("fuelLimit", FuelWatch.DEFAULT).coerceIn(FuelWatch.MIN, FuelWatch.MAX),
        passwordSetAt = p.getLong("pwSetAt", 0L),
        passwordDays = p.getInt("pwDays", 90).coerceIn(1, 365),
    )

    fun save(s: Settings) {
        p.edit()
            .putFloat("tank", s.tankLitres.toFloat())
            .putFloat("price", s.fuelPricePerL.toFloat())
            .putBoolean("bio", s.confirmBiometric)
            .putFloat("temp", s.climateTempC.toFloat())
            .putInt("mins", s.climateMinutes)
            .putBoolean("onboarded", s.onboarded)
            .putString("vin", s.vin)
            .putString("pt", s.powertrain)
            .putString("tr", s.transmission)
            .putInt("year", s.modelYear)
            .putInt("svcKm", s.serviceKm)
            .putInt("svcMonths", s.serviceMonths)
            .putBoolean("ncmd", s.notifyCommands)
            .putBoolean("nalert", s.notifyAlerts)
            .putBoolean("awake", s.keepAwake)
            .putInt("fuelLimit", s.fuelLimitL)
            .putLong("pwSetAt", s.passwordSetAt)
            .putInt("pwDays", s.passwordDays)
            .apply()
    }
}
