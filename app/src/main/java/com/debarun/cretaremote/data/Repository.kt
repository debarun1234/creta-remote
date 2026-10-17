package com.debarun.cretaremote.data

import android.content.Context
import android.location.Geocoder
import com.debarun.cretaremote.bluelink.ActionResult
import com.debarun.cretaremote.bluelink.BluelinkClient
import com.debarun.cretaremote.bluelink.BluelinkException
import com.debarun.cretaremote.bluelink.CarState
import com.debarun.cretaremote.bluelink.Session
import com.debarun.cretaremote.bluelink.StateParser
import com.debarun.cretaremote.bluelink.TripEntry
import com.debarun.cretaremote.bluelink.TripSummary
import com.debarun.cretaremote.bluelink.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Cmd(val label: String) {
    LOCK("Locking"),
    UNLOCK("Unlocking"),
    START("Starting engine & climate"),
    STOP("Stopping engine"),
    HORN("Horn & lights"),
}

enum class Phase { IDLE, SENDING, WAITING, OK, FAIL }

data class CmdUi(val cmd: Cmd? = null, val phase: Phase = Phase.IDLE, val message: String = "") {
    val busy get() = phase == Phase.SENDING || phase == Phase.WAITING
}

data class TripMonthUi(val yyyymm: String, val summary: TripSummary?, val days: List<DayRow>, val note: String? = null)

data class AppData(
    val booting: Boolean = true,
    val signedIn: Boolean = false,
    val signingIn: Boolean = false,
    val signInError: String? = null,
    val email: String = "",
    val hasPin: Boolean = false,
    val vehicle: Vehicle? = null,
    val car: CarState? = null,
    val fetchedAt: Long? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val insights: FuelInsights = FuelInsights(),
    val places: List<Place> = emptyList(),
    val trips: TripMonthUi? = null,
    val tripsLoading: Boolean = false,
    val cmd: CmdUi = CmdUi(),
    val settings: Settings = Settings(),
    val services: List<ServiceLog> = emptyList(),
)

fun DayRow.entries(): List<TripEntry> = try {
    val a = JSONArray(trips)
    (0 until a.length()).map {
        val o = a.getJSONObject(it)
        TripEntry(
            o.optString("t"),
            if (o.has("d")) o.optDouble("d") else null,
            if (o.has("m")) o.optInt("m") else null,
            if (o.has("a")) o.optDouble("a") else null,
            if (o.has("x")) o.optDouble("x") else null,
        )
    }
} catch (e: Exception) {
    emptyList()
}

/**
 * Single source of truth. Cold start paints the last stored reading instantly, then refreshes from the
 * cloud; token + device id are persisted so no login round-trips are needed on subsequent launches.
 */
