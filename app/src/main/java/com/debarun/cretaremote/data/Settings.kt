package com.debarun.cretaremote.data

import android.content.Context

data class Settings(
    val tankLitres: Double = 50.0,
    val fuelPricePerL: Double = 0.0,
    val confirmBiometric: Boolean = true,
    val climateTempC: Double = 22.0,
    val climateMinutes: Int = 10,
    val onboarded: Boolean = false,
)

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        tankLitres = p.getFloat("tank", 50f).toDouble(),
        fuelPricePerL = p.getFloat("price", 0f).toDouble(),
        confirmBiometric = p.getBoolean("bio", true),
        climateTempC = p.getFloat("temp", 22f).toDouble(),
        climateMinutes = p.getInt("mins", 10),
        onboarded = p.getBoolean("onboarded", false),
    )

    fun save(s: Settings) {
        p.edit()
            .putFloat("tank", s.tankLitres.toFloat())
            .putFloat("price", s.fuelPricePerL.toFloat())
            .putBoolean("bio", s.confirmBiometric)
            .putFloat("temp", s.climateTempC.toFloat())
            .putInt("mins", s.climateMinutes)
            .putBoolean("onboarded", s.onboarded)
            .apply()
    }
}
