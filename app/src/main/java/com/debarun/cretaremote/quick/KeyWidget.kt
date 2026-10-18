package com.debarun.cretaremote.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.RemoteViews
import com.debarun.cretaremote.CretaApp
import com.debarun.cretaremote.MainActivity
import com.debarun.cretaremote.R
import com.debarun.cretaremote.bluelink.CarState
import com.debarun.cretaremote.bluelink.tyreAttention
import com.debarun.cretaremote.data.Cmd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat
import java.util.Locale

/**
 * Home-screen widgets. Locking, stopping the engine and Lights fire straight from a widget. Unlocking, starting and
 * the boot open the app instead, so they go through the biometric check: a widget on an unlocked home screen must
 * not be able to open or start the car by itself.
 */
abstract class CarWidget : AppWidgetProvider() {
    abstract fun build(c: Context): RemoteViews

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(context)) }
    }

    fun refresh(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        val ids = mgr.getAppWidgetIds(ComponentName(c, javaClass))
        if (ids.isNotEmpty()) onUpdate(c, mgr, ids)
    }
}

/** 4 x 1: lock key that follows the car's state, lights, ignition, boot. */
class KeyWidget : CarWidget() {
    override fun build(c: Context): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_key)
        val st = Widgets.style(c)
        Widgets.background(v, st)
        Widgets.lockKey(c, v, st)
        Widgets.ignitionKey(c, v, st)
        Widgets.key(v, R.id.w_lights, R.id.i_lights, R.id.t_lights, R.drawable.ic_shortcut_lights, "Lights", st, Widgets.run(c, Cmd.LIGHTS))
        Widgets.key(v, R.id.w_boot, R.id.i_boot, R.id.t_boot, R.drawable.ic_shortcut_boot, "Boot", st, Widgets.open(c, "BOOT"))
        return v
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != Widgets.RUN) return
        val cmd = runCatching { Cmd.valueOf(intent.getStringExtra("cmd").orEmpty()) }.getOrNull() ?: return
        val repo = (context.applicationContext as CretaApp).repo
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                repo.runDetached(cmd)
                // keep the process alive until the command settles (or give up after 40 s)
                withTimeoutOrNull(40_000) {
                    repo.data.first { it.cmd.cmd == cmd && it.cmd.busy }
                    repo.data.first { !it.cmd.busy }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** 2 x 1: just the lock key and the ignition key. */
class MiniWidget : CarWidget() {
    override fun build(c: Context): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_mini)
        val st = Widgets.style(c)
        Widgets.background(v, st)
        Widgets.lockKey(c, v, st)
        Widgets.ignitionKey(c, v, st)
        return v
    }
}

/** 4 x 2: lock state, fuel, range and odometer at a glance. Tap to open the app. */
class StatusWidget : CarWidget() {
    override fun build(c: Context): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_status)
        val s = WidgetState.read(c)
        val st = Widgets.style(c)
        Widgets.background(v, st)
        val nf = NumberFormat.getIntegerInstance(Locale("en", "IN"))
        v.setTextViewText(R.id.s_title, s.title.ifBlank { "Creta" })
        val state = when (s.locked) { true -> "Locked"; false -> "Unlocked"; null -> "Status unknown" } + if (s.engine) " · Engine on" else ""
        v.setTextViewText(R.id.s_state, state)
        v.setTextColor(R.id.s_state, if (s.locked == true) 0xFF36E39A.toInt() else if (s.locked == false) 0xFFFFB020.toInt() else st.dim)
        v.setTextViewText(R.id.s_fuel, s.fuel?.let { "${it.toInt()}%" } ?: "--")
        v.setTextViewText(R.id.s_range, s.dte?.let { "${nf.format(it.toInt())} km" } ?: "--")
        v.setTextViewText(R.id.s_odo, s.odo?.let { "${nf.format(it.toInt())} km" } ?: "--")
        v.setProgressBar(R.id.s_bar, 100, (s.fuel ?: 0.0).toInt().coerceIn(0, 100), false)
        v.setTextViewText(R.id.s_ago, if (s.ts > 0) "Updated ${com.debarun.cretaremote.ui.agoText(s.ts)}" else "Open the app to sync")
        v.setTextViewText(R.id.s_tyre, if (s.tyre.isBlank()) "Tyres --" else "Tyres ${s.tyre}")
        v.setTextColor(R.id.s_tyre, if (s.tyreWarn) 0xFFFFB020.toInt() else st.dim)
        for (id in listOf(R.id.s_title, R.id.s_fuel, R.id.s_range, R.id.s_odo)) v.setTextColor(id, st.text)
        for (id in listOf(R.id.s_fuel_l, R.id.s_range_l, R.id.s_odo_l, R.id.s_ago)) v.setTextColor(id, st.dim)
        v.setOnClickPendingIntent(R.id.w_root, Widgets.open(c, "HOME"))
        return v
    }
}

object Widgets {
    const val RUN = "com.debarun.cretaremote.WIDGET_RUN"

    class Style(val dark: Boolean, val alpha: Int) {
        val bg = if (dark) 0xFF0B0C10.toInt() else 0xFFF3F4F8.toInt()
        val text = if (dark) 0xFFE8EAF0.toInt() else 0xFF14161C.toInt()
        val dim = if (dark) 0xFF9AA1B2.toInt() else 0xFF5B6272.toInt()
    }

