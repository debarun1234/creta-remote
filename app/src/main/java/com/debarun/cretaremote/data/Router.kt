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
    fun decode(s: String): List<Pair<Double, Double>> {
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
            out += lat / 1e5 to lon / 1e5
        }
        return out
    }

    /** About 11 m resolution, so a car parked in the same spot twice shares one lookup. */
    fun key(a: Pair<Double, Double>, b: Pair<Double, Double>): String =
        "rt_" + String.format(Locale.US, "%.4f,%.4f;%.4f,%.4f", a.first, a.second, b.first, b.second)

    /** The encoded route from [a] to [b], or null when no server answered. */
    suspend fun fetch(a: Pair<Double, Double>, b: Pair<Double, Double>): String? = withContext(Dispatchers.IO) {
        for (h in hosts) {
            val url = "$h/route/v1/driving/${a.second},${a.first};${b.second},${b.first}?overview=full&geometries=polyline"
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
