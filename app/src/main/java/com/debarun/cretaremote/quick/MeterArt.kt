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

/** Colours for the drawn widgets. [dim] and [text] follow the widget theme; [alpha] is the background opacity. */
class MeterColors(val dark: Boolean, val bg: Int, val text: Int, val dim: Int, val alpha: Int)

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
 * Each widget has four looks and picks one from the size the launcher gives it, so dragging the corners restyles it:
 * RING (about 2x2), STRIP (one row tall), SPLIT (wide) and STACK (large or tall).
 */
object MeterArt {
    private const val RED = 0xFFE3192D.toInt()
    private const val CYAN = 0xFF3DD6F5.toInt()
    private const val AMBER = 0xFFFFB020.toInt()
    private const val GREEN = 0xFF36E39A.toInt()
    private val nf: NumberFormat = NumberFormat.getIntegerInstance(Locale("en", "IN"))

    /** Width kept free for the refresh button: a strip loses it on the right, the other looks keep their top-right corner clear. */
    private const val BTN_W = 26f

    enum class Shape { RING, STRIP, SPLIT, STACK }

    fun shapeOf(w: Float, h: Float): Shape = when {
        h < 96f && w >= 1.7f * h -> Shape.STRIP
        w >= 1.55f * h && h < 170f -> Shape.SPLIT
        min(w, h) >= 170f || h >= 1.3f * w && h >= 150f -> Shape.STACK
        else -> Shape.RING
    }

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

    private fun fuelColor(pct: Double?): Int = when {
        pct == null -> CYAN
        pct < 15 -> RED
        pct < 30 -> AMBER
        else -> CYAN
    }

