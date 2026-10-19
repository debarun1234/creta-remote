package com.debarun.cretaremote.quick

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Colours for the drawn widgets. [dim] and [text] follow the widget theme; [alpha] is the background opacity.
 * The accent colours are deeper on a light card so they stay readable.
 */
class MeterColors(val dark: Boolean, val bg: Int, val text: Int, val dim: Int, val alpha: Int) {
    val cyan = if (dark) 0xFF3DD6F5.toInt() else 0xFF0A8DB0.toInt()
    val green = if (dark) 0xFF36E39A.toInt() else 0xFF0F9D62.toInt()
    val amber = if (dark) 0xFFFFB020.toInt() else 0xFFCF7A00.toInt()
    val red = if (dark) 0xFFFF4D5E.toInt() else 0xFFD3182B.toInt()
}

/** Everything the drawn widgets show, copied out of the saved widget state so this file needs nothing else from the app. */
class MeterData(
    val title: String = "Creta",
    val locked: Boolean? = null,
    val engine: Boolean = false,
    val fuelPct: Double? = null,
    val litres: Double? = null,
    val tank: Double = 50.0,
    val limit: Int = 10,
    val dteKm: Double? = null,
    val odoKm: Double? = null,
    val battPct: Int? = null,
    val ageText: String = "",
    val fillAtOdo: Double? = null,
    val kmToLimit: Double? = null,
    val kmpl: Double? = null,
    val rough: Boolean = true,
    val costPerKm: Double? = null,
    val psi: List<Double?> = List(4) { null },
    val warn: List<Boolean> = List(4) { false },
    val tyreReported: Boolean = false,
    val tyreWarnAny: Boolean = false,
    /** 0 idle, 1 syncing, 2 last sync failed: only changes the refresh button. */
    val sync: Int = 0,
)

/**
 * Canvas drawing for the meter widgets. All sizes are in dp: the caller scales the canvas, so one layout is crisp at any density.
 * Each widget has several looks and picks one from the size the launcher gives it, so dragging the corners restyles it:
 * TINY (a lone number), STRIP (one row), RING (about 2x2), WIDE (gauge on the left, figures on the right) and STACK (tall or large).
 * Every look is laid out from the real width and height, so nothing runs off the card at any size.
 */
object MeterArt {
    private val nf: NumberFormat = NumberFormat.getIntegerInstance(Locale.Builder().setLanguage("en").setRegion("IN").build())

    /** Width kept free for the refresh button: a strip loses it on the right, the other looks keep their top-right corner clear. */
    private const val BTN_W = 26f
    private const val PAD = 14f

    enum class Shape { TINY, STRIP, RING, WIDE, STACK }

    fun shapeOf(w: Float, h: Float): Shape = when {
        h < 100f && w >= 1.6f * h -> Shape.STRIP
        min(w, h) < 76f -> Shape.TINY
        w >= 1.5f * h && w >= h + 120f -> Shape.WIDE
        h >= 176f && w >= 110f -> Shape.STACK
        else -> Shape.RING
    }

    /** Too small for a refresh button. */
    fun hasButton(w: Float, h: Float) = shapeOf(w, h) != Shape.TINY

    /** Text scale for the roomier looks. */
    private fun kOf(w: Float, h: Float) = (min(w, h * 1.6f) / 200f).coerceIn(0.9f, 1.3f)

