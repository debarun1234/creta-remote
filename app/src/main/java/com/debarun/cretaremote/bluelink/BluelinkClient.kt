package com.debarun.cretaremote.bluelink

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Client for the Hyundai Bluelink **India** cloud (the API the official app talks to).
 *
 * The API is unofficial and undocumented; endpoints, constants and the stamp algorithm follow the
 * community-reverse-engineered `hyundai_kia_connect_api` (India / Hyundai). Everything is
 * blocking OkHttp wrapped in [Dispatchers.IO].
 */
class BluelinkClient {

    private companion object {
        const val BASE_DOMAIN = "prd.in-ccapi.hyundai.connected-car.io"
        const val BASE = "https://$BASE_DOMAIN:8080"
        const val USER = "$BASE/api/v1/user/"
        const val SPA = "$BASE/api/v1/spa/"
        const val SPA2 = "$BASE/api/v2/spa/"
        const val SERVICE_ID = "e5b3f6d0-7f83-43c9-aff3-a254db7af368"
        const val APP_ID = "5a27df80-4ca1-4154-8c09-6f4029d91cf7"
        const val CFB_B64 = "RFtoRq/vDXJmRndoZaZQyfOot7OrIqGVFj96iY2WL3yyH5Z/pUvlUhqmCxD2t+D65SQ="
        const val BASIC =
            "Basic ZTViM2Y2ZDAtN2Y4My00M2M5LWFmZjMtYTI1NGRiN2FmMzY4OjVKRk9DcjZDMjRPZk96bERxWnA3RXdxcmtMMFd3MDRVYXhjRGlFNlVkM3FJNVNFNA=="
        const val UA = "okhttp/3.12.0"
        val JSON = "application/json".toMediaType()
        val FORM = "application/x-www-form-urlencoded".toMediaType()
    }

    private val cfb: ByteArray = Base64.decode(CFB_B64, Base64.DEFAULT)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ---------------------------------------------------------------- auth

    suspend fun login(email: String, password: String): Session = withContext(Dispatchers.IO) {
        val deviceId = registerDevice()
        val jar = MemoryCookieJar()
        val c = http.newBuilder().cookieJar(jar).build()

        // 1) seed cookies
        val seedCode = c.newCall(
            Request.Builder().url(
                "${USER}oauth2/authorize?response_type=code&state=test&client_id=$SERVICE_ID&redirect_uri=${USER}oauth2/redirect",
            ).header("User-Agent", UA).build(),
        ).execute().use { it.code }

        // 2) sign in -> redirect url carrying the auth code
        val body = JSONObject().put("email", email).put("password", password).toString()
        var signinCode = 0
        val signin = c.newCall(
            Request.Builder().url("${USER}signin")
                .header("User-Agent", UA)
                .post(body.toByteArray().toRequestBody(JSON)).build(),
        ).execute().use { r -> signinCode = r.code; r.body?.string().orEmpty() }
        val json = try { JSONObject(signin) } catch (e: Exception) { null }
        val redirect = json?.optString("redirectUrl").orEmpty()
        val code = if (redirect.isNotEmpty()) Uri.parse(redirect).getQueryParameter("code") else null
        if (code.isNullOrEmpty()) {
            // Bluelink answers {"step":5} when the account password has expired and must be changed in the official app (confirmed on a real account).
            if (json != null && redirect.isEmpty() && json.has("step")) {
                throw BluelinkException("Bluelink says your password has expired (step ${json.optInt("step")}). Change it in the official Bluelink app, then sign in here with the new password.", auth = true)
            }
            // Say what the server actually answered, so "wrong password" is not blamed for an outage, a lockout or an API change.
            val said = json?.let { j ->
                listOf("errMsg", "errorMessage", "error_description", "message", "resMsg", "error", "errorCode", "resCode", "retCode")
                    .mapNotNull { k -> j.optString(k).takeIf { it.isNotBlank() }?.let { "$k=$it" } }.joinToString(", ")
            }.orEmpty().ifEmpty { signin.replace(Regex("\\s+"), " ").take(120) }
            val redirectErr = if (redirect.isNotEmpty()) Uri.parse(redirect).getQueryParameter("error") else null
            val detail = listOfNotNull("HTTP $signinCode", "seed $seedCode".takeIf { seedCode !in 200..399 }, redirectErr?.let { "redirect error=$it" }, said.takeIf { it.isNotEmpty() })
                .joinToString(" · ")
            throw BluelinkException("Sign-in failed ($detail). If your email and password are right, Bluelink may be blocking or down. Try the official app, then wait and retry.", auth = true)
        }

        // 3) exchange code for access token
        val form = "grant_type=authorization_code&redirect_uri=https%3A%2F%2F$BASE_DOMAIN%3A8080%2Fapi%2Fv1%2Fuser%2Foauth2%2Fredirect&code=$code"
        val tok = c.newCall(
            Request.Builder().url("${USER}oauth2/token")
                .header("Authorization", BASIC)
                .header("Stamp", stamp())
                .header("User-Agent", UA)
                .post(form.toByteArray().toRequestBody(FORM)).build(),
        ).execute().use { r -> JSONObject(r.body?.string().orEmpty()) }
        val access = tok.optString("access_token")
        if (access.isEmpty()) throw BluelinkException("Bluelink did not return an access token.", auth = true)
        Session("${tok.optString("token_type", "Bearer")} $access", deviceId, System.currentTimeMillis())
    }