class Repository(private val ctx: Context) {
    private val client = BluelinkClient()
    private val vault = Vault(ctx)
    private val store = Store(ctx)
    private val settingsStore = SettingsStore(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionLock = Mutex()
    private val refreshLock = Mutex()

    private var session: Session? = null
    private var vehicle: Vehicle? = null
    private var lockOverride: Pair<Boolean, Long>? = null

    private val _data = MutableStateFlow(AppData(settings = settingsStore.load()))
    val data: StateFlow<AppData> = _data

    // ------------------------------------------------------------------ boot

    suspend fun bootstrap() {
        val email = vault.get("email")
        val hasCreds = email != null && vault.get("password") != null
        withContext(Dispatchers.IO) {
            loadVehicleFromStore()
            val raw = store.kvGet("raw")
            val car = raw?.let { runCatching { parseRaw(JSONObject(it)) }.getOrNull() }
            val fetched = raw?.let { runCatching { JSONObject(it).optLong("fetched") }.getOrNull() }
            val insights = computeInsights(car)
            _data.update {
                it.copy(
                    booting = false,
                    signedIn = hasCreds,
                    email = email.orEmpty(),
                    hasPin = !vault.get("pin").isNullOrEmpty(),
                    vehicle = vehicle,
                    car = car,
                    fetchedAt = fetched,
                    insights = insights,
                    places = store.places(),
                    services = loadServices(),
                )
            }
        }
        if (hasCreds) {
            scope.launch { warmUp() }
            refresh(live = false)
            loadTrips(currentMonth())
        }
    }

    private fun loadServices(): List<ServiceLog> = runCatching {
        val arr = JSONArray(store.kvGet("services") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ServiceLog(o.getLong("ts"), o.getDouble("km"), o.optString("note"), if (o.has("cost")) o.getDouble("cost") else null)
        }.sortedByDescending { it.ts }
    }.getOrDefault(emptyList())

    private fun saveServices(list: List<ServiceLog>) {
        val arr = JSONArray()
        for (e in list) {
            val o = JSONObject().put("ts", e.ts).put("km", e.km).put("note", e.note)
            if (e.cost != null) o.put("cost", e.cost)
            arr.put(o)
        }
        store.kvPut("services", arr.toString())
    }

    suspend fun addService(log: ServiceLog) = withContext(Dispatchers.IO) {
        val list = (_data.value.services + log).sortedByDescending { it.ts }
        saveServices(list)
        _data.update { it.copy(services = list) }
    }

    suspend fun deleteService(ts: Long) = withContext(Dispatchers.IO) {
        val list = _data.value.services.filter { it.ts != ts }
        saveServices(list)
        _data.update { it.copy(services = list) }
    }

    private fun loadVehicleFromStore() {
        val j = store.kvGet("vehicle") ?: return
        runCatching {
            val o = JSONObject(j)
            vehicle = Vehicle(o.getString("id"), o.optString("name"), o.optString("model"), o.optString("vin"), o.optString("reg"), o.optInt("ccs2"))
        }
    }

    private fun parseRaw(o: JSONObject): CarState =
        StateParser.parse(o.optJSONObject("status"), o.optJSONObject("loc"), o.optJSONObject("maint"))

    // ------------------------------------------------------------------ session plumbing

    private fun fresh(s: Session) = System.currentTimeMillis() - s.issuedAt < 20L * 3600_000L

    private suspend fun ensureSession(): Session = sessionLock.withLock {
        val cur = session
        if (cur != null && fresh(cur)) return@withLock cur
        val saved = vault.get("session")?.let { runCatching { decodeSession(it) }.getOrNull() }
        if (saved != null && fresh(saved)) {
            session = saved
            return@withLock saved
        }
        loginLocked()
    }

    private suspend fun relogin(): Session = sessionLock.withLock { loginLocked() }

    private suspend fun loginLocked(): Session {
        val email = vault.get("email")
        val pass = vault.get("password")
        if (email == null || pass == null) throw BluelinkException("Please sign in again.", auth = true)
        val s = client.login(email, pass)
        saveSession(s)
        return s
    }

    private fun saveSession(s: Session) {
        session = s
        vault.put("session", JSONObject().put("a", s.accessToken).put("d", s.deviceId).put("t", s.issuedAt).toString())
    }

    private fun decodeSession(j: String): Session {
        val o = JSONObject(j)
        return Session(o.getString("a"), o.getString("d"), o.getLong("t"))
    }

    private suspend fun <T> api(block: suspend (Session) -> T): T {
        var s = ensureSession()
        try {
            return try {
                block(s)
            } catch (e: BluelinkException) {
                when {
                    e.auth -> { s = relogin(); block(s) }
                    e.deviceId -> {
                        s = s.copy(deviceId = client.newDeviceId())
                        saveSession(s)
                        block(s)
                    }
                    else -> throw e
                }
            }
        } catch (e: IOException) {
            throw BluelinkException("Can't reach Bluelink. Check your connection.")
        }
    }

    private suspend fun ensureVehicle(s: Session): Vehicle {
        vehicle?.let { return it }
        val list = client.vehicles(s)
        val v = list.firstOrNull { it.model.contains("creta", true) } ?: list.firstOrNull()
            ?: throw BluelinkException("No vehicle found on this Bluelink account.")
        vehicle = v
        store.kvPut("vehicle", JSONObject().put("id", v.id).put("name", v.name).put("model", v.model).put("vin", v.vin).put("reg", v.regDate).put("ccs2", v.ccs2).toString())
        _data.update { it.copy(vehicle = v) }
        return v
    }

    // ------------------------------------------------------------------ sign in / out

    suspend fun signIn(email: String, password: String, pin: String) {
        _data.update { it.copy(signingIn = true, signInError = null) }
        try {
            val s = client.login(email.trim(), password)
            vault.put("email", email.trim())
            vault.put("password", password)
            vault.put("pin", pin)
            saveSession(s)
            vehicle = null
            val v = ensureVehicle(s)
            _data.update { it.copy(signedIn = true, signingIn = false, email = email.trim(), hasPin = pin.isNotEmpty(), vehicle = v) }
            refresh(live = false)
            loadTrips(currentMonth())
        } catch (e: Exception) {
            _data.update { it.copy(signingIn = false, signInError = friendly(e)) }
        }
    }

    fun signOut() {
        vault.clear()
        store.wipe()
        session = null
        vehicle = null
        lockOverride = null
        _data.value = AppData(booting = false, settings = settingsStore.load())
    }

    fun savePin(pin: String) {
        vault.put("pin", pin)
        session = session?.copy(controlToken = null, controlExpiry = 0)
        _data.update { it.copy(hasPin = pin.isNotEmpty()) }
    }

    fun updateSettings(s: Settings) {
        settingsStore.save(s)
        _data.update { it.copy(settings = s) }
        _data.update { it.copy(insights = computeInsights(it.car)) }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is BluelinkException -> e.message ?: "Bluelink error"
        is IOException -> "Can't reach Bluelink. Check your connection."
        else -> e.message ?: "Something went wrong"
    }

    // ------------------------------------------------------------------ refresh

    private data class Fetched(val status: JSONObject, val loc: JSONObject?, val maint: JSONObject?)

    /** [live] = ask the car for fresh data (slower, wakes modem). Otherwise read the cloud copy (fast). */
    suspend fun refresh(live: Boolean) {
        if (!refreshLock.tryLock()) return
        _data.update { it.copy(refreshing = true, error = null) }
        try {
            val f = api { s ->
                val v = ensureVehicle(s)
                coroutineScope {
                    val st = async {
                        if (live) client.statusLive(s, v) else try {
                            client.statusCached(s, v)
                        } catch (e: BluelinkException) {
                            if (e.auth) throw e else client.statusLive(s, v)
                        }
                    }
                    val lo = async { client.parkLocation(s, v) }
                    val mt = async { client.maintenance(s, v) }
                    Fetched(st.await(), lo.await(), mt.await())
                }
            }
            applyFetched(f)
        } catch (e: Exception) {
            _data.update { it.copy(refreshing = false, error = friendly(e)) }
        } finally {
            refreshLock.unlock()
        }
    }

    private suspend fun applyFetched(f: Fetched) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // keep the previous raw part if this fetch lacked it
        val prev = store.kvGet("raw")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val raw = JSONObject()
            .put("status", f.status)
            .put("loc", f.loc ?: prev?.optJSONObject("loc"))
            .put("maint", f.maint ?: prev?.optJSONObject("maint"))
            .put("fetched", now)
        store.kvPut("raw", raw.toString())
        var car = parseRaw(raw)
        val ov = lockOverride
        if (ov != null) {
            if ((car.updatedAt ?: 0L) < ov.second && now - ov.second < 5 * 60_000L) car = car.copy(locked = ov.first) else lockOverride = null
        }

        // history
        val last = store.lastSnapshot()
        val changed = last == null || last.odo != car.odometerKm || last.fuel != car.fuelPct || last.locked != car.locked || last.engine != car.engineOn
        if (car.fuelPct != null || car.odometerKm != null) {
            if (changed || now - last!!.ts > 60 * 60_000L) {
                store.addSnapshot(Snapshot(now, car.odometerKm, car.fuelPct, car.dteKm, car.locked, car.engineOn))
            }
        }
        val lat = car.lat
        val lon = car.lon
        if (lat != null && lon != null) recordPlace(lat, lon, car.locTime ?: now)

        _data.update {
            it.copy(
                car = car,
                fetchedAt = now,
                refreshing = false,
                error = null,
                insights = computeInsights(car),
                places = store.places(),
            )
        }
    }

