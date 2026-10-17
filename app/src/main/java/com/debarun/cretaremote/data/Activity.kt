package com.debarun.cretaremote.data

import org.json.JSONArray
import org.json.JSONObject

enum class Outcome { SUCCESS, FAILED, CANCELLED, UNCONFIRMED }

/** One command the user sent to the car and how it ended. */
data class Activity(
    val ts: Long,
    val cmd: Cmd,
    val outcome: Outcome,
    val message: String,
    /** Milliseconds until Hyundai accepted the request (null if it never did). */
    val sendMs: Long?,
    /** Milliseconds from the tap to the final result. */
    val totalMs: Long,
    /** Which Bluelink route carried the command, when that is worth knowing. */
    val via: String? = null,
)

object ActivityLog {
    const val MAX = 100

    fun encode(list: List<Activity>): String = JSONArray().also { arr ->
        list.forEach { a ->
            arr.put(
                JSONObject().put("t", a.ts).put("c", a.cmd.name).put("o", a.outcome.name).put("m", a.message)
                    .put("d", a.totalMs).apply {
                        a.sendMs?.let { put("s", it) }
                        a.via?.let { put("v", it) }
                    },
            )
        }
    }.toString()

    fun decode(json: String?): List<Activity> = try {
        val arr = JSONArray(json ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val cmd = runCatching { Cmd.valueOf(o.getString("c")) }.getOrNull() ?: return@mapNotNull null
            val out = runCatching { Outcome.valueOf(o.getString("o")) }.getOrNull() ?: return@mapNotNull null
            Activity(
                o.getLong("t"), cmd, out, o.optString("m"),
                if (o.has("s")) o.optLong("s") else null,
                o.optLong("d"),
                if (o.has("v")) o.optString("v") else null,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
