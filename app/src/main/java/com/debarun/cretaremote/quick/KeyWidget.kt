package com.debarun.cretaremote.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.debarun.cretaremote.CretaApp
import com.debarun.cretaremote.MainActivity
import com.debarun.cretaremote.R
import com.debarun.cretaremote.data.Cmd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home-screen key: a lock key that follows the car's state, Lights, an ignition key and Boot. Locking, stopping the
 * engine and Lights fire straight from the widget. Unlocking, starting and the boot open the app instead, so they go
 * through the biometric check; a widget on an unlocked home screen must not be able to open or start the car itself.
 */
class KeyWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(context.packageName, R.layout.widget_key)
        val st = WidgetState.read(context)
        // one lock key that shows the car's state: open lock -> tap locks straight away; locked -> tap opens the app to unlock
        if (st.locked == false) {
            v.setTextViewText(R.id.w_lock, "Unlocked")
            v.setTextViewCompoundDrawables(R.id.w_lock, 0, R.drawable.ic_widget_unlocked, 0, 0)
            v.setOnClickPendingIntent(R.id.w_lock, run(context, Cmd.LOCK))
        } else {
            v.setTextViewText(R.id.w_lock, if (st.locked == true) "Locked" else "Lock")
            v.setTextViewCompoundDrawables(R.id.w_lock, 0, R.drawable.ic_widget_locked, 0, 0)
            v.setOnClickPendingIntent(R.id.w_lock, if (st.locked == true) open(context, "UNLOCK") else run(context, Cmd.LOCK))
        }
        // one ignition key: running -> Stop straight away; off -> opens the app to start (fingerprint)
        if (st.engine) {
            v.setTextViewText(R.id.w_ign, "Stop")
            v.setTextViewCompoundDrawables(R.id.w_ign, 0, R.drawable.ic_widget_stop, 0, 0)
            v.setOnClickPendingIntent(R.id.w_ign, run(context, Cmd.STOP))
        } else {
            v.setTextViewText(R.id.w_ign, "Start")
            v.setTextViewCompoundDrawables(R.id.w_ign, 0, R.drawable.ic_shortcut_start, 0, 0)
            v.setOnClickPendingIntent(R.id.w_ign, open(context, "START"))
        }
        v.setOnClickPendingIntent(R.id.w_lights, run(context, Cmd.LIGHTS))
        v.setOnClickPendingIntent(R.id.w_boot, open(context, "BOOT"))
        ids.forEach { mgr.updateAppWidget(it, v) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != RUN) return
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

    private fun run(c: Context, cmd: Cmd): PendingIntent =
        PendingIntent.getBroadcast(
            c, cmd.ordinal,
            Intent(c, KeyWidget::class.java).setAction(RUN).putExtra("cmd", cmd.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun open(c: Context, action: String): PendingIntent =
        PendingIntent.getActivity(
            c, 100 + action.hashCode() % 100,
            Intent(c, MainActivity::class.java).setAction("com.debarun.cretaremote.$action").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val RUN = "com.debarun.cretaremote.WIDGET_RUN"
    }
}

/** The last known lock and engine state, kept so the widget can show it without the app running. */
object WidgetState {
    data class S(val locked: Boolean?, val engine: Boolean)

    private fun prefs(c: Context) = c.getSharedPreferences("widget", Context.MODE_PRIVATE)

    fun read(c: Context): S {
        val p = prefs(c)
        return S(if (p.contains("locked")) p.getBoolean("locked", true) else null, p.getBoolean("engine", false))
    }

    fun save(c: Context, locked: Boolean?, engine: Boolean) {
        if (read(c) == S(locked, engine)) return
        prefs(c).edit().apply { if (locked == null) remove("locked") else putBoolean("locked", locked) }.putBoolean("engine", engine).apply()
        refresh(c)
    }

    fun saveLocked(c: Context, locked: Boolean) = save(c, locked, read(c).engine)
    fun saveEngine(c: Context, engine: Boolean) = save(c, read(c).locked, engine)

    private fun refresh(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        val ids = mgr.getAppWidgetIds(ComponentName(c, KeyWidget::class.java))
        if (ids.isNotEmpty()) KeyWidget().onUpdate(c, mgr, ids)
    }
}