    private fun recordPlace(lat: Double, lon: Double, ts: Long) {
        val last = store.places(1).firstOrNull()
        if (last == null || Geo.distanceM(last.lat, last.lon, lat, lon) > 80.0) {
            store.addPlace(Place(ts, lat, lon, null))
            scope.launch(Dispatchers.IO) {
                val label = reverseGeocode(lat, lon) ?: return@launch
                store.setPlaceLabel(ts, label)
                _data.update { it.copy(places = store.places()) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) null else {
            val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
            listOfNotNull(a?.subLocality ?: a?.thoroughfare, a?.locality).distinct().joinToString(", ").ifEmpty { null }
        }
    } catch (e: Exception) {
        null
    }

    private fun computeInsights(car: CarState?): FuelInsights {
        val s = _data.value.settings
        return Analytics.compute(store.snapshots(), car, s, store.days(""))
    }

    /** Called by WorkManager: stores a snapshot using the cheap cloud copy. */
    suspend fun backgroundSnapshot(): Boolean {
        if (vault.get("email") == null) return true
        refresh(live = false)
        return _data.value.error == null
    }

    // ------------------------------------------------------------------ trips

    fun currentMonth(): String = SimpleDateFormat("yyyyMM", Locale.US).format(Date())

    suspend fun loadTrips(yyyymm: String) {
        _data.update { it.copy(tripsLoading = true) }
        val cached = withContext(Dispatchers.IO) { store.days(yyyymm) }
        val cachedSummary = withContext(Dispatchers.IO) { store.kvGet("month_$yyyymm")?.let { runCatching { readSummary(JSONObject(it)) }.getOrNull() } }
        _data.update { it.copy(trips = TripMonthUi(yyyymm, cachedSummary, cached)) }
        try {
            val month = api { s -> client.tripMonth(s, ensureVehicle(s), yyyymm) }
            if (month == null) {
                _data.update { it.copy(tripsLoading = false, trips = TripMonthUi(yyyymm, cachedSummary, cached, "Bluelink returned no trip data for this month.")) }
                return
            }
            val summary = readSummary(month)
            store.kvPut("month_$yyyymm", month.toString())
            val list = month.optJSONArray("tripDayList") ?: JSONArray()
            val todayStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val sem = Semaphore(3)
            coroutineScope {
                (0 until list.length()).map { i ->
                    val o = list.getJSONObject(i)
                    val day = o.optString("tripDayInMonth")
                    async {
                        val have = cached.firstOrNull { it.day == day }
                        if (day.isEmpty() || (have != null && day < todayStr && have.fetched > 0)) return@async
                        sem.withPermit {
                            val d = api { s -> client.tripDay(s, ensureVehicle(s), day) } ?: return@withPermit
                            store.upsertDay(parseDay(day, d, o.optInt("tripCntDay")))
                        }
                    }
                }.forEach { it.await() }
            }
            val days = store.days(yyyymm)
            _data.update {
                it.copy(
                    tripsLoading = false,
                    trips = TripMonthUi(yyyymm, summary, days, if (days.isEmpty()) "No trips recorded this month." else null),
                    insights = computeInsights(it.car),
                )
            }
        } catch (e: Exception) {
            _data.update { it.copy(tripsLoading = false, trips = TripMonthUi(yyyymm, cachedSummary, cached, friendly(e))) }
        }
    }

    private fun readSummary(o: JSONObject) = TripSummary(
        distKm = o.optDouble("tripDist", 0.0),
        driveMin = o.optInt("tripDrvTime"),
        idleMin = o.optInt("tripIdleTime"),
        avgKmh = o.optDouble("tripAvgSpeed", 0.0),
        maxKmh = o.optDouble("tripMaxSpeed", 0.0),
    )

    private fun parseDay(day: String, resMsg: JSONObject, cnt: Int): DayRow {
        val d = resMsg.optJSONArray("dayTripList")?.optJSONObject(0) ?: JSONObject()
        val trips = JSONArray()
        val tl = d.optJSONArray("tripList") ?: JSONArray()
        for (i in 0 until tl.length()) {
            val t = tl.getJSONObject(i)
            trips.put(
                JSONObject().apply {
                    put("t", t.optString("tripTime", t.optString("tripStartTime", "")).takeLast(6))
                    if (t.has("tripDist")) put("d", t.optDouble("tripDist"))
                    if (t.has("tripDrvTime")) put("m", t.optInt("tripDrvTime"))
                    if (t.has("tripAvgSpeed")) put("a", t.optDouble("tripAvgSpeed"))
                    if (t.has("tripMaxSpeed")) put("x", t.optDouble("tripMaxSpeed"))
                },
            )
        }
        return DayRow(
            day = day,
            count = if (tl.length() > 0) tl.length() else cnt,
            dist = d.optDouble("tripDist", 0.0),
            drive = d.optInt("tripDrvTime"),
            idle = d.optInt("tripIdleTime"),
            avg = d.optDouble("tripAvgSpeed", 0.0),
            max = d.optDouble("tripMaxSpeed", 0.0),
            trips = trips.toString(),
            fetched = System.currentTimeMillis(),
        )
    }

    // ------------------------------------------------------------------ commands

    /** Fire-and-forget entry point for Quick Settings tiles and other callers without a UI scope. */
    fun runDetached(cmd: Cmd) { scope.launch { run(cmd) } }

    /**
     * Pre-pays the round trips a command would otherwise wait on: session, vehicle and (when a PIN is saved)
     * the control token that engine stop and horn need. Called on app open so the first tap goes straight out.
     */
    suspend fun warmUp() {
        val pin = vault.get("pin").orEmpty()
        runCatching {
            api { s ->
                ensureVehicle(s)
                if (pin.isNotEmpty()) saveSession(client.controlToken(s, pin))
                Unit
            }
        }
    }

    suspend fun run(cmd: Cmd) {
        if (_data.value.cmd.busy) return
        setCmd(cmd, Phase.SENDING, "Sending to car…")
        try {
            val pin = vault.get("pin").orEmpty()
            val st = _data.value.settings
            var vehicleUsed: Vehicle? = null
            val msgId = api { s ->
                val v = ensureVehicle(s)
                vehicleUsed = v
                when (cmd) {
                    Cmd.LOCK -> client.lock(s, v, true)
                    Cmd.UNLOCK -> client.lock(s, v, false)
                    Cmd.START -> client.startEngine(s, v, st.climateTempC, st.climateMinutes, defrost = false, heating = false)
                    Cmd.STOP -> client.stopEngine(client.controlToken(s, pin).also { saveSession(it) }, v)
                    Cmd.HORN -> client.hornAndLights(client.controlToken(s, pin).also { saveSession(it) }, v)
                }
            }
            val v = vehicleUsed ?: return
            setCmd(cmd, Phase.WAITING, "Waiting for the car…")
            var result = ActionResult.PENDING
            val started = System.currentTimeMillis()
            val deadline = started + 60_000L
            var polls = 0
            while (System.currentTimeMillis() < deadline) {
                // poll every second for the first 12 s (most commands finish there), then ease off to stay clear of Bluelink's rate limit
                delay(if (polls++ < 12) 1_000L else 2_000L)
                val secs = (System.currentTimeMillis() - started) / 1000
                setCmd(cmd, Phase.WAITING, if (secs < 3) "Waiting for the car..." else "Waiting for the car... ${secs}s")
                result = try {
                    api { s -> client.actionStatus(s, v, msgId) }
                } catch (e: BluelinkException) {
                    ActionResult.PENDING
                }
                if (result == ActionResult.SUCCESS || result == ActionResult.FAILED || result == ActionResult.TIMEOUT) break
            }
            when (result) {
                ActionResult.SUCCESS -> {
                    if (cmd == Cmd.LOCK || cmd == Cmd.UNLOCK) {
                        val locked = cmd == Cmd.LOCK
                        lockOverride = locked to System.currentTimeMillis()
                        _data.update { it.copy(car = it.car?.copy(locked = locked)) }
                    }
                    setCmd(cmd, Phase.OK, doneText(cmd))
                    scope.launch { delay(4_000); refresh(live = false) }
                }
                ActionResult.FAILED -> setCmd(cmd, Phase.FAIL, "The car rejected the command.")
                ActionResult.TIMEOUT -> setCmd(cmd, Phase.FAIL, "The car did not respond. Is it in network coverage?")
                else -> setCmd(cmd, Phase.OK, "Sent. The car hasn't confirmed yet.")
            }
        } catch (e: Exception) {
            setCmd(cmd, Phase.FAIL, friendly(e))
        }
        scope.launch { delay(3_500); _data.update { if (it.cmd.busy) it else it.copy(cmd = CmdUi()) } }
    }

    private fun doneText(c: Cmd) = when (c) {
        Cmd.LOCK -> "Locked"
        Cmd.UNLOCK -> "Unlocked"
        Cmd.START -> "Engine started"
        Cmd.STOP -> "Engine stopped"
        Cmd.HORN -> "Horn & lights on"
    }

    private fun setCmd(c: Cmd, p: Phase, m: String) = _data.update { it.copy(cmd = CmdUi(c, p, m)) }
}
