package com.debarun.cretaremote.quick

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.RemoteViews
import com.debarun.cretaremote.R
import kotlin.math.sqrt

/**
 * Widgets drawn as one picture so they can restyle themselves while you drag their corners: the launcher tells us the
 * size, [MeterArt] picks a ring, a strip, a split or a stacked layout for it, and the bitmap is redrawn at exactly that size.
 * Tapping anywhere opens the app.
 */
abstract class MeterWidget : CarWidget() {
    abstract val desc: String
    abstract fun draw(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData)

    override fun build(c: Context): RemoteViews = build(c, 160f, 160f)

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        render(context, mgr, id)
    }

    private fun render(c: Context, mgr: AppWidgetManager, id: Int) {
        val o = mgr.getAppWidgetOptions(id)
        val land = c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val w = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 160
        val h = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 160
        mgr.updateAppWidget(id, build(c, w.toFloat(), h.toFloat()))
    }

    private fun build(c: Context, wDp: Float, hDp: Float): RemoteViews {
        val st = Widgets.style(c)
        val s = WidgetState.read(c)
        var scale = c.resources.displayMetrics.density
        val px = wDp * scale * hDp * scale
        if (px > 900_000f) scale *= sqrt(900_000f / px) // keep the picture well under the launcher's size limit
        val bmp = Bitmap.createBitmap((wDp * scale).toInt().coerceAtLeast(1), (hDp * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(scale, scale)
        draw(cv, wDp, hDp, MeterColors(st.dark, st.bg, st.text, st.dim, st.alpha), meterData(s))
        val v = RemoteViews(c.packageName, R.layout.widget_canvas)
        v.setImageViewBitmap(R.id.w_img, bmp)
        v.setContentDescription(R.id.w_img, desc)
        v.setOnClickPendingIntent(R.id.w_root, Widgets.open(c, "HOME"))
        return v
    }
}

/** Fuel arc with range, litres and odometer. */
class FuelMeterWidget : MeterWidget() {
    override val desc = "Creta fuel meter"
    override fun draw(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) = MeterArt.fuel(cv, w, h, c, d)
}

/** Fuel and 12V battery as two rings around the range. */
class DashWidget : MeterWidget() {
    override val desc = "Creta dashboard: range, fuel and battery"
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

/** Builds what the drawn widgets show from the saved widget state. */
private fun meterData(s: WidgetState.S): MeterData = MeterData(
    title = s.title, locked = s.locked, engine = s.engine, fuelPct = s.fuel, litres = s.litres ?: s.fuel?.let { it / 100.0 * s.tank },
    tank = s.tank, limit = s.limit, dteKm = s.dte, odoKm = s.odo, battPct = s.batt,
    ageText = if (s.ts > 0) "Updated ${com.debarun.cretaremote.ui.agoText(s.ts)}" else "Open the app to sync",
    fillAtOdo = s.fillAt, kmToLimit = s.kmToLimit, kmpl = s.kmpl, rough = s.rough, costPerKm = s.costKm,
    psi = s.psi, warn = s.warn, tyreReported = s.tyreReported, tyreWarnAny = s.tyreAny,
)