    /** Reads the theme and transparency chosen in the app's Settings. */
    fun style(c: Context): Style {
        val p = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val dark = when (p.getInt("wtheme", 0)) {
            1 -> true
            2 -> false
            else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val alpha = when (p.getInt("wbg", 1)) { 0 -> 245; 1 -> 150; else -> 0 }
        return Style(dark, alpha)
    }

    fun background(v: RemoteViews, st: Style) {
        v.setInt(R.id.w_bg, "setColorFilter", st.bg)
        v.setInt(R.id.w_bg, "setImageAlpha", st.alpha)
    }

    fun key(v: RemoteViews, cell: Int, img: Int, txt: Int, icon: Int, label: String, st: Style, tap: PendingIntent) {
        v.setImageViewResource(img, icon)
        v.setTextViewText(txt, label)
        v.setTextColor(txt, st.text)
        v.setOnClickPendingIntent(cell, tap)
    }

    /** One lock key that shows the car's state. Unlocked: tap locks at once. Locked: tap opens the app to unlock. */
    fun lockKey(c: Context, v: RemoteViews, st: Style) {
        val locked = WidgetState.read(c).locked
        when (locked) {
            false -> key(v, R.id.w_lock, R.id.i_lock, R.id.t_lock, R.drawable.ic_widget_unlocked, "Unlocked", st, run(c, Cmd.LOCK))
            true -> key(v, R.id.w_lock, R.id.i_lock, R.id.t_lock, R.drawable.ic_widget_locked, "Locked", st, open(c, "UNLOCK"))
            null -> key(v, R.id.w_lock, R.id.i_lock, R.id.t_lock, R.drawable.ic_widget_locked, "Lock", st, run(c, Cmd.LOCK))
        }
    }

    /** One ignition key. Running: Stop at once. Off: opens the app to start, behind the fingerprint. */
    fun ignitionKey(c: Context, v: RemoteViews, st: Style) {
        if (WidgetState.read(c).engine) key(v, R.id.w_ign, R.id.i_ign, R.id.t_ign, R.drawable.ic_widget_stop, "Stop", st, run(c, Cmd.STOP))
        else key(v, R.id.w_ign, R.id.i_ign, R.id.t_ign, R.drawable.ic_shortcut_start, "Start", st, open(c, "START"))
    }

    fun run(c: Context, cmd: Cmd): PendingIntent =
        PendingIntent.getBroadcast(
            c, cmd.ordinal,
            Intent(c, KeyWidget::class.java).setAction(RUN).putExtra("cmd", cmd.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun open(c: Context, action: String): PendingIntent =
        PendingIntent.getActivity(
            c, 100 + action.hashCode().mod(100),
            Intent(c, MainActivity::class.java).setAction("com.debarun.cretaremote.$action").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun updateAll(c: Context) {
        KeyWidget().refresh(c)
        MiniWidget().refresh(c)
        StatusWidget().refresh(c)
    }
}

/** The last known car state, kept so the widgets can show it without the app running. */
object WidgetState {
    data class S(
        val locked: Boolean?, val engine: Boolean, val fuel: Double?, val dte: Double?, val odo: Double?,
        val ts: Long, val title: String, val tyre: String, val tyreWarn: Boolean,
    )

    private fun prefs(c: Context) = c.getSharedPreferences("widget", Context.MODE_PRIVATE)

    fun read(c: Context): S {
        val p = prefs(c)
        fun d(k: String) = if (p.contains(k)) p.getFloat(k, 0f).toDouble() else null
        return S(
            if (p.contains("locked")) p.getBoolean("locked", true) else null,
            p.getBoolean("engine", false), d("fuel"), d("dte"), d("odo"), p.getLong("ts", 0L), p.getString("title", "") ?: "",
            p.getString("tyre", "") ?: "", p.getBoolean("tyreWarn", false),
        )
    }

    /** Called after every fetch. */
    fun save(c: Context, car: CarState, title: String, tyre: String) {
        prefs(c).edit().apply {
            if (car.locked == null) remove("locked") else putBoolean("locked", car.locked)
            putBoolean("engine", car.engineOn == true)
            car.fuelPct?.let { putFloat("fuel", it.toFloat()) } ?: remove("fuel")
            car.dteKm?.let { putFloat("dte", it.toFloat()) } ?: remove("dte")
            car.odometerKm?.let { putFloat("odo", it.toFloat()) } ?: remove("odo")
            putLong("ts", car.updatedAt ?: System.currentTimeMillis())
            putString("title", title)
            putString("tyre", tyre)
            putBoolean("tyreWarn", car.tyreAttention)
        }.apply()
        Widgets.updateAll(c)
    }

    fun saveTyre(c: Context, line: String, warn: Boolean) {
        prefs(c).edit().putString("tyre", line).putBoolean("tyreWarn", warn).apply()
        Widgets.updateAll(c)
    }

    fun saveLocked(c: Context, locked: Boolean) {
        if (read(c).locked == locked) return
        prefs(c).edit().putBoolean("locked", locked).apply()
        Widgets.updateAll(c)
    }

    fun saveEngine(c: Context, engine: Boolean) {
        if (read(c).engine == engine) return
        prefs(c).edit().putBoolean("engine", engine).apply()
        Widgets.updateAll(c)
    }
}
