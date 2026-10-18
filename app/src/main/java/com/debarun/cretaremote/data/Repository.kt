package com.debarun.cretaremote.data

import android.content.Context
import android.location.Geocoder
import com.debarun.cretaremote.bluelink.ActionResult
import com.debarun.cretaremote.bluelink.BluelinkClient
import com.debarun.cretaremote.bluelink.BluelinkException
import com.debarun.cretaremote.bluelink.CarState
import com.debarun.cretaremote.bluelink.tyreAttention
import com.debarun.cretaremote.bluelink.tyreSummary
import com.debarun.cretaremote.bluelink.Session
import com.debarun.cretaremote.bluelink.StateParser
import com.debarun.cretaremote.bluelink.TripEntry
import com.debarun.cretaremote.bluelink.TripSummary
import com.debarun.cretaremote.bluelink.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
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
    LIGHTS("Flashing lights"),
    BOOT("Opening boot"),
}

enum class Phase { IDLE, SENDING, WAITING, OK, FAIL, CANCELLED }

data class CmdUi(val cmd: Cmd? = null, val phase: Phase = Phase.IDLE, val message: String = "") {
    val busy get() = phase == Phase.SENDING || phase == Phase.WAITING
}

data class TripMonthUi(val yyyymm: String, val summary: TripSummary?, val days: List<DayRow>, val note: String? = null)

/** Tyre pressures the driver read off the dashboard or a pump and logged by hand (psi per wheel, FL FR RL RR). */
data class TyreLog(val ts: Long, val psi: List<Double?>)