    /** Re-registers the (virtual) push device. Needed when Bluelink answers resCode 4002. */
    suspend fun newDeviceId(): String = withContext(Dispatchers.IO) { registerDevice() }

    private fun registerDevice(): String {
        val hex = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val payload = JSONObject().put("pushRegId", hex).put("pushType", "GCM").put("uuid", UUID.randomUUID().toString())
        val req = Request.Builder().url("${SPA}notifications/register")
            .header("ccsp-service-id", SERVICE_ID)
            .header("ccsp-application-id", APP_ID)
            .header("Stamp", stamp())
            .header("User-Agent", UA)
            .post(payload.toString().toByteArray().toRequestBody("application/json;charset=UTF-8".toMediaType()))
            .build()
        val json = exec(req)
        return json.optJSONObject("resMsg")?.optString("deviceId").orEmpty()
            .ifEmpty { throw BluelinkException("Device registration failed.") }
    }

    private fun stamp(): String {
        val raw = "$APP_ID:${System.currentTimeMillis() / 1000}".toByteArray()
        val n = minOf(raw.size, cfb.size)
        val out = ByteArray(n) { i -> (cfb[i].toInt() xor raw[i].toInt()).toByte() }
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun Request.Builder.withAuth(s: Session, ccs2: Int = 0): Request.Builder = this
        .header("Authorization", s.accessToken)
        .header("ccsp-service-id", SERVICE_ID)
        .header("ccsp-application-id", APP_ID)
        .header("ccsp-device-id", s.deviceId)
        .header("Ccuccs2protocolsupport", ccs2.toString())
        .header("User-Agent", UA)

    /** PIN -> short-lived control token, required by some control endpoints. */
    suspend fun controlToken(s: Session, pin: String): Session = withContext(Dispatchers.IO) {
        if (s.controlToken != null && s.controlExpiry > System.currentTimeMillis() / 1000 + 10) return@withContext s
        if (pin.isBlank()) throw BluelinkException("Enter your Bluelink PIN in Settings to send this command.")
        val body = JSONObject().put("deviceId", s.deviceId).put("pin", pin).toString()
        val req = Request.Builder().url("${USER}pin?token=")
            .header("Authorization", s.accessToken)
            .header("User-Agent", UA)
            .put(body.toByteArray().toRequestBody(JSON)).build()
        val json = http.newCall(req).execute().use { r -> JSONObject(r.body?.string().orEmpty()) }
        val ct = json.optString("controlToken")
        if (ct.isEmpty()) throw BluelinkException("PIN rejected by Bluelink. Check the PIN in Settings.")
        s.copy(controlToken = "Bearer $ct", controlExpiry = System.currentTimeMillis() / 1000 + json.optLong("expiresTime", 600))
    }

    // ---------------------------------------------------------------- reads

    suspend fun vehicles(s: Session): List<Vehicle> = withContext(Dispatchers.IO) {
        val json = exec(Request.Builder().url("${SPA}vehicles").withAuth(s).get().build())
        val arr = json.optJSONObject("resMsg")?.optJSONArray("vehicles") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            Vehicle(
                id = e.optString("vehicleId"),
                name = e.optString("nickname"),
                model = e.optString("vehicleName"),
                vin = e.optString("vin"),
                regDate = e.optString("regDate"),
                ccs2 = e.optInt("ccuCCS2ProtocolSupport", 0),
            )
        }
    }

