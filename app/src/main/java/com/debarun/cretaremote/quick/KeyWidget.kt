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

/**
 * Home-screen widgets. Locking, stopping the engine and Lights fire straight from a widget. Unlocking, starting and
 * the boot open the app instead, so they go through the biometric check: a widget on an unlocked home screen must
 * not be able to open or start the car by itself.
 */
abstract class CarWidget : AppWidgetProvider() {
    abstract fun build(c: Context, id: Int): RemoteViews

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(context, it)) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetPrefs.remove(context, it) }
    }

    fun refresh(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        val ids = mgr.getAppWidgetIds(ComponentName(c, javaClass))
        if (ids.isNotEmpty()) onUpdate(c, mgr, ids)
    }
}

/** 4 x 1: lock key that follows the car's state, lights, ignition, boot. */
class KeyWidget : CarWidget() {
    override fun build(c: Context, id: Int): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_key)
        val st = Widgets.style(c, id)
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
    override fun build(c: Context, id: Int): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_mini)
        val st = Widgets.style(c, id)
        Widgets.background(v, st)
        Widgets.lockKey(c, v, st)
        Widgets.ignitionKey(c, v, st)
        return v
    }
}

object Widgets {
    const val RUN = "com.debarun.cretaremote.WIDGET_RUN"
    const val REFRESH = "com.debarun.cretaremote.WIDGET_REFRESH"

    class Style(val dark: Boolean, val alpha: Int) {
        val bg = if (dark) 0xFF0B0C10.toInt() else 0xFFF3F4F8.toInt()
        val text = if (dark) 0xFFE8EAF0.toInt() else 0xFF14161C.toInt()
        val dim = if (dark) 0xFF9AA1B2.toInt() else 0xFF5B6272.toInt()
    }

    fun phoneIsDark(c: Context): Boolean = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** The look chosen for this one widget on its settings screen (light and solid until chosen otherwise). */
    fun style(c: Context, id: Int): Style {
        val cfg = WidgetPrefs.get(c, id)
        val dark = when (cfg.theme) {
            WidgetPrefs.DARK -> true
            WidgetPrefs.AUTO -> phoneIsDark(c)
            else -> false
        }
        return Style(dark, cfg.alpha)
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

    /** Live-sync button of the drawn widgets: a broadcast back to the widget's own class. */
    fun refresh(c: Context, cls: Class<*>): PendingIntent =
        PendingIntent.getBroadcast(
            c, 300 + cls.simpleName.hashCode().mod(100),
            Intent(c, cls).setAction(REFRESH),
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
        FuelMeterWidget().refresh(c)
        DashWidget().refresh(c)
        PlannerWidget().refresh(c)
        TyreWidget().refresh(c)
    }
}

/** The last known car state, kept so the widgets can show it without the app running. */
object WidgetState {
    data class S(
        val locked: Boolean?, val engine: Boolean, val fuel: Double?, val dte: Double?, val odo: Double?,
        val ts: Long, val title: String, val tyre: String, val tyreWarn: Boolean,
        val batt: Int? = null, val litres: Double? = null, val tank: Double = 50.0, val limit: Int = 10,
        val fillAt: Double? = null, val kmToLimit: Double? = null, val kmpl: Double? = null, val rough: Boolean = true,
        val costKm: Double? = null, val psi: List<Double?> = List(4) { null }, val warn: List<Boolean> = List(4) { false },
        val tyreReported: Boolean = false, val tyreAny: Boolean = false,
        /** 0 idle, 1 syncing from a widget's refresh button, 2 that sync failed. */
        val sync: Int = 0,
    )

    private fun prefs(c: Context) = c.getSharedPreferences("widget", Context.MODE_PRIVATE)