    private fun card(cv: Canvas, w: Float, h: Float, c: MeterColors) {
        if (c.alpha > 0) cv.drawRoundRect(RectF(0f, 0f, w, h), 24f, 24f, fill(withAlpha(c.bg, c.alpha / 255f)))
        cv.drawRoundRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), 24f, 24f, stroke(withAlpha(c.text, if (c.alpha > 0) 0.08f else 0.18f), 1f))
    }

    /** 270 degree gauge with glow, tick marks and an end dot. */
    private fun arc(cv: Canvas, cx: Float, cy: Float, r: Float, sw: Float, frac: Float, color: Int, c: MeterColors, ticks: Boolean = true) {
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        val track = withAlpha(c.text, 0.13f)
        cv.drawArc(rect, 135f, 270f, false, stroke(track, sw))
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
        for (i in 1..3) {
            val tx = x + w * i / 4f
            cv.drawLine(tx, y + h * 0.2f, tx, y + h * 0.8f, stroke(withAlpha(if (c.dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.35f), 1f, round = false))
        }
        if (marker != null) {
            val mx = x + w * marker.coerceIn(0f, 1f)
            cv.drawLine(mx, y - 3f, mx, y + h + 3f, stroke(AMBER, 2f))
        }
    }

    private fun pct(v: Double?) = v?.let { "${it.roundToInt()}%" } ?: "--"
    private fun km(v: Double?) = v?.let { "${nf.format(v.roundToInt())} km" } ?: "--"

    // ------------------------------------------------------------------ fuel meter

    fun fuel(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) {
        card(cv, w, h, c)
        val col = fuelColor(d.fuelPct)
        val frac = ((d.fuelPct ?: 0.0) / 100.0).toFloat()
        when (shapeOf(w, h)) {
            Shape.RING -> {
                val r = min(w, h) / 2f - 22f
                val cx = w / 2f
                val cy = h / 2f - 2f
                arc(cv, cx, cy, r, r * 0.17f, frac, col, c)
                txt(cv, pct(d.fuelPct), cx, cy + r * 0.2f, r * 0.62f, c.text, true, Paint.Align.CENTER, r * 1.3f)
                txt(cv, d.litres?.let { "${"%.1f".format(Locale.US, it)} L" } ?: "FUEL", cx, cy + r * 0.98f, max(9f, r * 0.24f), c.dim, false, Paint.Align.CENTER, r * 1.2f)
            }
            Shape.STRIP -> {
                val pad = 14f
                val ew = w - BTN_W
                val showRange = ew >= 160f
                txt(cv, pct(d.fuelPct), pad, h / 2f + 8f, min(h * 0.38f, 24f), col, true)
                val bx = pad + min(h * 0.38f, 24f) * 2.1f
                val rw = if (showRange) 70f else 0f
                bar(cv, bx, h / 2f - 5f, ew - bx - pad - rw - (if (showRange) 8f else 0f), 10f, frac, col, c, (d.limit / d.tank).toFloat())
                if (showRange) {
                    txt(cv, d.dteKm?.let { "${nf.format(it.roundToInt())}" } ?: "--", ew - pad, h / 2f + 3f, 17f, c.text, true, Paint.Align.RIGHT, rw)
                    txt(cv, "km range", ew - pad, h / 2f + 16f, 10f, c.dim, false, Paint.Align.RIGHT)
                }
            }
            Shape.SPLIT -> {
                val r = h / 2f - 14f
                val cx = r + 16f
                val cy = h / 2f - 2f
                arc(cv, cx, cy, r, r * 0.17f, frac, col, c)
                txt(cv, pct(d.fuelPct), cx, cy + r * 0.2f, r * 0.55f, c.text, true, Paint.Align.CENTER, r * 1.3f)
                txt(cv, "FUEL", cx, cy + r * 0.98f, 10f, c.dim, false, Paint.Align.CENTER)
                val x = cx + r + 22f
                val cw = w - x - 14f - BTN_W
                val rows = listOf("RANGE" to km(d.dteKm), "IN TANK" to (d.litres?.let { "${"%.1f".format(Locale.US, it)} L" } ?: "--"), "ODOMETER" to km(d.odoKm))
                val step = (h - 22f) / rows.size
                rows.forEachIndexed { i, (l, v) ->
                    val y0 = 12f + step * i
                    txt(cv, l, x, y0 + 10f, 9.5f, c.dim, true)
                    txt(cv, v, x, y0 + 10f + min(step - 12f, 19f), min(step * 0.62f, 19f), c.text, true, maxW = cw)
                }
            }
            Shape.STACK -> {
                val r = min(w / 2f - 24f, h * 0.36f)
                val cx = w / 2f
                val cy = 16f + r
                arc(cv, cx, cy, r, r * 0.16f, frac, col, c)
                txt(cv, pct(d.fuelPct), cx, cy + r * 0.2f, r * 0.55f, c.text, true, Paint.Align.CENTER, r * 1.3f)
                txt(cv, d.litres?.let { "${"%.1f".format(Locale.US, it)} L of ${d.tank.roundToInt()} L" } ?: "FUEL", cx, cy + r * 0.98f, 11f, c.dim, false, Paint.Align.CENTER, r * 1.5f)
                val y = cy + r + 22f
                val colW = (w - 28f) / 3f
                listOf("RANGE" to km(d.dteKm), "ODOMETER" to km(d.odoKm), "12V" to (d.battPct?.let { "$it%" } ?: "--")).forEachIndexed { i, (l, v) ->
                    txt(cv, l, 14f + colW * i, y, 9.5f, c.dim, true)
                    txt(cv, v, 14f + colW * i, y + 17f, 15f, c.text, true, maxW = colW - 4f)
                }
                if (h > y + 52f) txt(cv, tyreLine(d), 14f, h - 14f, 11f, if (d.tyreWarnAny || d.warn.any { it }) AMBER else c.dim, true, maxW = w - 28f)
            }
        }
    }

    private fun tyreLine(d: MeterData): String = when {
        d.tyreWarnAny || d.warn.any { it } -> "Tyres: check pressure"
        d.psi.all { it != null } -> "Tyres " + d.psi.joinToString(" · ") { "${it!!.roundToInt()}" } + " psi"
        d.tyreReported -> "Tyres: car says OK"
        else -> "Tyres: not reported"
    }

    // ------------------------------------------------------------------ dash (dual ring)

    fun dash(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) {
        card(cv, w, h, c)
        val fCol = fuelColor(d.fuelPct)
        val bCol = if ((d.battPct ?: 100) < 50) AMBER else GREEN
        val ff = ((d.fuelPct ?: 0.0) / 100.0).toFloat()
        val bf = ((d.battPct ?: 0) / 100f)
        fun rings(cx: Float, cy: Float, r: Float) {
            val sw = r * 0.15f
            ring(cv, cx, cy, r, sw, ff, fCol, c)
            ring(cv, cx, cy, r - sw * 1.55f, sw * 0.7f, bf, bCol, c)
            txt(cv, d.dteKm?.let { nf.format(it.roundToInt()) } ?: "--", cx, cy + r * 0.12f, r * 0.42f, c.text, true, Paint.Align.CENTER, r * 1.1f)
            txt(cv, "km range", cx, cy + r * 0.42f, max(8f, r * 0.17f), c.dim, false, Paint.Align.CENTER, r * 1.1f)
        }
        val state = when (d.locked) { true -> "Locked"; false -> "Unlocked"; null -> "Status unknown" } + if (d.engine) " · Engine on" else ""
        val stateCol = when (d.locked) { true -> GREEN; false -> AMBER; null -> c.dim }
        when (shapeOf(w, h)) {
            Shape.RING -> rings(w / 2f, h / 2f, min(w, h) / 2f - 22f)
            Shape.STRIP -> {
                val pad = 14f
                txt(cv, d.dteKm?.let { nf.format(it.roundToInt()) } ?: "--", pad, h / 2f + 8f, 22f, c.text, true)
                txt(cv, "km", pad + 52f, h / 2f + 8f, 11f, c.dim)
                val bx = pad + 84f
                val bw = w - BTN_W - bx - pad
                txt(cv, "FUEL ${pct(d.fuelPct)}", bx, h / 2f - 8f, 9.5f, c.dim, true, maxW = bw)
                bar(cv, bx, h / 2f - 4f, bw, 6f, ff, fCol, c)
                txt(cv, "12V ${d.battPct?.let { "$it%" } ?: "--"}", bx, h / 2f + 18f, 9.5f, c.dim, true, maxW = bw)
                bar(cv, bx, h / 2f + 22f, bw, 6f, bf, bCol, c)
            }
            Shape.SPLIT -> {
                val r = h / 2f - 14f
                rings(r + 16f, h / 2f, r)
                val x = 2f * r + 40f
                val cw = w - x - 14f - BTN_W
                txt(cv, d.title.ifBlank { "Creta" }, x, 26f, 14f, c.text, true, maxW = cw)
                txt(cv, state, x, 44f, 11f, stateCol, true, maxW = cw)
                val y = h / 2f + 10f
                txt(cv, "FUEL", x, y, 9.5f, c.dim, true); txt(cv, pct(d.fuelPct), x, y + 17f, 17f, fCol, true)
                txt(cv, "12V", x + cw / 2f, y, 9.5f, c.dim, true); txt(cv, d.battPct?.let { "$it%" } ?: "--", x + cw / 2f, y + 17f, 17f, bCol, true)
                if (h > 120f) txt(cv, km(d.odoKm), x, h - 14f, 11f, c.dim, false, maxW = cw)
            }
            Shape.STACK -> {
                val r = min(w / 2f - 26f, h * 0.34f)
                rings(w / 2f, 18f + r, r)
                var y = 18f + 2f * r + 24f
                txt(cv, state, w / 2f, y, 13f, stateCol, true, Paint.Align.CENTER, w - 28f)
                y += 26f
                val colW = (w - 28f) / 3f
                listOf(Triple("FUEL", pct(d.fuelPct), fCol), Triple("12V", d.battPct?.let { "$it%" } ?: "--", bCol), Triple("ODO", km(d.odoKm), c.text)).forEachIndexed { i, (l, v, col) ->
                    txt(cv, l, 14f + colW * i, y, 9.5f, c.dim, true)
                    txt(cv, v, 14f + colW * i, y + 17f, 15f, col, true, maxW = colW - 4f)
                }
                if (h > y + 44f) txt(cv, d.ageText, 14f, h - 12f, 10f, c.dim, maxW = w - 28f)
            }
        }
    }

    // ------------------------------------------------------------------ next fill-up planner

    fun planner(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) {
        card(cv, w, h, c)
        val kmLeft = d.kmToLimit
        val litres = d.litres
        val span = max(1.0, d.tank - d.limit)
        val frac = if (litres != null) ((litres - d.limit) / span).toFloat().coerceIn(0f, 1f) else 0f
        val col = when { kmLeft == null -> c.dim; kmLeft <= 0.5 -> RED; frac < 0.25f -> AMBER; else -> GREEN }
        val big = kmLeft?.let { if (it <= 0.5) "NOW" else nf.format(it.roundToInt()) } ?: "--"
        val sub = if (kmLeft != null && kmLeft > 0.5) "km to fill-up" else "fill up"
        val fillAt = d.fillAtOdo?.let { "${nf.format(it.roundToInt())} km" } ?: "--"
        val eco = d.kmpl?.let { "${"%.1f".format(Locale.US, it)} km/L" + if (d.rough) " (rough)" else "" } ?: "log a fill-up"
        val cost = d.costPerKm?.let { "₹${"%.2f".format(Locale.US, it)}/km" } ?: "--"
        when (shapeOf(w, h)) {
            Shape.RING -> {
                val r = min(w, h) / 2f - 22f
                ring(cv, w / 2f, h / 2f, r, r * 0.15f, frac, col, c)
                txt(cv, big, w / 2f, h / 2f + r * 0.14f, r * 0.45f, c.text, true, Paint.Align.CENTER, r * 1.2f)
                txt(cv, sub, w / 2f, h / 2f + r * 0.46f, max(8f, r * 0.19f), c.dim, false, Paint.Align.CENTER, r * 1.2f)
            }
            Shape.STRIP -> {
                val pad = 14f
                val ew = w - BTN_W
                txt(cv, "FILL UP AT", pad, h / 2f - 8f, 9.5f, c.dim, true)
                txt(cv, fillAt, pad, h / 2f + 12f, 17f, c.text, true, maxW = ew * 0.45f)
                val bx = pad + min(ew * 0.45f, 120f) + 12f
                val bw = ew - bx - pad
                if (bw > 40f) {
                    txt(cv, if (kmLeft != null && kmLeft > 0.5) "in ${nf.format(kmLeft.roundToInt())} km" else "now", bx, h / 2f - 8f, 10.5f, col, true, maxW = bw)
                    bar(cv, bx, h / 2f - 2f, bw, 8f, frac, col, c)
                }
            }
            Shape.SPLIT -> {
                val r = h / 2f - 14f
                ring(cv, r + 16f, h / 2f, r, r * 0.15f, frac, col, c)
                txt(cv, big, r + 16f, h / 2f + r * 0.14f, r * 0.42f, c.text, true, Paint.Align.CENTER, r * 1.2f)
                txt(cv, sub, r + 16f, h / 2f + r * 0.44f, max(8f, r * 0.18f), c.dim, false, Paint.Align.CENTER, r * 1.2f)
                val x = 2f * r + 40f
                val cw = w - x - 14f - BTN_W
                txt(cv, "FILL UP AT", x, 24f, 9.5f, c.dim, true)
                txt(cv, fillAt, x, 46f, 21f, c.text, true, maxW = cw)
                txt(cv, eco, x, h - 34f, 11.5f, c.dim, false, maxW = cw)
                txt(cv, cost, x, h - 16f, 11.5f, c.dim, false, maxW = cw)
            }
            Shape.STACK -> {
                val r = min(w / 2f - 26f, h * 0.3f)
                ring(cv, w / 2f, 20f + r, r, r * 0.14f, frac, col, c)
                txt(cv, big, w / 2f, 20f + r + r * 0.14f, r * 0.42f, c.text, true, Paint.Align.CENTER, r * 1.2f)
                txt(cv, sub, w / 2f, 20f + r + r * 0.46f, max(9f, r * 0.17f), c.dim, false, Paint.Align.CENTER, r * 1.2f)
                var y = 20f + 2f * r + 26f
                txt(cv, "FILL UP AT", 14f, y, 9.5f, c.dim, true)
                txt(cv, fillAt, 14f, y + 22f, 22f, c.text, true, maxW = w - 28f)
                y += 46f
                val half = (w - 28f) / 2f
                txt(cv, "ECONOMY", 14f, y, 9.5f, c.dim, true); txt(cv, eco, 14f, y + 16f, 13f, c.text, true, maxW = half - 4f)
                txt(cv, "COST", 14f + half, y, 9.5f, c.dim, true); txt(cv, cost, 14f + half, y + 16f, 13f, c.text, true, maxW = half - 4f)
                if (litres != null && h > y + 44f) txt(cv, "${"%.1f".format(Locale.US, litres)} L in tank, limit ${d.limit} L", 14f, h - 12f, 10.5f, c.dim, maxW = w - 28f)
            }
        }
    }

    // ------------------------------------------------------------------ tyres

    private fun wheelColor(d: MeterData, i: Int, c: MeterColors): Int = when {
        d.warn[i] || d.tyreWarnAny -> AMBER
        d.psi[i] != null || d.tyreReported -> GREEN
        else -> withAlpha(c.text, 0.3f)
    }

    private fun psiText(d: MeterData, i: Int): String = d.psi[i]?.let { "${it.roundToInt()}" } ?: if (d.warn[i]) "LOW" else "--"

    /** Top view of the car: body, glass, four wheels coloured by state, optional pressure labels beside them. */
    private fun car(cv: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, d: MeterData, c: MeterColors, labels: Boolean) {
        val body = RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
        cv.drawRoundRect(body, bw * 0.3f, bw * 0.3f, fill(withAlpha(c.text, 0.1f)))
        cv.drawRoundRect(body, bw * 0.3f, bw * 0.3f, stroke(withAlpha(c.text, 0.3f), 1.2f))
        // glass
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
        val warnCol = if (d.tyreWarnAny || d.warn.any { it }) AMBER else if (d.tyreReported || d.psi.any { it != null }) GREEN else c.dim
        when (shapeOf(w, h)) {
            Shape.RING -> {
                val bh = min(h - 24f, w * 1.1f)
                car(cv, w / 2f, h / 2f, bh * 0.46f, bh, d, c, labels = w >= 130f)
            }
            Shape.STRIP -> {
                val pad = 12f
                val cw = (w - BTN_W - pad * 2f) / 4f
                listOf("FL", "FR", "RL", "RR").forEachIndexed { i, l ->
                    val x = pad + cw * i
                    cv.drawCircle(x + 5f, h / 2f - 12f, 4f, fill(wheelColor(d, i, c)))
                    txt(cv, l, x + 14f, h / 2f - 8f, 9.5f, c.dim, true)
                    txt(cv, psiText(d, i), x + 2f, h / 2f + 14f, 17f, c.text, true, maxW = cw - 4f)
                }
            }
            Shape.SPLIT -> {
                val bh = h - 26f
                car(cv, bh * 0.23f + 30f, h / 2f, bh * 0.46f, bh, d, c, labels = false)
                val x = bh * 0.46f + 70f
                val cw = w - x - 14f - BTN_W
                txt(cv, "TYRE PRESSURE", x, 24f, 9.5f, c.dim, true)
                txt(cv, status.removePrefix("Tyres: ").removePrefix("Tyres ").replaceFirstChar { it.uppercase() }, x, 44f, 13f, warnCol, true, maxW = cw)
                val half = cw / 2f
                listOf("FL", "FR", "RL", "RR").forEachIndexed { i, l ->
                    val gx = x + half * (i % 2)
                    val gy = 64f + (h - 90f) / 2f * (i / 2)
                    txt(cv, l, gx, gy, 9.5f, c.dim, true)
                    txt(cv, psiText(d, i), gx, gy + 17f, 17f, wheelColor(d, i, c).let { if (d.psi[i] != null) c.text else it }, true)
                }
            }
            Shape.STACK -> {
                val bh = min(h - 70f, w * 1.2f)
                car(cv, w / 2f, 14f + bh / 2f, bh * 0.46f, bh, d, c, labels = true)
                txt(cv, status, w / 2f, h - 14f, 12f, warnCol, true, Paint.Align.CENTER, w - 28f)
            }
        }
    }

    // ------------------------------------------------------------------ refresh button

    /**
     * Small round refresh button. A strip carries it at the right edge, every other look in the top-right corner; the real tap
     * area is a transparent view in widget_canvas.xml at the same spot. Amber while syncing, red when the last sync failed.
     */
    fun refreshButton(cv: Canvas, w: Float, h: Float, c: MeterColors, d: MeterData) {
        val strip = shapeOf(w, h) == Shape.STRIP
        val cx = w - 20f
        val cy = if (strip) h / 2f else 20f
        val col = when (d.sync) { 1 -> AMBER; 2 -> RED; else -> c.text }
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
