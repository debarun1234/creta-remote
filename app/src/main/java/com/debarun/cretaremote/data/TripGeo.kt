package com.debarun.cretaremote.data

import org.json.JSONArray
import org.json.JSONObject

/** Where a trip began and ended, and the path between if Bluelink sent one. */
data class TripGeo(val start: Pair<Double, Double>?, val end: Pair<Double, Double>?, val path: List<Pair<Double, Double>>)

/**
 * Looks through a trip exactly as Bluelink sent it for positions: pairs of latitude/longitude fields (under any naming), lists of
 * points, and encoded route lines. Bluelink's own app shows each trip's start, goal and route, so that data has to come from
 * somewhere; this finds it without needing to know the field names in advance.
 */
object TripGeoParser {
    private class Found(val where: String, val lat: Double, val lon: Double)

    private val startWords = listOf("start", "origin", "begin", "depart", "src", "source")
    private val endWords = listOf("end", "goal", "dest", "arriv", "finish", "stop")
    private val lineKeys = listOf("route", "path", "poly", "geometry", "line", "track", "shape", "waypoint")

    private fun valid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    private fun num(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.optString(k).toDoubleOrNull() else null

    fun parse(raw: String?): TripGeo? {
        if (raw.isNullOrBlank()) return null
        val root = try { JSONObject(raw) } catch (e: Exception) { return null }
        val pts = mutableListOf<Found>()
        val lines = mutableListOf<List<Pair<Double, Double>>>()
        walk(root, "", pts, lines)
        val path = lines.maxByOrNull { it.size }.orEmpty()
        var start = pts.firstOrNull { f -> startWords.any { f.where.contains(it) } }
        var end = pts.lastOrNull { f -> endWords.any { f.where.contains(it) } }
        if (start == null && end == null && pts.size >= 2) { start = pts.first(); end = pts.last() }
        val s = start?.let { it.lat to it.lon } ?: path.firstOrNull()
        val e = end?.let { it.lat to it.lon } ?: path.lastOrNull()
        if (s == null && e == null && path.isEmpty()) return null
        return TripGeo(s, e, path)
    }

    /**
     * The `tripinfo/detail` answer: `tripList` is the timed positions the car passed (the official app draws its route from them),
     * `tripStartCoord` / `tripEndCoord` are where it began and ended. Returns null when no usable position is in it.
     */
    fun parseDetail(root: JSONObject?): TripGeo? {
        if (root == null) return null
        val list = findList(root) ?: return null
        val path = ArrayList<Pair<Double, Double>>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val la = num(o, "lat") ?: continue
            val lo = num(o, "lon") ?: num(o, "lng") ?: continue
            if (!valid(la, lo)) continue
            val pt = la to lo
            if (path.lastOrNull() != pt) path += pt
        }
        val info = root.optJSONObject("tripInfo") ?: root
        fun coord(k: String): Pair<Double, Double>? {
            val o = info.optJSONObject(k) ?: return null
            val la = num(o, "lat") ?: return null
            val lo = num(o, "lon") ?: num(o, "lng") ?: return null
            return if (valid(la, lo)) la to lo else null
        }
        val s = coord("tripStartCoord") ?: path.firstOrNull()
        val e = coord("tripEndCoord") ?: path.lastOrNull()
        if (path.isEmpty() && s == null && e == null) return null
        return TripGeo(s, e, path)
    }

    private fun findList(node: Any?): JSONArray? {
        when (node) {
            is JSONObject -> {
                node.optJSONArray("tripList")?.let { return it }
                for (k in node.keys()) findList(node.opt(k))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findList(node.opt(i))?.let { return it }
        }
        return null
    }

    private fun walk(node: Any?, where: String, pts: MutableList<Found>, lines: MutableList<List<Pair<Double, Double>>>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys().asSequence().toList()
                for (k in keys) {
                    val lk = k.lowercase()
                    val prefix = when {
                        lk.endsWith("latitude") -> lk.removeSuffix("latitude")
                        lk.endsWith("lat") -> lk.removeSuffix("lat")
                        else -> continue
                    }
                    val lonKey = keys.firstOrNull { it.lowercase() in setOf(prefix + "lon", prefix + "lng", prefix + "long", prefix + "longitude") } ?: continue
                    val la = num(node, k) ?: continue
                    val lo = num(node, lonKey) ?: continue
                    if (valid(la, lo)) pts += Found("$where/$prefix".lowercase(), la, lo)
                }
                for (k in keys) {
                    val v = node.opt(k)
                    val w = "$where/$k".lowercase()
                    if (v is String) {
                        if (lineKeys.any { w.endsWith(it) || k.lowercase().contains(it) } && v.length >= 12) decodeLine(v)?.let { lines += it }
                    } else walk(v, w, pts, lines)
                }
            }
            is JSONArray -> {
                val pairs = mutableListOf<Pair<Double, Double>>()
                for (i in 0 until node.length()) {
                    val e = node.opt(i)
                    if (e is JSONArray && e.length() >= 2) {
                        val a = e.optString(0).toDoubleOrNull()
                        val b = e.optString(1).toDoubleOrNull()
                        if (a != null && b != null) pairs += orient(a, b) ?: continue
                    }
                }
                if (pairs.size >= 2 && pairs.size == node.length()) { lines += pairs; return }
                for (i in 0 until node.length()) walk(node.opt(i), "$where[$i]", pts, lines)
            }
        }
    }

    /** A [first, second] pair as (lat, lon): GeoJSON puts longitude first, most other things latitude first. */
    private fun orient(a: Double, b: Double): Pair<Double, Double>? = when {
        valid(a, b) && a in 5.0..40.0 && b in 60.0..100.0 -> a to b
        valid(b, a) && b in 5.0..40.0 && a in 60.0..100.0 -> b to a
        valid(a, b) -> a to b
        else -> null
    }

    /** An encoded line (the usual 5 or 6 decimal kind) when the text decodes to a believable run of points. */
    private fun decodeLine(s: String): List<Pair<Double, Double>>? {
        for (precision in listOf(5, 6)) {
            val pts = try { Router.decode(s, precision) } catch (e: Exception) { continue }
            if (pts.size < 2) continue
            if (pts.any { !valid(it.first, it.second) }) continue
            val jumpy = pts.zipWithNext().any { (x, y) -> Math.abs(x.first - y.first) > 1.0 || Math.abs(x.second - y.second) > 1.0 }
            if (!jumpy) return pts
        }
        return null
    }

    /** A short description of what was found, for the diagnostics. */
    fun describe(raw: String?): String {
        val g = parse(raw) ?: return "no positions found"
        return "start=${g.start != null} end=${g.end != null} path points=${g.path.size}"
    }
}