    /** Last status the car pushed to the cloud. Fast, does not wake the car. */
    suspend fun statusCached(s: Session, v: Vehicle): JSONObject = withContext(Dispatchers.IO) {
        val path = if (v.ccs2 == 0) "status/latest" else "ccs2/carstatus/latest"
        exec(Request.Builder().url("${SPA}vehicles/${v.id}/$path").withAuth(s, v.ccs2).get().build()).optJSONObject("resMsg") ?: JSONObject()
    }

    /** Asks the car for fresh data (wakes the modem). Slower: 5-30 s. */
    suspend fun statusLive(s: Session, v: Vehicle): JSONObject = withContext(Dispatchers.IO) {
        val path = if (v.ccs2 == 0) "status" else "ccs2/carstatus"
        exec(Request.Builder().url("${SPA}vehicles/${v.id}/$path").withAuth(s, v.ccs2).get().build()).optJSONObject("resMsg") ?: JSONObject()
    }

    suspend fun parkLocation(s: Session, v: Vehicle): JSONObject? = withContext(Dispatchers.IO) {
        try {
            exec(Request.Builder().url("${SPA}vehicles/${v.id}/location/park").withAuth(s, v.ccs2).get().build()).optJSONObject("resMsg")
        } catch (e: BluelinkException) {
            if (e.auth) throw e else null
        }
    }

    suspend fun maintenance(s: Session, v: Vehicle): JSONObject? = withContext(Dispatchers.IO) {
        try {
            exec(Request.Builder().url("${SPA}vehicles/${v.id}/setting/alert/maintenance").withAuth(s, v.ccs2).get().build()).optJSONObject("resMsg")
        } catch (e: BluelinkException) {
            if (e.auth) throw e else null
        }
    }

    /** Month overview: summary + the days that had trips. [yyyymm] e.g. "202610". */
    suspend fun tripMonth(s: Session, v: Vehicle, yyyymm: String): JSONObject? = withContext(Dispatchers.IO) {
        val p = JSONObject().put("tripPeriodType", 0).put("setTripMonth", yyyymm)
        try {
            exec(Request.Builder().url("${SPA}vehicles/${v.id}/tripinfo").withAuth(s, v.ccs2).post(p.toString().toByteArray().toRequestBody(JSON)).build()).optJSONObject("resMsg")
        } catch (e: BluelinkException) {
            if (e.auth) throw e else null
        }
    }

    suspend fun tripDay(s: Session, v: Vehicle, yyyymmdd: String): JSONObject? = withContext(Dispatchers.IO) {
        val p = JSONObject().put("tripPeriodType", 1).put("setTripDay", yyyymmdd)
        try {
            exec(Request.Builder().url("${SPA}vehicles/${v.id}/tripinfo").withAuth(s, v.ccs2).post(p.toString().toByteArray().toRequestBody(JSON)).build()).optJSONObject("resMsg")
        } catch (e: BluelinkException) {
            if (e.auth) throw e else null
        }
    }

    // ---------------------------------------------------------------- commands (return msgId)

    suspend fun lock(s: Session, v: Vehicle, lock: Boolean): String = withContext(Dispatchers.IO) {
        val action = if (lock) "close" else "open"
        val req = if (v.ccs2 == 0) {
            val p = JSONObject().put("action", action).put("deviceId", s.deviceId)
            Request.Builder().url("${SPA}vehicles/${v.id}/control/door").withAuth(s, v.ccs2).post(p.toString().toByteArray().toRequestBody(JSON)).build()
        } else {
            val p = JSONObject().put("command", action)
            Request.Builder().url("${SPA2}vehicles/${v.id}/ccs2/control/door").withControl(s, v.ccs2).post(p.toString().toByteArray().toRequestBody(JSON)).build()
        }
        exec(req).optString("msgId")
    }