    // ------------------------------------------------------------------ primitives

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, width: Float, round: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
    }

    private fun withAlpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

    private fun txt(cv: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, maxW: Float = 100_000f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            textAlign = align
        }
        while (p.measureText(s) > maxW && p.textSize > 6f) p.textSize -= 0.5f
        cv.drawText(s, x, y, p)
    }

    private fun fuelColor(c: MeterColors, pct: Double?): Int = when {
        pct == null -> c.cyan
        pct < 15 -> c.red
        pct < 30 -> c.amber
        else -> c.cyan
    }

    private fun stateText(d: MeterData) = when (d.locked) { true -> "Locked"; false -> "Unlocked"; null -> "Status unknown" } + if (d.engine) " · Engine on" else ""
    private fun stateColor(c: MeterColors, d: MeterData) = when (d.locked) { true -> c.green; false -> c.amber; null -> c.dim }

    private fun card(cv: Canvas, w: Float, h: Float, c: MeterColors) {
        if (c.alpha > 0) cv.drawRoundRect(RectF(0f, 0f, w, h), 24f, 24f, fill(withAlpha(c.bg, c.alpha / 255f)))
        cv.drawRoundRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), 24f, 24f, stroke(withAlpha(c.text, if (c.alpha > 0) 0.08f else 0.18f), 1f))
    }

    /** 270 degree gauge with glow, tick marks and an end dot. */
    private fun arc(cv: Canvas, cx: Float, cy: Float, r: Float, sw: Float, frac: Float, color: Int, c: MeterColors, ticks: Boolean = true) {
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        cv.drawArc(rect, 135f, 270f, false, stroke(withAlpha(c.text, 0.13f), sw))
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.004f) {
            cv.drawArc(rect, 135f, 270f * f, false, stroke(withAlpha(color, 0.22f), sw * 1.7f))
            cv.drawArc(rect, 135f, 270f * f, false, stroke(color, sw))
            val a = (135f + 270f * f) * PI.toFloat() / 180f
            cv.drawCircle(cx + r * cos(a), cy + r * sin(a), sw * 0.32f, fill(if (c.dark) 0xFFFFFFFF.toInt() else 0xFF14161C.toInt()))
        }
        if (ticks && r > 26f) {
            val inner = r - sw / 2f - 3f
            val len = if (r > 50f) 5f else 3f
            for (i in 0..10) {
                val a = (135f + 270f * i / 10f) * PI.toFloat() / 180f
                val len2 = if (i % 5 == 0) len + 2f else len
                cv.drawLine(cx + inner * cos(a), cy + inner * sin(a), cx + (inner - len2) * cos(a), cy + (inner - len2) * sin(a), stroke(withAlpha(c.text, if (i % 5 == 0) 0.45f else 0.22f), 1f, round = false))
            }
        }
    }

    private fun ring(cv: Canvas, cx: Float, cy: Float, r: Float, sw: Float, frac: Float, color: Int, c: MeterColors) {
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        cv.drawArc(rect, -90f, 360f, false, stroke(withAlpha(c.text, 0.13f), sw))
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.004f) {
            cv.drawArc(rect, -90f, 360f * f, false, stroke(withAlpha(color, 0.22f), sw * 1.6f))
            cv.drawArc(rect, -90f, 360f * f, false, stroke(color, sw))
        }
    }

    /** Horizontal meter with quarter marks and an optional marker (the fuel limit). */
    private fun bar(cv: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, color: Int, c: MeterColors, marker: Float? = null) {
        val r = h / 2f
        cv.drawRoundRect(RectF(x, y, x + w, y + h), r, r, fill(withAlpha(c.text, 0.13f)))
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.004f) cv.drawRoundRect(RectF(x, y, x + max(h, w * f), y + h), r, r, fill(color))
        if (h >= 6f) for (i in 1..3) {
            val tx = x + w * i / 4f
            cv.drawLine(tx, y + h * 0.2f, tx, y + h * 0.8f, stroke(withAlpha(if (c.dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.35f), 1f, round = false))
        }
        if (marker != null) {
            val mx = x + w * marker.coerceIn(0f, 1f)
            cv.drawLine(mx, y - 3f, mx, y + h + 3f, stroke(c.amber, 2f))
        }
    }

    private fun pct(v: Double?) = v?.let { "${it.roundToInt()}%" } ?: "--"
    private fun km(v: Double?) = v?.let { "${nf.format(v.roundToInt())} km" } ?: "--"
    private fun litres(v: Double?) = v?.let { "${"%.1f".format(Locale.US, it)} L" } ?: "--"

    /** One figure: small caps label with the value under it. */
    private class Stat(val label: String, val value: String, val color: Int)

    /** Stats spread evenly across [x, x + width]; [y] is the label baseline. */
    private fun stats(cv: Canvas, x: Float, y: Float, width: Float, items: List<Stat>, c: MeterColors, k: Float) {
        if (items.isEmpty()) return
        val colW = width / items.size
        items.forEachIndexed { i, s ->
            txt(cv, s.label, x + colW * i, y, 9.5f, c.dim, true, maxW = colW - 4f)
            txt(cv, s.value, x + colW * i, y + 17f * k, 15f * k, s.color, true, maxW = colW - 6f)
        }
    }

    /** Lone figure for the smallest sizes. */
    private fun tiny(cv: Canvas, w: Float, h: Float, c: MeterColors, value: String, label: String, color: Int) {
        val s = (min(w, h) * 0.3f).coerceIn(12f, 26f)
        txt(cv, value, w / 2f, h / 2f + s * 0.2f, s, color, true, Paint.Align.CENTER, w - 10f)
        txt(cv, label, w / 2f, h / 2f + s * 0.2f + 13f, 9f, c.dim, false, Paint.Align.CENTER, w - 10f)
    }

    /**
     * Ring radius for the stacked look, and how many of the optional footer parts still fit under it.
     * [base] is the height the always-shown footer needs; [extras] are the heights of the optional parts, in priority order.
     */
    private fun stackFit(w: Float, h: Float, base: Float, extras: List<Float>): Pair<Float, Int> {
        for (n in extras.size downTo 0) {
            val r = min(w / 2f - 26f, (h - 2f * PAD - base - extras.take(n).sum()) / 2f)
            if (r >= 44f || n == 0) return r.coerceAtLeast(30f) to n
        }
        return 30f to 0
    }

    // ------------------------------------------------------------------ fuel meter

    fun fuel(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) {
        card(cv, w, h, c)
        val col = fuelColor(c, d.fuelPct)
        val frac = ((d.fuelPct ?: 0.0) / 100.0).toFloat()
        val k = kOf(w, h)
        when (shapeOf(w, h)) {
            Shape.TINY -> tiny(cv, w, h, c, pct(d.fuelPct), "FUEL", col)
            Shape.STRIP -> {
                val ew = w - BTN_W
                val vs = min(h * 0.38f, 24f)
                if (ew < 150f) {
                    txt(cv, pct(d.fuelPct), PAD, h / 2f - 1f, min(h * 0.36f, 22f), col, true)
                    bar(cv, PAD, h / 2f + 8f, ew - 2f * PAD, 8f, frac, col, c, (d.limit / d.tank).toFloat())
                } else {
                    txt(cv, pct(d.fuelPct), PAD, h / 2f + vs * 0.33f, vs, col, true)
                    val bx = PAD + vs * 2.2f + 6f
                    val showRange = ew >= 220f
                    val rw = if (showRange) 70f else 0f
                    bar(cv, bx, h / 2f - 5f, ew - bx - PAD - rw - (if (showRange) 8f else 0f), 10f, frac, col, c, (d.limit / d.tank).toFloat())
                    if (showRange) {
                        txt(cv, d.dteKm?.let { nf.format(it.roundToInt()) } ?: "--", ew - PAD, h / 2f + 3f, 17f, c.text, true, Paint.Align.RIGHT, rw)
                        txt(cv, "km range", ew - PAD, h / 2f + 16f, 10f, c.dim, false, Paint.Align.RIGHT)
                    }
                }
            }
            Shape.RING -> {
                val r = min(w, h) / 2f - 22f
                val cx = w / 2f
                val cy = h / 2f - 2f
                arc(cv, cx, cy, r, r * 0.17f, frac, col, c)
                txt(cv, pct(d.fuelPct), cx, cy + r * 0.2f, r * 0.62f, c.text, true, Paint.Align.CENTER, r * 1.3f)
                txt(cv, d.litres?.let { litres(it) } ?: "FUEL", cx, cy + r * 0.98f, max(9f, r * 0.24f), c.dim, false, Paint.Align.CENTER, r * 1.2f)
            }
            Shape.WIDE -> {
                val r = h / 2f - 16f
                val cx = r + 16f
                val cy = h / 2f
                arc(cv, cx, cy, r, r * 0.17f, frac, col, c)
                txt(cv, pct(d.fuelPct), cx, cy + r * 0.2f, r * 0.55f, c.text, true, Paint.Align.CENTER, r * 1.3f)
                txt(cv, "FUEL", cx, cy + r * 0.98f, 10f, c.dim, false, Paint.Align.CENTER)
                val x = 2f * r + 36f
                val rows = listOf("RANGE" to km(d.dteKm), "IN TANK" to litres(d.litres), "ODOMETER" to km(d.odoKm))
                val step = (h - 2f * PAD) / rows.size
                rows.forEachIndexed { i, (l, v) ->
                    val y0 = PAD + step * i
                    val rw = w - x - PAD - (if (i == 0) BTN_W else 0f)
                    txt(cv, l, x, y0 + 10f, 9.5f, c.dim, true, maxW = rw)
                    txt(cv, v, x, y0 + 10f + min(step - 14f, 20f * k), min(step * 0.62f, 20f * k), c.text, true, maxW = rw)
                }
            }
            Shape.STACK -> {
                val extras = listOf(20f)
                val (r, n) = stackFit(w, h, 50f, extras)
                val cx = w / 2f
                val cy = PAD + r
                arc(cv, cx, cy, r, r * 0.16f, frac, col, c)
                txt(cv, pct(d.fuelPct), cx, cy + r * 0.2f, r * 0.55f, c.text, true, Paint.Align.CENTER, r * 1.3f)
                txt(cv, d.litres?.let { "${litres(it)} of ${d.tank.roundToInt()} L" } ?: "FUEL", cx, cy + r * 0.98f, 11f, c.dim, false, Paint.Align.CENTER, r * 1.5f)
                val y = cy + r * 1.16f + 18f
                stats(cv, PAD, y, w - 2f * PAD, listOfNotNull(Stat("RANGE", km(d.dteKm), c.text), Stat("ODOMETER", km(d.odoKm), c.text), d.battPct?.let { Stat("12V", "$it%", c.text) }), c, k)
                if (n >= 1) txt(cv, tyreLine(d), PAD, y + 17f * k + 22f, 11f, if (tyreAttention(d)) c.amber else c.dim, true, maxW = w - 2f * PAD)
            }
        }
    }

    private fun tyreAttention(d: MeterData) = d.tyreWarnAny || d.warn.any { it }

    private fun tyreLine(d: MeterData): String = when {
        tyreAttention(d) -> "Tyres: check pressure"
        d.psi.all { it != null } -> "Tyres " + d.psi.joinToString(" · ") { "${it!!.roundToInt()}" } + " psi"
        d.tyreReported -> "Tyres: car says OK"
        else -> "Tyres: not reported"
    }

    // ------------------------------------------------------------------ dash (fuel ring, 12V ring only when the car reports it)

    fun dash(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) {
        card(cv, w, h, c)
        val fCol = fuelColor(c, d.fuelPct)
        val bCol = if ((d.battPct ?: 100) < 50) c.amber else c.green
        val ff = ((d.fuelPct ?: 0.0) / 100.0).toFloat()
        val bf = ((d.battPct ?: 0) / 100f)
        val k = kOf(w, h)
        val rangeText = d.dteKm?.let { nf.format(it.roundToInt()) } ?: "--"

        /** Fuel ring, plus an inner 12V ring only when there is a 12V reading to show. */
        fun rings(cx: Float, cy: Float, r: Float) {
            val sw = r * 0.15f
            ring(cv, cx, cy, r, sw, ff, fCol, c)
            if (d.battPct != null) ring(cv, cx, cy, r - sw * 1.55f, sw * 0.7f, bf, bCol, c)
            txt(cv, rangeText, cx, cy + r * 0.12f, r * 0.42f, c.text, true, Paint.Align.CENTER, r * 1.1f)
            txt(cv, "km range", cx, cy + r * 0.42f, max(8f, r * 0.17f), c.dim, false, Paint.Align.CENTER, r * 1.1f)
        }
        when (shapeOf(w, h)) {
            Shape.TINY -> tiny(cv, w, h, c, rangeText, "km range", c.text)
            Shape.STRIP -> {
                val ew = w - BTN_W
                if (ew < 150f) {
                    txt(cv, rangeText, PAD, h / 2f - 1f, min(h * 0.36f, 22f), c.text, true)
                    bar(cv, PAD, h / 2f + 8f, ew - 2f * PAD, 8f, ff, fCol, c)
                } else {
                    txt(cv, rangeText, PAD, h / 2f + 8f, 22f, c.text, true)
                    txt(cv, "km", PAD + 52f, h / 2f + 8f, 11f, c.dim)
                    val bx = PAD + 84f
                    val bw = ew - bx - PAD
                    if (d.battPct != null && h >= 56f) {
                        txt(cv, "FUEL ${pct(d.fuelPct)}", bx, h / 2f - 8f, 9.5f, c.dim, true, maxW = bw)
                        bar(cv, bx, h / 2f - 4f, bw, 6f, ff, fCol, c)
                        txt(cv, "12V ${d.battPct}%", bx, h / 2f + 18f, 9.5f, c.dim, true, maxW = bw)
                        bar(cv, bx, h / 2f + 22f, bw, 6f, bf, bCol, c)
                    } else {
                        txt(cv, "FUEL ${pct(d.fuelPct)}", bx, h / 2f - 4f, 9.5f, c.dim, true, maxW = bw)
                        bar(cv, bx, h / 2f + 2f, bw, 7f, ff, fCol, c)
                    }
                }
            }
            Shape.RING -> rings(w / 2f, h / 2f, min(w, h) / 2f - 22f)
            Shape.WIDE -> {
                val r = h / 2f - 16f
                rings(r + 16f, h / 2f, r)
                val x = 2f * r + 36f
                val cw = w - x - PAD
                txt(cv, d.title.ifBlank { "Creta" }, x, 28f, 14f * k, c.text, true, maxW = cw - BTN_W)
                txt(cv, stateText(d), x, 28f + 17f * k, 11f * k, stateColor(c, d), true, maxW = cw - BTN_W)
                val y = h / 2f + 12f
                stats(cv, x, y, cw, listOfNotNull(Stat("FUEL", pct(d.fuelPct), fCol), d.battPct?.let { Stat("12V", "$it%", bCol) }), c, k)
                if (h >= 130f) txt(cv, km(d.odoKm), x, h - PAD, 11f * k, c.dim, false, maxW = cw)
            }
            Shape.STACK -> {
                val (r, n) = stackFit(w, h, 70f, listOf(20f))
                rings(w / 2f, PAD + r, r)
                var y = PAD + 2f * r + 24f
                txt(cv, stateText(d), w / 2f, y, 13f, stateColor(c, d), true, Paint.Align.CENTER, w - 2f * PAD)
                y += 24f
                stats(cv, PAD, y, w - 2f * PAD, listOfNotNull(Stat("FUEL", pct(d.fuelPct), fCol), d.battPct?.let { Stat("12V", "$it%", bCol) }, Stat("ODO", km(d.odoKm), c.text)), c, k)
                if (n >= 1) txt(cv, d.ageText, PAD, y + 17f * k + 22f, 10f, c.dim, maxW = w - 2f * PAD)
            }
        }
    }

    // ------------------------------------------------------------------ next fill-up planner

    fun planner(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) {
        card(cv, w, h, c)
        val kmLeft = d.kmToLimit
        val tankL = d.litres
        val span = max(1.0, d.tank - d.limit)
        val frac = if (tankL != null) ((tankL - d.limit) / span).toFloat().coerceIn(0f, 1f) else 0f
        val col = when { kmLeft == null -> c.dim; kmLeft <= 0.5 -> c.red; frac < 0.25f -> c.amber; else -> c.green }
        val big = kmLeft?.let { if (it <= 0.5) "NOW" else nf.format(it.roundToInt()) } ?: "--"
        val sub = if (kmLeft != null && kmLeft > 0.5) "km to fill-up" else "fill up"
        val fillAt = d.fillAtOdo?.let { "${nf.format(it.roundToInt())} km" } ?: "--"
        val eco = d.kmpl?.let { "${"%.1f".format(Locale.US, it)} km/L" + if (d.rough) " (rough)" else "" } ?: "log a fill-up"
        val cost = d.costPerKm?.let { "₹${"%.2f".format(Locale.US, it)}/km" } ?: "--"
        val k = kOf(w, h)
        when (shapeOf(w, h)) {
            Shape.TINY -> tiny(cv, w, h, c, big, sub, col)
            Shape.STRIP -> {
                val ew = w - BTN_W
                if (ew < 150f) {
                    txt(cv, big, PAD, h / 2f - 1f, min(h * 0.36f, 22f), c.text, true, maxW = ew - 2f * PAD)
                    bar(cv, PAD, h / 2f + 8f, ew - 2f * PAD, 8f, frac, col, c)
                } else {
                    txt(cv, "FILL UP AT", PAD, h / 2f - 8f, 9.5f, c.dim, true)
                    txt(cv, fillAt, PAD, h / 2f + 12f, 17f, c.text, true, maxW = ew * 0.45f)
                    val bx = PAD + min(ew * 0.45f, 120f) + 12f
                    val bw = ew - bx - PAD
                    if (bw > 40f) {
                        txt(cv, if (kmLeft != null && kmLeft > 0.5) "in ${nf.format(kmLeft.roundToInt())} km" else "now", bx, h / 2f - 8f, 10.5f, col, true, maxW = bw)
                        bar(cv, bx, h / 2f - 2f, bw, 8f, frac, col, c)
                    }
                }
            }
            Shape.RING -> {
                val r = min(w, h) / 2f - 22f
                ring(cv, w / 2f, h / 2f, r, r * 0.15f, frac, col, c)
                txt(cv, big, w / 2f, h / 2f + r * 0.14f, r * 0.45f, c.text, true, Paint.Align.CENTER, r * 1.2f)
                txt(cv, sub, w / 2f, h / 2f + r * 0.46f, max(8f, r * 0.19f), c.dim, false, Paint.Align.CENTER, r * 1.2f)
            }
            Shape.WIDE -> {
                val r = h / 2f - 16f
                ring(cv, r + 16f, h / 2f, r, r * 0.15f, frac, col, c)
                txt(cv, big, r + 16f, h / 2f + r * 0.14f, r * 0.42f, c.text, true, Paint.Align.CENTER, r * 1.2f)
                txt(cv, sub, r + 16f, h / 2f + r * 0.44f, max(8f, r * 0.18f), c.dim, false, Paint.Align.CENTER, r * 1.2f)
                val x = 2f * r + 36f
                val cw = w - x - PAD
                txt(cv, "FILL UP AT", x, 28f, 9.5f, c.dim, true, maxW = cw - BTN_W)
                txt(cv, fillAt, x, 28f + 22f * k, 21f * k, c.text, true, maxW = cw - BTN_W)
                if (h >= 120f) {
                    txt(cv, eco, x, h - 32f, 11.5f * k, c.dim, false, maxW = cw)
                    txt(cv, cost, x, h - PAD, 11.5f * k, c.dim, false, maxW = cw)
                }
            }
            Shape.STACK -> {
                val (r, n) = stackFit(w, h, 56f, listOf(40f, 20f))
                ring(cv, w / 2f, PAD + r, r, r * 0.14f, frac, col, c)
                txt(cv, big, w / 2f, PAD + r + r * 0.14f, r * 0.42f, c.text, true, Paint.Align.CENTER, r * 1.2f)
                txt(cv, sub, w / 2f, PAD + r + r * 0.46f, max(9f, r * 0.17f), c.dim, false, Paint.Align.CENTER, r * 1.2f)
                var y = PAD + 2f * r + 24f
                txt(cv, "FILL UP AT", PAD, y, 9.5f, c.dim, true)
                txt(cv, fillAt, PAD, y + 22f * k, 22f * k, c.text, true, maxW = w - 2f * PAD)
                y += 22f * k + 22f
                if (n >= 1) {
                    val half = (w - 2f * PAD) / 2f
                    txt(cv, "ECONOMY", PAD, y, 9.5f, c.dim, true, maxW = half - 4f); txt(cv, eco, PAD, y + 16f, 13f, c.text, true, maxW = half - 6f)
                    txt(cv, "COST", PAD + half, y, 9.5f, c.dim, true, maxW = half - 4f); txt(cv, cost, PAD + half, y + 16f, 13f, c.text, true, maxW = half - 6f)
                    y += 38f
                }
                if (n >= 2 && tankL != null) txt(cv, "${litres(tankL)} in tank, limit ${d.limit} L", PAD, y + 4f, 10.5f, c.dim, maxW = w - 2f * PAD)
            }
        }
    }

    // ------------------------------------------------------------------ tyres

    private fun wheelColor(d: MeterData, i: Int, c: MeterColors): Int = when {
        d.warn[i] || d.tyreWarnAny -> c.amber
        d.psi[i] != null || d.tyreReported -> c.green
        else -> withAlpha(c.text, 0.3f)
    }

    private fun psiText(d: MeterData, i: Int): String = d.psi[i]?.let { "${it.roundToInt()}" } ?: if (d.warn[i]) "LOW" else "--"

    /** Top view of the car: body, glass, four wheels coloured by state, optional pressure labels beside them. */
    private fun car(cv: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, d: MeterData, c: MeterColors, labels: Boolean) {
        val body = RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
        cv.drawRoundRect(body, bw * 0.3f, bw * 0.3f, fill(withAlpha(c.text, 0.1f)))
        cv.drawRoundRect(body, bw * 0.3f, bw * 0.3f, stroke(withAlpha(c.text, 0.3f), 1.2f))
        cv.drawRoundRect(RectF(cx - bw * 0.34f, cy - bh * 0.28f, cx + bw * 0.34f, cy - bh * 0.06f), bw * 0.1f, bw * 0.1f, fill(withAlpha(c.text, 0.16f)))
        cv.drawRoundRect(RectF(cx - bw * 0.3f, cy + bh * 0.2f, cx + bw * 0.3f, cy + bh * 0.34f), bw * 0.1f, bw * 0.1f, fill(withAlpha(c.text, 0.12f)))
        val ww = bw * 0.2f
        val wh = bh * 0.2f
        val xs = listOf(cx - bw / 2f - ww * 0.45f, cx + bw / 2f - ww * 0.55f)
        val ys = listOf(cy - bh * 0.3f, cy + bh * 0.16f)
        for (i in 0 until 4) {
            val x = xs[i % 2]
            val y = ys[i / 2]
            cv.drawRoundRect(RectF(x, y, x + ww, y + wh), ww * 0.35f, ww * 0.35f, fill(wheelColor(d, i, c)))
            if (labels) {
                val left = i % 2 == 0
                txt(cv, psiText(d, i), if (left) x - 5f else x + ww + 5f, y + wh / 2f + 5f, 13f, c.text, true, if (left) Paint.Align.RIGHT else Paint.Align.LEFT)
            }
        }
    }

    fun tyres(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) {
        card(cv, w, h, c)
        val status = tyreLine(d)
        val warnCol = if (tyreAttention(d)) c.amber else if (d.tyreReported || d.psi.any { it != null }) c.green else c.dim
        val k = kOf(w, h)
        val names = listOf("FL", "FR", "RL", "RR")
        when (shapeOf(w, h)) {
            Shape.TINY -> tiny(cv, w, h, c, if (tyreAttention(d)) "CHECK" else d.psi.firstNotNullOfOrNull { it }?.let { "${it.roundToInt()}" } ?: "--", "TYRES", if (tyreAttention(d)) c.amber else c.text)
            Shape.STRIP -> {
                val ew = w - BTN_W
                val pad = 12f
                val cw = (ew - pad * 2f) / 4f
                if (ew < 150f) {
                    // too narrow for four readings: show the lowest one
                    val low = d.psi.filterNotNull().minOrNull()
                    val col = if (tyreAttention(d)) c.amber else if (low != null || d.tyreReported) c.green else c.dim
                    cv.drawCircle(pad + 4f, h / 2f - 11f, 3.5f, fill(col))
                    txt(cv, "LOWEST PSI", pad + 12f, h / 2f - 8f, 9.5f, c.dim, true, maxW = ew - 2f * pad - 12f)
                    txt(cv, low?.let { "${it.roundToInt()}" } ?: if (tyreAttention(d)) "LOW" else "--", pad, h / 2f + 14f, 17f, c.text, true, maxW = ew - 2f * pad)
                } else names.forEachIndexed { i, l ->
                    val x = pad + cw * i
                    cv.drawCircle(x + 4f, h / 2f - 11f, 3.5f, fill(wheelColor(d, i, c)))
                    txt(cv, l, x + 12f, h / 2f - 8f, 9.5f, c.dim, true, maxW = cw - 12f)
                    txt(cv, psiText(d, i), x + 1f, h / 2f + 14f, 17f, c.text, true, maxW = cw - 4f)
                }
            }
            Shape.RING -> {
                val bh = min(h - 24f, w * 1.1f)
                car(cv, w / 2f, h / 2f, bh * 0.46f, bh, d, c, labels = w >= 130f)
            }
            Shape.WIDE -> {
                val bh = h - 26f
                car(cv, bh * 0.23f + 30f, h / 2f, bh * 0.46f, bh, d, c, labels = false)
                val x = bh * 0.46f + 66f
                val cw = w - x - PAD
                txt(cv, "TYRE PRESSURE", x, 28f, 9.5f, c.dim, true, maxW = cw - BTN_W)
                txt(cv, status.removePrefix("Tyres: ").removePrefix("Tyres ").replaceFirstChar { it.uppercase() }, x, 28f + 18f * k, 13f * k, warnCol, true, maxW = cw - BTN_W)
                if (h >= 165f) {
                    val half = cw / 2f
                    names.forEachIndexed { i, l ->
                        val gx = x + half * (i % 2)
                        val gy = 76f + (h - 76f - 40f) * (i / 2)
                        txt(cv, l, gx, gy, 9.5f, c.dim, true)
                        txt(cv, psiText(d, i), gx, gy + 17f * k, 17f * k, c.text, true, maxW = half - 4f)
                    }
                } else {
                    val q = cw / 4f
                    names.forEachIndexed { i, l ->
                        txt(cv, l, x + q * i, h - 36f, 9.5f, c.dim, true, maxW = q - 2f)
                        txt(cv, psiText(d, i), x + q * i, h - 18f, 16f, c.text, true, maxW = q - 4f)
                    }
                }
            }
            Shape.STACK -> {
                val bh = min(h - 70f, w * 1.2f)
                car(cv, w / 2f, PAD + bh / 2f, bh * 0.46f, bh, d, c, labels = true)
                txt(cv, status, w / 2f, h - PAD, 12f, warnCol, true, Paint.Align.CENTER, w - 2f * PAD)
            }
        }
    }

    // ------------------------------------------------------------------ status (everything on one widget)

    fun status(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) {
        card(cv, w, h, c)
        val col = fuelColor(c, d.fuelPct)
        val frac = ((d.fuelPct ?: 0.0) / 100.0).toFloat()
        val sCol = stateColor(c, d)
        val k = kOf(w, h)
        val rangeText = d.dteKm?.let { nf.format(it.roundToInt()) } ?: "--"
        val fillAt = d.fillAtOdo?.let { km(it) }
        when (shapeOf(w, h)) {
            Shape.TINY -> tiny(cv, w, h, c, pct(d.fuelPct), if (d.locked == true) "LOCKED" else if (d.locked == false) "UNLOCKED" else "FUEL", col)
            Shape.STRIP -> {
                val ew = w - BTN_W
                if (ew < 150f) {
                    txt(cv, pct(d.fuelPct), PAD, h / 2f - 1f, min(h * 0.36f, 22f), col, true)
                    bar(cv, PAD, h / 2f + 8f, ew - 2f * PAD, 8f, frac, col, c, (d.limit / d.tank).toFloat())
                } else {
                    val vs = min(h * 0.38f, 24f)
                    txt(cv, pct(d.fuelPct), PAD, h / 2f + vs * 0.33f, vs, col, true)
                    val bx = PAD + vs * 2.2f + 6f
                    val bw = ew - bx - PAD
                    txt(cv, stateText(d), bx, h / 2f - 8f, 10f, sCol, true, maxW = bw * 0.55f)
                    txt(cv, "$rangeText km range", ew - PAD, h / 2f - 8f, 10f, c.dim, true, Paint.Align.RIGHT, bw * 0.45f)
                    bar(cv, bx, h / 2f - 2f, bw, 9f, frac, col, c, (d.limit / d.tank).toFloat())
                }
            }
            Shape.RING -> {
                val r = min(w, h) / 2f - 22f
                val cx = w / 2f
                val cy = h / 2f - 2f
                arc(cv, cx, cy, r, r * 0.17f, frac, col, c)
                txt(cv, rangeText, cx, cy + r * 0.12f, r * 0.5f, c.text, true, Paint.Align.CENTER, r * 1.3f)
                txt(cv, "km range", cx, cy + r * 0.45f, max(8f, r * 0.19f), c.dim, false, Paint.Align.CENTER, r * 1.2f)
                txt(cv, if (d.locked == true) "Locked" else if (d.locked == false) "Unlocked" else "FUEL ${pct(d.fuelPct)}", cx, cy + r * 1.0f, max(9f, r * 0.22f), sCol, true, Paint.Align.CENTER, r * 1.2f)
            }
            Shape.WIDE -> {
                txt(cv, d.title.ifBlank { "Creta" }, PAD + 2f, 28f, 15f * k, c.text, true, maxW = w * 0.4f)
                txt(cv, stateText(d), w - PAD - BTN_W - 14f, 28f, 12f * k, sCol, true, Paint.Align.RIGHT, w * 0.45f)
                val footer = h >= 125f
                val bottom = if (footer) h - 38f else h - 22f
                val mid = (44f + bottom - 22f) / 2f
                val items = listOfNotNull(Stat("FUEL", pct(d.fuelPct), c.text), Stat("RANGE", km(d.dteKm), c.text), Stat("ODOMETER", km(d.odoKm), c.text), if (w >= 420f) fillAt?.let { Stat("FILL UP AT", it, c.text) } else null)
                val colW = (w - 2f * (PAD + 2f)) / items.size
                items.forEachIndexed { i, s ->
                    val x = PAD + 2f + colW * i
                    txt(cv, s.value, x, mid + 8f, 19f * k, s.color, true, maxW = colW - 6f)
                    txt(cv, s.label.lowercase().replaceFirstChar { it.uppercase() }, x, mid + 8f + 15f, 11f, c.dim, false, maxW = colW - 6f)
                }
                bar(cv, PAD + 2f, bottom - 2f, w - 2f * (PAD + 2f), 7f, frac, col, c, (d.limit / d.tank).toFloat())
                if (footer) {
                    txt(cv, tyreLine(d), PAD + 2f, h - PAD + 2f, 11f, if (tyreAttention(d)) c.amber else c.dim, true, maxW = w * 0.6f)
                    if (w >= 330f) txt(cv, d.ageText, w - PAD - 2f, h - PAD + 2f, 10f, c.dim, false, Paint.Align.RIGHT, w * 0.35f)
                }
            }
            Shape.STACK -> {
                val (r, n) = stackFit(w, h, 58f, listOf(22f, 22f, 20f))
                val cx = w / 2f
                val cy = PAD + r
                arc(cv, cx, cy, r, r * 0.16f, frac, col, c)
                txt(cv, rangeText, cx, cy + r * 0.14f, r * 0.5f, c.text, true, Paint.Align.CENTER, r * 1.3f)
                txt(cv, "km range", cx, cy + r * 0.45f, max(9f, r * 0.17f), c.dim, false, Paint.Align.CENTER, r * 1.3f)
                txt(cv, stateText(d), cx, cy + r * 1.0f, 12f, sCol, true, Paint.Align.CENTER, r * 1.5f)
                val y = cy + r * 1.16f + 20f
                stats(cv, PAD, y, w - 2f * PAD, listOf(Stat("FUEL", pct(d.fuelPct), col), Stat("ODOMETER", km(d.odoKm), c.text)) + listOfNotNull(d.battPct?.let { Stat("12V", "$it%", c.text) }), c, k)
                var ly = y + 17f * k + 22f
                if (n >= 1) { txt(cv, tyreLine(d), PAD, ly, 11f, if (tyreAttention(d)) c.amber else c.dim, true, maxW = w - 2f * PAD); ly += 20f }
                if (n >= 2) { txt(cv, fillAt?.let { "Fill up at $it" } ?: "Fill up: log a fill-up", PAD, ly, 11f, c.dim, true, maxW = w - 2f * PAD); ly += 20f }
                if (n >= 3) txt(cv, d.ageText, PAD, ly, 10f, c.dim, maxW = w - 2f * PAD)
            }
        }
    }

    // ------------------------------------------------------------------ refresh button

    /**
     * Small round refresh button. A strip carries it at the right edge, every other look in the top-right corner (none on the
     * tiniest size); the real tap area is a transparent view in widget_canvas.xml at the same spot. Amber while syncing, red
     * when the last sync failed.
     */
    fun refreshButton(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) {
        if (!hasButton(w, h)) return
        val strip = shapeOf(w, h) == Shape.STRIP
        val cx = w - 20f
        val cy = if (strip) h / 2f else 20f
        val col = when (d.sync) { 1 -> c.amber; 2 -> c.red; else -> c.text }
        cv.drawCircle(cx, cy, 12f, fill(withAlpha(col, if (d.sync == 0) 0.10f else 0.20f)))
        cv.drawCircle(cx, cy, 12f, stroke(withAlpha(col, if (d.sync == 0) 0.22f else 0.6f), 1f))
        val r = 5.6f
        val arcCol = if (d.sync == 0) withAlpha(c.text, 0.85f) else col
        cv.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -50f, 280f, false, stroke(arcCol, 1.7f))
        // arrow head at the end of the arc, pointing along the direction of travel
        val a = Math.toRadians(230.0)
        val ex = cx + r * cos(a).toFloat()
        val ey = cy + r * sin(a).toFloat()
        val dx = (-sin(a)).toFloat()
        val dy = cos(a).toFloat()
        val path = android.graphics.Path().apply {
            moveTo(ex + dx * 4f, ey + dy * 4f)
            lineTo(ex - dy * 3.4f, ey + dx * 3.4f)
            lineTo(ex + dy * 3.4f, ey - dx * 3.4f)
            close()
        }
        cv.drawPath(path, fill(arcCol))
    }
}