    fun read(c: Context): S {
        val p = prefs(c)
        fun d(k: String) = if (p.contains(k)) p.getFloat(k, 0f).toDouble() else null
        return S(
            if (p.contains("locked")) p.getBoolean("locked", true) else null,
            p.getBoolean("engine", false), d("fuel"), d("dte"), d("odo"), p.getLong("ts", 0L), p.getString("title", "") ?: "",
            p.getString("tyre", "") ?: "", p.getBoolean("tyreWarn", false),
            batt = if (p.contains("batt")) p.getInt("batt", 0) else null,
            litres = d("litres"), tank = p.getFloat("tank", 50f).toDouble(), limit = p.getInt("limit", 10),
            fillAt = d("fillAt"), kmToLimit = d("kmToLimit"), kmpl = d("kmpl"), rough = p.getBoolean("rough", true), costKm = d("costKm"),
            psi = (p.getString("psi", "") ?: "").split(",").let { l -> List(4) { i -> l.getOrNull(i)?.toDoubleOrNull() } },
            warn = (p.getString("warn", "") ?: "").let { w -> List(4) { i -> w.getOrNull(i) == '1' } },
            tyreReported = p.getBoolean("tyreReported", false), tyreAny = p.getBoolean("tyreAny", false),
            // a sync that never reported back (process killed) must not leave the button stuck on "syncing"
            sync = p.getInt("sync", 0).let { v -> if (v == 1 && System.currentTimeMillis() - p.getLong("syncAt", 0L) > 90_000L) 2 else v },
        )
    }

    /** Refresh-button state for the drawn widgets. */
    fun saveSync(c: Context, state: Int) {
        prefs(c).edit().putInt("sync", state).putLong("syncAt", System.currentTimeMillis()).apply()
        Widgets.updateAll(c)
    }

    /** Called after every fetch. */
    fun save(c: Context, car: CarState, title: String, tyre: String, psi: List<Double?> = car.tyrePsi) {
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
            car.batteryPct?.let { putInt("batt", it) } ?: remove("batt")
            putString("warn", listOf(car.tyreWarn.fl, car.tyreWarn.fr, car.tyreWarn.rl, car.tyreWarn.rr).joinToString("") { if (it) "1" else "0" })
            putBoolean("tyreReported", car.tyreReported)
            putBoolean("tyreAny", car.tyreWarnAny)
            putString("psi", psi.joinToString(",") { it?.toString() ?: "" })
            if (prefs(c).getInt("sync", 0) == 2) putInt("sync", 0) // any good fetch clears an old failure
        }.apply()
        Widgets.updateAll(c)
    }

    fun saveTyre(c: Context, line: String, warn: Boolean, psi: List<Double?>? = null) {
        prefs(c).edit().putString("tyre", line).putBoolean("tyreWarn", warn).apply {
            if (psi != null) putString("psi", psi.joinToString(",") { it?.toString() ?: "" })
        }.apply()
        Widgets.updateAll(c)
    }

    /** Fuel numbers from the Fuel log maths, for the planner and gauge widgets. Only redraws when something changed. */
    fun savePlan(c: Context, litres: Double?, tank: Double, limit: Int, fillAt: Double?, kmToLimit: Double?, kmpl: Double?, rough: Boolean, costKm: Double?) {
        val sig = listOf(litres?.let { Math.round(it * 10) }, Math.round(tank), limit, fillAt?.let { Math.round(it) }, kmToLimit?.let { Math.round(it) }, kmpl?.let { Math.round(it * 10) }, rough, costKm?.let { Math.round(it * 100) }).joinToString("|")
        val p = prefs(c)
        if (p.getString("plan_sig", null) == sig) return
        p.edit().apply {
            fun put(k: String, v: Double?) { if (v == null) remove(k) else putFloat(k, v.toFloat()) }
            put("litres", litres); putFloat("tank", tank.toFloat()); putInt("limit", limit)
            put("fillAt", fillAt); put("kmToLimit", kmToLimit); put("kmpl", kmpl); putBoolean("rough", rough); put("costKm", costKm)
            putString("plan_sig", sig)
        }.apply()
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
