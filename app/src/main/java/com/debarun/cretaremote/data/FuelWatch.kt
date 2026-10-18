package com.debarun.cretaremote.data

/**
 * Per-trip fuel limit. The driver sets how many litres they want left in the tank (10-30, default 10). Two alerts
 * follow as the tank drops: a short heads-up 2 L above the limit, then an alarm at the limit that rings until it is
 * acknowledged. Once the tank is comfortably above the limit again (a refuel, or a lower limit) it re-arms.
 */
object FuelWatch {
    const val MIN = 10
    const val MAX = 30
    const val DEFAULT = 10
    const val HEADS_UP_MARGIN = 2.0
    private const val REARM_MARGIN = 4.0
    /** A rise this big between two readings is a refuel; the limit goes back to the default for the next trip. */
    const val REFUEL_L = 5.0

    enum class Fire { HEADS_UP, ALARM }

    /** stage: 0 quiet, 1 heads-up sent, 2 alarm ringing, 3 acknowledged. */
    data class Next(val stage: Int, val fire: Fire?)

    fun next(litres: Double, limit: Int, stage: Int): Next {
        var s = stage
        if (litres > limit + REARM_MARGIN) s = 0
        return when {
            litres <= limit && s < 2 -> Next(2, Fire.ALARM)
            litres <= limit + HEADS_UP_MARGIN && s < 1 -> Next(1, Fire.HEADS_UP)
            else -> Next(s, null)
        }
    }
}

data class FuelWatchUi(val stage: Int = 0, val litres: Double? = null)