    /** Remote engine start with climate. [tempC] 14.0..29.5 in 0.5 steps; [minutes] ignition-on duration. */
    suspend fun startEngine(s: Session, v: Vehicle, tempC: Double, minutes: Int, defrost: Boolean, heating: Boolean): String =
        withContext(Dispatchers.IO) {
            val idx = ((tempC.coerceIn(14.0, 29.5) - 14.0) / 0.5).toInt()
            val hex = Integer.toHexString(idx).uppercase().padStart(2, '0') + "H"
            val p = JSONObject()
                .put("action", "start")
                .put("hvacType", 1)
                .put("options", JSONObject().put("defrost", defrost).put("heating1", if (heating) 1 else 0).put("igniOnDuration", minutes))
                .put("tempCode", hex)
                .put("unit", "C")
            val req = Request.Builder().url("${SPA}vehicles/${v.id}/control/engine").withAuth(s, v.ccs2).post(p.toString().toByteArray().toRequestBody(JSON)).build()
            exec(req).optString("msgId")
        }

    suspend fun stopEngine(s: Session, v: Vehicle): String = withContext(Dispatchers.IO) {
        val p = JSONObject().put("action", "stop")
        val req = Request.Builder().url("${SPA2}vehicles/${v.id}/control/engine").withControl(s, v.ccs2).post(p.toString().toByteArray().toRequestBody(JSON)).build()
        exec(req).optString("msgId")
    }

    /** The route that carried the last horn / lights / boot command (for the activity log). */
    @Volatile var lastRoute: String? = null

    private fun post(s: Session, v: Vehicle, url: String, body: String, control: Boolean): () -> Request = {
        val b = Request.Builder().url(url)
        (if (control) b.withControl(s, v.ccs2) else b.withAuth(s, v.ccs2)).post(body.toByteArray().toRequestBody(JSON)).build()
    }

    /**
     * These endpoints differ by modem generation and are not documented, so the routes that fit this car are tried in
     * order. A route the server rejects costs one quick round trip; the first one that returns a message id wins and
     * is remembered in [lastRoute].
     */
    private fun tryRoutes(routes: List<Pair<String, () -> Request>>, failure: String): String {
        var last: BluelinkException? = null
        for ((label, build) in routes) {
            try {
                val id = exec(build()).optString("msgId")
                if (id.isNotEmpty()) {
                    lastRoute = label
                    return id
                }
            } catch (e: BluelinkException) {
                if (e.auth || e.deviceId) throw e
                last = e
            }
        }
        throw last ?: BluelinkException(failure)
    }

    /** Flash the lights only. Never sounds the horn. */
    suspend fun lights(s: Session, v: Vehicle): String = withContext(Dispatchers.IO) {
        val on = JSONObject().put("command", "on").toString()
        val start = JSONObject().put("action", "start").put("deviceId", s.deviceId).toString()
        val u1 = "${SPA}vehicles/${v.id}/control/light"
        val u2 = "${SPA2}vehicles/${v.id}/control/light"
        val c2 = "${SPA2}vehicles/${v.id}/ccs2/control/light"
        tryRoutes(
            if (v.ccs2 != 0) listOf("ccs2 light" to post(s, v, c2, on, true))
            else listOf("light" to post(s, v, u1, start, false), "light (PIN)" to post(s, v, u1, start, true), "light v2" to post(s, v, u2, start, true)),
            "The car did not accept the lights command.",
        )
    }

    /** Horn and lights together ("find my car"). */
    suspend fun hornAndLights(s: Session, v: Vehicle): String = withContext(Dispatchers.IO) {
        val on = JSONObject().put("command", "on").toString()
        val start = JSONObject().put("action", "start").put("deviceId", s.deviceId).toString()
        val u1 = "${SPA}vehicles/${v.id}/control/hornlight"
        val u2 = "${SPA2}vehicles/${v.id}/control/hornlight"
        val c2 = "${SPA2}vehicles/${v.id}/ccs2/control/hornlight"
        tryRoutes(
            if (v.ccs2 != 0) listOf("ccs2 horn+light" to post(s, v, c2, on, true))
            else listOf(
                "horn+light" to post(s, v, u1, start, false),
                "horn+light (PIN)" to post(s, v, u1, start, true),
                "horn+light v2" to post(s, v, u2, start, true),
                "ccs2 horn+light" to post(s, v, c2, on, true),
            ),
            "The car did not accept the horn command.",
        )
    }

