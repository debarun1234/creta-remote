package com.debarun.cretaremote.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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
 * Home-screen key. Lock and Lights fire straight from the widget. Unlock and Boot open the app instead, so they
 * go through the biometric check; a widget on an unlocked home screen must not be able to open the car by itself.
 */
class KeyWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(context.packageName, R.layout.widget_key)
        v.setOnClickPendingIntent(R.id.w_lock, run(context, Cmd.LOCK))
        v.setOnClickPendingIntent(R.id.w_lights, run(context, Cmd.LIGHTS))
        v.setOnClickPendingIntent(R.id.w_unlock, open(context, "UNLOCK"))
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
