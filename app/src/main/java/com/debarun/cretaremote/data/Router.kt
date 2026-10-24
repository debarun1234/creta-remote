package com.debarun.cretaremote.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Road routes between two points from OpenStreetMap routing servers (free, no key), so a day's map follows streets instead of
 * straight lines. Answers are kept (as the compact encoded line) so each pair of places is only ever looked up once.
 */
object Router {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private val hosts = listOf("https://routing.openstreetmap.de/routed-car", "https://router.project-osrm.org")

    /** Google's encoded-polyline format (5 decimals), which both servers return. */
    fun decode(s: String, precision: Int = 5): List<Pair<Double, Double>> {
        val scale = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0
        var lat = 0
        var lon = 0
        while (i < s.length) {
            for (k in 0..1) {
                var shift = 0
                var result = 0
                var b: Int
                do {
                    b = s[i++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20 && i < s.length)
                val d = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
                if (k == 0) lat += d else lon += d
            }
            out += lat / scale to lon / scale
        }
        return out
    }

    /** The reverse of [decode], for tests and for storing a line compactly. */
    fun geometryOf(pts: List<Pair<Double, Double>>, precision: Int = 5): String {
        val scale = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var pLat = 0L
        var pLon = 0L
        fun put(v0: Long) {
            var v = if (v0 < 0) (v0 shl 1).inv() else v0 shl 1
            while (v >= 0x20) { sb.append(((0x20L or (v and 0x1f)) + 63).toInt().toChar()); v = v shr 5 }
            sb.append((v + 63).toInt().toChar())
        }
        for ((la, lo) in pts) {
            val a = Math.round(la * scale)
            val b = Math.round(lo * scale)
            put(a - pLat); put(b - pLon)
            pLat = a; pLon = b
        }
        return sb.toString()
    }

    /** About 11 m resolution, so a car parked in the same spot twice shares one lookup. */
    fun key(a: Pair<Double, Double>, b: Pair<Double, Double>): String =
        "rt_" + String.format(Locale.US, "%.4f,%.4f;%.4f,%.4f", a.first, a.second, b.first, b.second)

    /** The encoded route from [a] to [b], or null when no server answered. */
    suspend fun fetch(a: Pair<Double, Double>, b: Pair<Double, Double>, mapplsKey: String = ""): String? = withContext(Dispatchers.IO) {
        // MapmyIndia (Mappls) first when a key was entered, since that is what Bluelink's own app draws its trips with; OpenStreetMap otherwise
        val urls = buildList {
            if (mapplsKey.isNotBlank()) add("https://apis.mappls.com/advancedmaps/v1/${mapplsKey.trim()}/route_adv/driving/${a.second},${a.first};${b.second},${b.first}?geometries=polyline&overview=full")
            for (h in hosts) add("$h/route/v1/driving/${a.second},${a.first};${b.second},${b.first}?overview=full&geometries=polyline")
        }
        for (url in urls) {
            val g = try {
                http.newCall(Request.Builder().url(url).header("User-Agent", "creta-remote-app (personal car app)").build()).execute().use { r ->
                    if (!r.isSuccessful) null else geometry(r.body?.string().orEmpty())
                }
            } catch (e: Exception) {
                null
            }
            if (g != null) return@withContext g
        }
        null
    }

    fun geometry(json: String): String? = try {
        val o = JSONObject(json)
        if (o.optString("code") != "Ok") null
        else o.optJSONArray("routes")?.optJSONObject(0)?.optString("geometry")?.takeIf { it.length >= 4 }
    } catch (e: Exception) {
        null
    }
}
