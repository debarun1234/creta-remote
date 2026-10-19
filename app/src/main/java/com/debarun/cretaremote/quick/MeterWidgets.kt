package com.debarun.cretaremote.quick

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.debarun.cretaremote.CretaApp
import com.debarun.cretaremote.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

/**
 * Widgets drawn as one picture so they can restyle themselves while you drag their corners: the launcher tells us the
 * size, [MeterArt] picks a look for it and the bitmap is redrawn at exactly that size. Tapping anywhere opens the app;
 * the round button in the corner (right edge on a strip) syncs live from the car without opening it.
 */
abstract class MeterWidget : CarWidget() {
    abstract val desc: String
    abstract fun draw(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData)

    override fun build(c: Context, id: Int): RemoteViews = build(c, id, 160f, 160f)

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        render(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != Widgets.REFRESH) return
        val repo = (context.applicationContext as CretaApp).repo
        val pending = goAsync()
        WidgetState.saveSync(context, 1)
        CoroutineScope(Dispatchers.Default).launch {
            var ok = false
            try {
                // null from refresh = another sync is already running, which is as good as ours
                ok = withTimeoutOrNull(55_000) { repo.syncLive(quiet = true) ?: true } == true
            } finally {
                WidgetState.saveSync(context, if (ok) 0 else 2)
                pending.finish()
            }
        }
    }

    private fun render(c: Context, mgr: AppWidgetManager, id: Int) {
        val o = mgr.getAppWidgetOptions(id)
        val land = c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val w = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 160
        val h = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 160
        mgr.updateAppWidget(id, build(c, id, w.toFloat(), h.toFloat()))
    }

    /** The widget's picture at the given size and style. Also used for the live preview on the widget's settings screen. */
    fun bitmap(c: Context, wDp: Float, hDp: Float, st: Widgets.Style): Bitmap {
        var scale = c.resources.displayMetrics.density
        val px = wDp * scale * hDp * scale
        if (px > 900_000f) scale *= sqrt(900_000f / px) // keep the picture well under the launcher's size limit
        val bmp = Bitmap.createBitmap((wDp * scale).toInt().coerceAtLeast(1), (hDp * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(scale, scale)
        val colors = MeterColors(st.dark, st.bg, st.text, st.dim, st.alpha)
        val data = meterData(WidgetState.read(c))
        draw(cv, wDp, hDp, colors, data)
        MeterArt.refreshButton(cv, wDp, hDp, colors, data)
        return bmp
    }

    private fun build(c: Context, id: Int, wDp: Float, hDp: Float): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_canvas)
        v.setImageViewBitmap(R.id.w_img, bitmap(c, wDp, hDp, Widgets.style(c, id)))
        v.setContentDescription(R.id.w_img, desc)
        v.setOnClickPendingIntent(R.id.w_root, Widgets.open(c, "HOME"))
        val shape = MeterArt.shapeOf(wDp, hDp)
        val strip = shape == MeterArt.Shape.STRIP
        v.setViewVisibility(R.id.w_sync_top, if (MeterArt.hasButton(wDp, hDp) && !strip) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.w_sync_mid, if (strip) View.VISIBLE else View.GONE)
        if (MeterArt.hasButton(wDp, hDp)) {
            val tap = if (strip) R.id.w_sync_mid else R.id.w_sync_top
            v.setContentDescription(tap, c.getString(R.string.widget_refresh))
            v.setOnClickPendingIntent(tap, Widgets.refresh(c, javaClass))
        }
        return v
    }
}

/** Fuel arc with range, litres and odometer. */
class FuelMeterWidget : MeterWidget() {
    override val desc = "Creta fuel meter"
    override fun draw(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) = MeterArt.fuel(cv, w, h, c, d)
}

/** Range in a ring around the fuel level, with the lock state. A 12V ring appears only if the car reports it. */
class DashWidget : MeterWidget() {
    override val desc = "Creta dashboard: range and fuel"
    override fun draw(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) = MeterArt.dash(cv, w, h, c, d)
}

/** Where to fill up next, by odometer, from the Fuel log maths. */
class PlannerWidget : MeterWidget() {
    override val desc = "Creta next fill-up"
    override fun draw(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) = MeterArt.planner(cv, w, h, c, d)
}

/** Four tyres on a top view of the car. */
class TyreWidget : MeterWidget() {
    override val desc = "Creta tyre pressure"
    override fun draw(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) = MeterArt.tyres(cv, w, h, c, d)
}

/** Lock state, fuel, range, odometer, tyres and the next fill-up: shows more as it is made bigger. */
class StatusWidget : MeterWidget() {
    override val desc = "Creta status"
    override fun draw(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) = MeterArt.status(cv, w, h, c, d)
}

/** Builds what the drawn widgets show from the saved widget state. */
private fun meterData(s: WidgetState.S): MeterData = MeterData(
    title = s.title, locked = s.locked, engine = s.engine, fuelPct = s.fuel, litres = s.litres ?: s.fuel?.let { it / 100.0 * s.tank },
    tank = s.tank, limit = s.limit, dteKm = s.dte, odoKm = s.odo, battPct = s.batt,
    ageText = when {
        s.sync == 1 -> "Syncing…"
        s.sync == 2 -> "Sync failed, tap refresh to retry"
        s.ts > 0 -> "Updated ${com.debarun.cretaremote.ui.agoText(s.ts)}"
        else -> "Open the app to sync"
    },
    fillAtOdo = s.fillAt, kmToLimit = s.kmToLimit, kmpl = s.kmpl, rough = s.rough, costPerKm = s.costKm,
    psi = s.psi, warn = s.warn, tyreReported = s.tyreReported, tyreWarnAny = s.tyreAny, sync = s.sync,
)