    /** Release the boot. Experimental: Hyundai does not document this and not every Creta supports it remotely. */
    suspend fun boot(s: Session, v: Vehicle): String = withContext(Dispatchers.IO) {
        val on = JSONObject().put("command", "open").toString()
        val open = JSONObject().put("action", "open").put("deviceId", s.deviceId).toString()
        val u1 = "${SPA}vehicles/${v.id}/control/trunk"
        val u2 = "${SPA2}vehicles/${v.id}/control/trunk"
        val c2 = "${SPA2}vehicles/${v.id}/ccs2/control/trunk"
        tryRoutes(
            if (v.ccs2 != 0) listOf("ccs2 trunk" to post(s, v, c2, on, true))
            else listOf("trunk" to post(s, v, u1, open, false), "trunk (PIN)" to post(s, v, u1, open, true), "trunk v2" to post(s, v, u2, open, true)),
            "This car did not accept a remote boot release.",
        )
    }

    private fun Request.Builder.withControl(s: Session, ccs2: Int): Request.Builder {
        val ct = s.controlToken ?: throw BluelinkException("PIN verification required.")
        return withAuth(s, ccs2).header("Authorization", ct).header("AuthorizationCCSP", ct)
    }

    suspend fun actionStatus(s: Session, v: Vehicle, msgId: String): ActionResult = withContext(Dispatchers.IO) {
        val json = exec(Request.Builder().url("${SPA}notifications/${v.id}/records").withAuth(s, v.ccs2).get().build())
        val arr = json.optJSONArray("resMsg") ?: return@withContext ActionResult.UNKNOWN
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            if (a.optString("recordId") == msgId) {
                return@withContext when {
                    a.isNull("result") -> ActionResult.PENDING
                    a.optString("result") == "success" -> ActionResult.SUCCESS
                    a.optString("result") == "fail" -> ActionResult.FAILED
                    a.optString("result") == "non-response" -> ActionResult.TIMEOUT
                    else -> ActionResult.UNKNOWN
                }
            }
        }
        ActionResult.UNKNOWN
    }

    // ---------------------------------------------------------------- plumbing

    /** Used by the repository to build a session with a verified control token. */
    internal suspend fun withControlToken(s: Session, pin: String): Session = controlToken(s, pin)

    /** Aborts every request currently in flight (used when the user cancels a command). */
    fun cancelInFlight() = http.dispatcher.cancelAll()

    private fun exec(req: Request): JSONObject {
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw BluelinkException("Bluelink returned an unreadable response (HTTP ${r.code}).", auth = r.code == 401 || r.code == 403)
            }
            check(json, r.code)
            return json
        }
    }

    private fun check(json: JSONObject, http: Int) {
        if (http == 401) throw BluelinkException("Session expired.", auth = true)
        if (json.optString("retCode") == "F") {
            val code = json.optString("resCode")
            val msg = json.optString("resMsg", "Request failed")
            when (code) {
                "7501" -> throw BluelinkException(msg, code, auth = true)
                "4002" -> throw BluelinkException(msg, code, deviceId = true)
                "4004" -> throw BluelinkException("Duplicate request, try again in a moment.", code)
                "4005" -> throw BluelinkException("This car does not support that command.", code)
                "4081", "9999" -> throw BluelinkException("The car did not respond in time.", code)
                "5031" -> throw BluelinkException("Remote control is temporarily unavailable.", code)
                "5091" -> throw BluelinkException("Too many requests, wait a bit and retry.", code)
                "5921" -> throw BluelinkException("No data available yet.", code)
                else -> throw BluelinkException("Bluelink error $code: $msg", code)
            }
        }
        val err = json.optString("error")
        if (err.isNotEmpty()) {
            throw BluelinkException(err, auth = err.contains("token", true) || err.contains("expired", true))
        }
        if (json.optString("retMsg") == "Received unexpected statusCode") {
            throw BluelinkException("Session expired.", auth = true)
        }
    }

    private class MemoryCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { n -> list.removeAll { it.name == n.name }; list.add(n) }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
    }
}
