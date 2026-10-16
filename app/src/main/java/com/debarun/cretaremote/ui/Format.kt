package com.debarun.cretaremote.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun agoText(ts: Long?, now: Long = System.currentTimeMillis()): String {
    if (ts == null) return "never"
    val s = (now - ts) / 1000
    return when {
        s < 45 -> "just now"
        s < 3600 -> "${maxOf(1L, s / 60)} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

fun dayText(ts: Long): String = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ts))
fun dateTimeText(ts: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(ts))

/** "20261016" -> "Fri 16 Oct" */
fun yyyymmddText(d: String): String = try {
    dayText(SimpleDateFormat("yyyyMMdd", Locale.US).parse(d)!!.time)
} catch (e: Exception) {
    d
}

/** minutes -> "1 h 05 m" */
fun minutesText(m: Int): String = if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)} m" else "$m m"