/** What we know about the car's modem: it sleeps to save the battery and has to be woken before it will act. */
enum class Link { UNKNOWN, WAKING, AWAKE, ASLEEP }

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
    val activity: List<Activity> = emptyList(),
    val activityUnread: Int = 0,
    val link: Link = Link.UNKNOWN,
    val linkAt: Long? = null,
    val fuel: FuelWatchUi = FuelWatchUi(),
    val tyreLog: TyreLog? = null,
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
            endHhmmss = if (o.has("e")) o.optString("e") else null,
            idleMin = if (o.has("i")) o.optInt("i") else null,
            raw = if (o.has("r")) o.optString("r") else null,
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
                    activity = loadActivity(),
                    activityUnread = loadActivity().count { a -> a.ts > activitySeen },
                    tyreLog = loadTyreLog(),
                    fuel = FuelWatchUi(store.kvGet("fw")?.let { runCatching { JSONObject(it).optInt("stage", 0) }.getOrNull() } ?: 0, litresOf(car)),
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
        val old = _data.value.settings
        settingsStore.save(s)
        _data.update { it.copy(settings = s) }
        if (old.widgetTheme != s.widgetTheme || old.widgetBg != s.widgetBg) com.debarun.cretaremote.quick.Widgets.updateAll(ctx)
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
    /** Returns true when the car answered, false when it did not, null when another refresh was already running. */
    suspend fun refresh(live: Boolean, quiet: Boolean = false): Boolean? {
        if (!refreshLock.tryLock()) return null
        if (!quiet) _data.update { it.copy(refreshing = true, error = null) }
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
            return true
        } catch (e: Exception) {
            _data.update { it.copy(refreshing = false, error = if (quiet) it.error else friendly(e)) }
            return false
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
        checkAlerts(car, now)
        evaluateFuel(car, fromFetch = true)
        com.debarun.cretaremote.quick.WidgetState.save(ctx, car, _data.value.settings.carTitle(), tyreLine(car))

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

    /**
     * Background alerts, only when the app is closed and only on a change so nothing repeats: fuel just went low,
     * something was just left open, or the car has sat unlocked for 20 minutes.
     */
    private fun checkAlerts(car: CarState, now: Long) {
        val st = _data.value.settings
        val prev = runCatching { JSONObject(store.kvGet("alert_state") ?: "{}") }.getOrDefault(JSONObject())
        val wasLow = prev.optBoolean("low")
        val wasOpen = prev.optBoolean("open")
        val unlockedSince = prev.optLong("unl", 0L)
        val unlockedNotified = prev.optBoolean("unlN")

        val open = car.doorsOpen.any || car.hoodOpen || car.trunkOpen
        val unlockedNow = car.locked == false && car.engineOn != true
        val since = if (unlockedNow) (if (unlockedSince > 0) unlockedSince else now) else 0L
        var notified = unlockedNow && unlockedNotified
        val canNotify = !AppForeground.visible && st.notifyAlerts

        if (canNotify) {
            if (car.lowFuel && !wasLow) {
                Notifier.alert(ctx, 201, "Fuel is low", car.dteKm?.let { "About ${it.toInt()} km of range left." } ?: "Time to refuel.")
            }
            if (open && !wasOpen) {
                val parts = buildList {
                    if (car.doorsOpen.fl) add("front left door")
                    if (car.doorsOpen.fr) add("front right door")
                    if (car.doorsOpen.rl) add("rear left door")
                    if (car.doorsOpen.rr) add("rear right door")
                    if (car.hoodOpen) add("bonnet")
                    if (car.trunkOpen) add("boot")
                }
                Notifier.alert(ctx, 202, "Your Creta has something open", parts.joinToString(", ").replaceFirstChar { it.uppercase() } + " is open.")
            }
            if (unlockedNow && !notified && now - since >= 20 * 60_000L) {
                Notifier.alert(ctx, 203, "Your Creta is unlocked", "It has been unlocked for over 20 minutes.")
                notified = true
            }
        }
        store.kvPut("alert_state", JSONObject().put("low", car.lowFuel).put("open", open).put("unl", since).put("unlN", notified).toString())
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
        // while driving the modem is awake anyway, so ask the car directly; otherwise take the cached reading
        refresh(live = _data.value.car?.engineOn == true)
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
                        if (day.isEmpty() || (have != null && day < todayStr && have.fetched > 0 && (have.count == 0 || have.trips.contains("\"r\":")))) return@async
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
            // Bluelink field names vary by modem generation, so each value is looked up under every name seen so far.
            fun num(vararg keys: String): Double? = keys.firstNotNullOfOrNull { k -> if (t.has(k) && !t.isNull(k)) t.optString(k).toDoubleOrNull() else null }
            fun txt(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> t.optString(k, "").takeIf { it.isNotEmpty() && it != "null" } }
            trips.put(
                JSONObject().apply {
                    put("t", (txt("tripTime", "tripStartTime", "startTime", "tripStart") ?: "").takeLast(6))
                    txt("tripEndTime", "endTime", "tripEnd")?.let { put("e", it.takeLast(6)) }
                    num("tripDist", "tripDistance", "distance", "dist", "mileage", "drivingDistance")?.let { put("d", it) }
                    num("tripDrvTime", "drvTime", "driveTime", "drivingTime", "tripDuration", "duration")?.let { put("m", it.toInt()) }
                    num("tripIdleTime", "idleTime")?.let { put("i", it.toInt()) }
                    num("tripAvgSpeed", "avgSpeed", "averageSpeed")?.let { put("a", it) }
                    num("tripMaxSpeed", "maxSpeed")?.let { put("x", it) }
                    put("r", t.toString())
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

    // ------------------------------------------------------------------ tyre pressure

    private fun loadTyreLog(): TyreLog? = runCatching {
        val o = JSONObject(store.kvGet("tyre_log") ?: return null)
        val a = o.getJSONArray("psi")
        TyreLog(o.getLong("ts"), (0 until 4).map { if (a.isNull(it)) null else a.getDouble(it) })
    }.getOrNull()

    /** The driver's own reading, for cars whose Bluelink data has no pressure numbers. */
    fun saveTyreLog(psi: List<Double?>) {
        val log = TyreLog(System.currentTimeMillis(), psi)
        store.kvPut("tyre_log", JSONObject().put("ts", log.ts).put("psi", JSONArray().apply { psi.forEach { put(it ?: JSONObject.NULL) } }).toString())
        _data.update { it.copy(tyreLog = log) }
        _data.value.car?.let { com.debarun.cretaremote.quick.WidgetState.saveTyre(ctx, tyreLine(it), it.tyreAttention) }
    }

    /** Widget text: the car's own numbers if it sends them, else a low-tyre warning, else the driver's last logged numbers. */
    fun tyreLine(car: CarState): String {
        val log = _data.value.tyreLog
        return when {
            car.tyrePsi.all { it != null } || car.tyreAttention || log == null -> car.tyreSummary()
            else -> log.psi.joinToString(" · ") { it?.toInt()?.toString() ?: "-" } + " psi"
        }
    }

    /** Status and maintenance exactly as Bluelink sent them (no location), for working out what this car reports. */
    fun rawForSupport(): String? {
        val raw = store.kvGet("raw")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return JSONObject().put("status", raw.opt("status")).put("maint", raw.opt("maint")).toString(2)
    }

    // ------------------------------------------------------------------ fuel limit

    private fun litresOf(car: CarState?): Double? = car?.fuelPct?.let { it / 100.0 * _data.value.settings.tankLitres }

    /** Runs on every fetch and whenever the limit changes. Fires the heads-up and the alarm as the tank crosses them. */
    private fun evaluateFuel(car: CarState, fromFetch: Boolean) {
        val litres = litresOf(car) ?: return
        val prev = store.kvGet("fw")?.let { runCatching { JSONObject(it) }.getOrNull() }
        var stage = prev?.optInt("stage", 0) ?: 0
        val last = if (prev?.has("lit") == true) prev.optDouble("lit") else null
        var limit = _data.value.settings.fuelLimitL
        if (fromFetch && last != null && litres - last >= FuelWatch.REFUEL_L) {
            // refuelled: this trip is over, the next one starts from the default limit
            stage = 0
            if (limit != FuelWatch.DEFAULT) {
                limit = FuelWatch.DEFAULT
                updateSettings(_data.value.settings.copy(fuelLimitL = limit))
            }
            Notifier.cancelFuelAlarm(ctx)
        }
        val n = FuelWatch.next(litres, limit, stage)
        store.kvPut("fw", JSONObject().put("stage", n.stage).put("lit", litres).toString())
        _data.update { it.copy(fuel = FuelWatchUi(n.stage, litres)) }
        when (n.fire) {
            FuelWatch.Fire.HEADS_UP -> Notifier.fuelHeadsUp(ctx, litres, limit)
            FuelWatch.Fire.ALARM -> Notifier.fuelAlarm(ctx, litres, limit)
            null -> {}
        }
        if (needsFastWatch()) scheduleFastWatch(append = false)
    }

    /** Sets this trip's limit (10-30 L) and checks it against the tank right away. */
    fun setFuelLimit(l: Int) {
        val v = l.coerceIn(FuelWatch.MIN, FuelWatch.MAX)
        updateSettings(_data.value.settings.copy(fuelLimitL = v))
        _data.value.car?.let { evaluateFuel(it, fromFetch = false) }
    }

    /** "I'll fill up": stops the alarm. It stays quiet until the tank is well above the limit again. */
    fun ackFuelAlarm() {
        val litres = _data.value.fuel.litres ?: litresOf(_data.value.car)
        store.kvPut("fw", JSONObject().put("stage", 3).apply { litres?.let { put("lit", it) } }.toString())
        _data.update { it.copy(fuel = it.fuel.copy(stage = 3)) }
        Notifier.cancelFuelAlarm(ctx)
    }

    /** True while the tank needs close watching: the engine is on, or it is within a few litres of the limit. */
    fun needsFastWatch(): Boolean {
        val car = _data.value.car ?: return false
        if (_data.value.fuel.stage >= 3) return false
        val litres = litresOf(car)
        return car.engineOn == true || (litres != null && litres <= _data.value.settings.fuelLimitL + 6)
    }

    /** Background checks are 15 minutes apart at best; while it matters this chains a check every 5. */
    fun scheduleFastWatch(append: Boolean) {
        val req = androidx.work.OneTimeWorkRequestBuilder<com.debarun.cretaremote.work.SnapshotWorker>()
            .setInitialDelay(5, java.util.concurrent.TimeUnit.MINUTES)
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .build()
        androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork(
            "fuelwatch", if (append) androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE else androidx.work.ExistingWorkPolicy.KEEP, req,
        )
    }

    // ------------------------------------------------------------------ wake-up

    @Volatile private var lastActivity = 0L
    @Volatile private var lastPing = 0L
    private var keepJob: Job? = null

    private fun hasCreds() = vault.get("email") != null && vault.get("password") != null

    /**
     * The car's modem sleeps and has to be woken before it will act, which is most of the wait on every command.
     * A live status read is the closest thing Bluelink offers to a wake-up call, so while the app is on screen it
     * sends one on open and again every few minutes, for at most [KEEP_AWAKE_MS] after the last activity. That cap
     * protects the 12 V battery and Hyundai's rate limit.
     */
    fun onForeground(visible: Boolean) {
        AppForeground.visible = visible
        if (!visible) { keepJob?.cancel(); keepJob = null; return }
        if (!hasCreds() || !_data.value.settings.keepAwake) return
        lastActivity = System.currentTimeMillis()
        if (keepJob?.isActive == true) return
        keepJob = scope.launch {
            while (AppForeground.visible && System.currentTimeMillis() - lastActivity < KEEP_AWAKE_MS) {
                if (!_data.value.cmd.busy && System.currentTimeMillis() - lastPing > PING_EVERY_MS) wakePing()
                delay(15_000)
            }
        }
    }

    /** One wake-up call, used by the keep-awake loop and when the Quick Settings panel is opened. */
    suspend fun wakePing() {
        if (_data.value.cmd.busy || !hasCreds()) return
        lastPing = System.currentTimeMillis()
        val before = _data.value.link
        _data.update { it.copy(link = Link.WAKING) }
        val ok = refresh(live = true, quiet = true)
        _data.update {
            when (ok) {
                true -> it.copy(link = Link.AWAKE, linkAt = System.currentTimeMillis())
                false -> it.copy(link = Link.ASLEEP, linkAt = System.currentTimeMillis())
                null -> it.copy(link = before)
            }
        }
    }

    /** For callers outside the app (Quick Settings tiles): wake the car if it has not been pinged lately. */
    fun wakeIfIdle() {
        if (!_data.value.settings.keepAwake || !hasCreds()) return
        if (System.currentTimeMillis() - lastPing < PING_EVERY_MS) return
        scope.launch { wakePing() }
    }

    // ------------------------------------------------------------------ activity log

    private var activitySeen: Long = 0L

    private fun loadActivity(): List<Activity> {
        if (activitySeen == 0L) activitySeen = store.kvGet("activity_seen")?.toLongOrNull() ?: 0L
        return ActivityLog.decode(store.kvGet("activity"))
    }

    private fun logActivity(a: Activity) {
        val list = (listOf(a) + _data.value.activity).take(ActivityLog.MAX)
        store.kvPut("activity", ActivityLog.encode(list))
        _data.update { it.copy(activity = list, activityUnread = list.count { x -> x.ts > activitySeen }) }
    }

    fun markActivitySeen() {
        activitySeen = System.currentTimeMillis()
        store.kvPut("activity_seen", activitySeen.toString())
        _data.update { it.copy(activityUnread = 0) }
    }

    fun clearActivity() {
        store.kvPut("activity", "[]")
        _data.update { it.copy(activity = emptyList(), activityUnread = 0) }
    }

    @Volatile private var cmdJob: Job? = null
    @Volatile private var cancelRequested = false

    /**
     * Cancels the running command. Before the request is sent this stops it outright; once Hyundai has accepted it
     * there is no way to recall it, so this only stops waiting and the car may still carry it out.
     */
    fun cancelCmd() {
        if (!_data.value.cmd.busy) return
        cancelRequested = true
        client.cancelInFlight()
        cmdJob?.cancel()
    }

    suspend fun run(cmd: Cmd) {
        if (_data.value.cmd.busy) return
        lastActivity = System.currentTimeMillis()
        cmdJob = currentCoroutineContext()[Job]
        cancelRequested = false
        client.lastRoute = null
        var sent = false
        val t0 = System.currentTimeMillis()
        var sendMs: Long? = null
        fun log(outcome: Outcome, msg: String) {
            logActivity(Activity(t0, cmd, outcome, msg, sendMs, System.currentTimeMillis() - t0, if (cmd == Cmd.HORN || cmd == Cmd.LIGHTS || cmd == Cmd.BOOT) client.lastRoute else null))
            // the app is closed (Quick Settings tile, shortcut, or the user switched away): tell them how it ended
            if (!AppForeground.visible && _data.value.settings.notifyCommands) Notifier.command(ctx, cmd, outcome, msg)
        }
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
                    Cmd.LIGHTS -> client.lights(optionalControl(s, pin), v)
                    Cmd.BOOT -> client.boot(optionalControl(s, pin), v)
                }
            }
            val v = vehicleUsed ?: return
            sent = true
            sendMs = System.currentTimeMillis() - t0
            setCmd(cmd, Phase.WAITING, "Waiting for the car…")
            var result = ActionResult.PENDING
            val started = System.currentTimeMillis()
            val deadline = started + 60_000L
            var polls = 0
            while (System.currentTimeMillis() < deadline) {
                // poll every second for the first 12 s (most commands finish there), then ease off to stay clear of Bluelink's rate limit
                delay(when { polls == 0 -> 700L; polls < 12 -> 1_000L; else -> 2_000L }); polls++
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
                        com.debarun.cretaremote.quick.WidgetState.saveLocked(ctx, locked)
                        _data.update { it.copy(car = it.car?.copy(locked = locked)) }
                    }
                    if (cmd == Cmd.START) com.debarun.cretaremote.quick.WidgetState.saveEngine(ctx, true)
                    if (cmd == Cmd.STOP) com.debarun.cretaremote.quick.WidgetState.saveEngine(ctx, false)
                    setCmd(cmd, Phase.OK, doneText(cmd))
                    _data.update { it.copy(link = Link.AWAKE, linkAt = System.currentTimeMillis()) }
                    lastActivity = System.currentTimeMillis()
                    log(Outcome.SUCCESS, doneText(cmd))
                    scope.launch { delay(4_000); refresh(live = false) }
                }
                ActionResult.FAILED -> {
                    setCmd(cmd, Phase.FAIL, "The car rejected the command.")
                    log(Outcome.FAILED, "The car rejected the command.")
                }
                ActionResult.TIMEOUT -> {
                    setCmd(cmd, Phase.FAIL, "The car did not respond. Is it in network coverage?")
                    log(Outcome.FAILED, "The car did not respond. Is it in network coverage?")
                }
                else -> {
                    setCmd(cmd, Phase.OK, "Sent. The car hasn't confirmed yet.")
                    log(Outcome.UNCONFIRMED, "Sent, but the car did not confirm within a minute. It may not have acted.")
                }
            }
        } catch (e: Exception) {
            if (cancelRequested || e is CancellationException) {
                val m = if (sent) "Stopped waiting. The car may still carry the command out."
                else "Cancelled. If the request had already left your phone, the car may still act on it."
                setCmd(cmd, Phase.CANCELLED, m)
                log(Outcome.CANCELLED, m)
            } else {
                setCmd(cmd, Phase.FAIL, friendly(e))
                log(Outcome.FAILED, friendly(e))
            }
        } finally {
            cmdJob = null
        }
        scope.launch { delay(if (_data.value.cmd.phase == Phase.CANCELLED) 5_000 else 3_500); _data.update { if (it.cmd.busy) it else it.copy(cmd = CmdUi()) } }
    }

    /** Adds the PIN-verified control token when there is one; routes that need it are skipped when there isn't. */
    private suspend fun optionalControl(s: Session, pin: String): Session {
        if (pin.isBlank()) return s
        return try {
            client.controlToken(s, pin).also { saveSession(it) }
        } catch (e: BluelinkException) {
            if (e.auth) throw e else s
        }
    }

    private fun doneText(c: Cmd) = when (c) {
        Cmd.LOCK -> "Locked"
        Cmd.UNLOCK -> "Unlocked"
        Cmd.START -> "Engine started"
        Cmd.STOP -> "Engine stopped"
        Cmd.HORN -> "Horn & lights on"
        Cmd.LIGHTS -> "Lights flashed"
        Cmd.BOOT -> "Boot released"
    }

    private companion object {
        const val KEEP_AWAKE_MS = 10 * 60_000L
        const val PING_EVERY_MS = 3 * 60_000L
    }

    private fun setCmd(c: Cmd, p: Phase, m: String) = _data.update { it.copy(cmd = CmdUi(c, p, m)) }
}
