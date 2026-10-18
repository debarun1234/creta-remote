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
    /** 0 follow the phone, 1 dark, 2 light */
    val widgetTheme: Int = 0,
    /** 0 solid, 1 glass, 2 clear */
    val widgetBg: Int = 1,
)

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
        widgetTheme = p.getInt("wtheme", 0),
        widgetBg = p.getInt("wbg", 1),
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
            .putInt("wtheme", s.widgetTheme)
            .putInt("wbg", s.widgetBg)
            .apply()
    }
}
