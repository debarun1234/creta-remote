package com.debarun.cretaremote.data

import com.debarun.cretaremote.BuildConfig
import com.debarun.cretaremote.service.DriveState
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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

/** One stretch of a day's map: the road route between two stops, or a straight line when no route could be found. */
data class RouteLeg(val pts: List<Pair<Double, Double>>, val straight: Boolean, val km: Double)

/** A day on the map: the places the car stood, in order, and the legs between them. */
data class DayRoute(val stops: List<Place>, val legs: List<RouteLeg>, val trips: Int)

/** The drive in progress, odometer since the engine started, and a rough economy from the gauge while it lasts. */
data class LiveTrip(val km: Double, val litresUsed: Double?, val kmpl: Double?)

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
    val liveTrip: LiveTrip? = null,
    val update: UpdateUi = UpdateUi(),
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
            runCatching { cleanUpdates() }
            val raw = store.kvGet("raw")
            val car = raw?.let { runCatching { parseRaw(JSONObject(it)) }.getOrNull() }
            val fetched = raw?.let { runCatching { JSONObject(it).optLong("fetched") }.getOrNull() }
            val insights = computeInsights(car)
            // what we last learnt about the car's modem survives the app being closed, so reopening does not start from "unknown" and wake it again
            val lk = store.kvGet("link")?.split("|")
            val lkState = lk?.getOrNull(0)?.let { n -> runCatching { Link.valueOf(n) }.getOrNull() } ?: Link.UNKNOWN
            val lkAt = lk?.getOrNull(1)?.toLongOrNull()
            _data.update {
                it.copy(
                    booting = false,
                    signedIn = hasCreds,
                    email = email.orEmpty(),
                    hasPin = !vault.get("pin").isNullOrEmpty(),
                    vehicle = vehicle,
                    car = car,
                    fetchedAt = fetched,
                    link = lkState,
                    linkAt = lkAt,
                    insights = insights,
                    places = store.places(),
                    services = loadServices(),
                    activity = loadActivity(),
                    activityUnread = loadActivity().count { a -> a.ts > activitySeen },
                    tyreLog = loadTyreLog(),
                    fuel = FuelWatchUi(store.kvGet("fw")?.let { raw -> runCatching { JSONObject(raw).optInt("stage", 0) }.getOrNull() } ?: 0, litresOf(car)),
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
        // Background work (widgets, 15-min snapshots, fuel watch) calls this too. After a failure, stop hammering the server:
        // repeated bad logins are exactly what gets an account temporarily locked.
        val now = System.currentTimeMillis()
        if (now < loginBlockedUntil) throw BluelinkException(loginBlockedMsg ?: "Sign-in is paused after a failure. Open the app and sign in again.", auth = true)
        try {
            val s = client.login(email, pass)
            loginFailures = 0
            loginBlockedUntil = 0
            saveSession(s)
            return s
        } catch (e: BluelinkException) {
            if (e.auth) {
                loginFailures++
                val wait = minOf(120L, 15L shl minOf(loginFailures - 1, 3)) // 15, 30, 60, 120 min
                loginBlockedUntil = now + wait * 60_000L
                loginBlockedMsg = (e.message ?: "Sign-in failed.") + " Automatic retries paused for $wait min."
                throw BluelinkException(loginBlockedMsg!!, auth = true)
            }
            throw e
        }
    }

    @Volatile private var loginBlockedUntil = 0L
    @Volatile private var loginBlockedMsg: String? = null
    @Volatile private var loginFailures = 0

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
        loginBlockedUntil = 0
        try {
            val s = client.login(email.trim(), password)
            val changed = vault.get("password") != password
            vault.put("email", email.trim())
            vault.put("password", password)
            // a different password than the one we had means it was just changed, so the expiry clock restarts
            if (changed || _data.value.settings.passwordSetAt == 0L) updateSettings(_data.value.settings.copy(passwordSetAt = System.currentTimeMillis()))
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
        val keptFills = store.kvGet("fills") // the driver's own fill-up ledger survives a sign-out
        store.wipe()
        keptFills?.let { store.kvPut("fills", it) }
        fillsCache = null
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

    /** The driver changed the Bluelink password (in the official app); restart the expiry clock. */
    fun passwordChanged() = updateSettings(_data.value.settings.copy(passwordSetAt = System.currentTimeMillis()))

    /** Once a day at most, a week before the password is due to expire and then every day until it does. */
    private fun checkPasswordExpiry() {
        val st = _data.value.settings
        val left = st.passwordDaysLeft() ?: return
        if (left > PASSWORD_WARN_DAYS || !st.notifyAlerts || AppForeground.visible) return
        val today = System.currentTimeMillis() / 86_400_000L
        if (store.kvGet("pw_notified") == today.toString()) return
        store.kvPut("pw_notified", today.toString())
        val (title, text) = when {
            left < 0 -> "Bluelink password has probably expired" to "Change it in the official Bluelink app, then sign in here again. Creta Remote cannot reach the car until you do."
            left == 0 -> "Bluelink password expires today" to "Change it in the official Bluelink app, then update it in Creta Remote."
            else -> "Bluelink password expires in $left day${if (left == 1) "" else "s"}" to "Change it in the official Bluelink app, then sign in here with the new one."
        }
        Notifier.alert(ctx, Notifier.ID_PASSWORD, title, text)
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

    /** Why the last refresh failed, so the "Car asleep" chip is only shown when the car really did not answer. */
    @Volatile private var lastRefreshError: Throwable? = null

    /** [live] = ask the car for fresh data (slower, wakes modem). Otherwise, read the cloud copy (fast). */
    /** Returns true when the car answered, false when it did not, null when another refresh was already running. */
    suspend fun refresh(live: Boolean, quiet: Boolean = false, light: Boolean = false): Boolean? {
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
                    // a light read is the status alone: the parked location and service dates do not change while driving
                    val lo = if (light) null else async { client.parkLocation(s, v) }
                    val mt = if (light) null else async { client.maintenance(s, v) }
                    Fetched(st.await(), lo?.await(), mt?.await())
                }
            }
            applyFetched(f, light)
            lastRefreshError = null
            return true
        } catch (e: Exception) {
            lastRefreshError = e
            _data.update { it.copy(refreshing = false, error = if (quiet) it.error else friendly(e)) }
            return false
        } finally {
            refreshLock.unlock()
        }
    }

    private suspend fun applyFetched(f: Fetched, light: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val wasOn = _data.value.car?.engineOn == true
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
        var stored = false
        if (car.fuelPct != null || car.odometerKm != null) {
            // while driving a reading arrives every 30 s; keep one per kilometre (or when anything else changes) so the table stays small
            val rolling = last != null && last.engine == true && car.engineOn == true && last.fuel == car.fuelPct && last.locked == car.locked &&
                (car.odometerKm ?: 0.0) - (last.odo ?: 0.0) < 1.0
            if ((changed && !rolling) || now - last!!.ts > 60 * 60_000L) {
                store.addSnapshot(Snapshot(now, car.odometerKm, car.fuelPct, car.dteKm, car.locked, car.engineOn))
                stored = true
            }
        }
        val lat = car.lat
        val lon = car.lon
        if (lat != null && lon != null) recordPlace(lat, lon, car.locTime ?: now)
        checkAlerts(car, now)
        evaluateFuel(car, fromFetch = true)
        com.debarun.cretaremote.quick.WidgetState.save(ctx, car, _data.value.settings.carTitle(), tyreLine(car), widgetPsi(car))

        _data.update {
            it.copy(
                car = car,
                fetchedAt = now,
                refreshing = false,
                error = null,
                insights = if (light && !stored) it.insights else computeInsights(car),
                liveTrip = liveTripOf(car),
                places = store.places(),
            )
        }
        // a trip just ended: light reads skip the parked location, so pick it up once the car has had time to report it
        if (light && wasOn && car.engineOn != true) scope.launch { delay(40.seconds); refresh(live = false, quiet = true) }
        // the engine is on: stay attached (a no-op when a session is already running or Android says no from the background)
        if (car.engineOn == true && _data.value.settings.driveSession && hasCreds()) com.debarun.cretaremote.service.DriveService.start(ctx)
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
        val snaps = store.snapshots()
        // parked readings only: with the engine running the gauge sloshes
        val gauge = snaps.mapNotNull { p ->
            val odo = p.odo ?: return@mapNotNull null
            val pct = p.fuel ?: return@mapNotNull null
            if (p.engine == true) null else GaugePoint(p.ts, odo, pct / 100.0 * s.tankLitres)
        }
        val ledger = FuelLedger.stats(fills(), car?.odometerKm, s.tankLitres, s.fuelPricePerL, car?.fuelPct?.let { it / 100.0 * s.tankLitres }, gauge)
        val ins = Analytics.compute(snaps, car, s, fixDays(store.days("")), ledger)
        // the fuel widgets show the same numbers as the Fuel log
        com.debarun.cretaremote.quick.WidgetState.savePlan(
            ctx, ins.litresLeft, s.tankLitres, s.fuelLimitL, ins.fillAtOdo, ins.kmToLimit, ledger.kmplNow, !ledger.usable, ins.costPerKm,
        )
        return ins
    }

    // ------------------------------------------------------------------ fill-up ledger

    private var fillsCache: List<Fill>? = null

    private fun fills(): List<Fill> = fillsCache ?: runCatching {
        val arr = JSONArray(store.kvGet("fills") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Fill(o.getLong("ts"), o.getDouble("odo"), o.getDouble("l"), o.optDouble("p", 0.0), o.optBoolean("full", true))
        }.sortedBy { it.ts }
    }.getOrDefault(emptyList()).also { fillsCache = it }

    private fun persistFills(list: List<Fill>) {
        val arr = JSONArray()
        for (f in list) arr.put(JSONObject().put("ts", f.ts).put("odo", f.odoKm).put("l", f.litres).put("p", f.pricePerL).put("full", f.full))
        store.kvPut("fills", arr.toString())
        fillsCache = list
        _data.update { it.copy(insights = computeInsights(it.car)) }
    }

    /** Adds a fill-up, or replaces the one at [replaceTs]. Returns an error message, or null on success. */
    fun saveFill(f: Fill, replaceTs: Long?): String? {
        val others = fills().filter { it.ts != replaceTs }
        validateFill(others, f)?.let { return it }
        persistFills((others + f).sortedBy { it.ts })
        return null
    }

    private fun validateFill(others: List<Fill>, f: Fill): String? = FuelLedger.validate(others, f, _data.value.settings.tankLitres)

    fun deleteFill(ts: Long) = persistFills(fills().filter { it.ts != ts })

    /** Called by WorkManager: stores a snapshot using the cheap cloud copy. */
    suspend fun backgroundSnapshot(): Boolean {
        if (vault.get("email") == null) return true
        checkPasswordExpiry()
        runCatching { checkUpdatesNow(manual = false, minGapMs = 6 * 3_600_000L) }
        // while driving the modem is awake anyway, so ask the car directly; otherwise take the cached reading
        refresh(live = _data.value.car?.engineOn == true)
        return _data.value.error == null
    }

    // ------------------------------------------------------------------ trips

    /**
     * Bluelink's own trip figures run short (the dash said 37 km, the cloud 34 km). The odometer cannot: every metre driven moves it,
     * and it stays put while the car is parked. So a day's distance is the odometer from just before its first trip to just after its
     * last, from the readings the app records. When that is a plausible amount more than the cloud figure it replaces it, and the day's
     * trips are scaled to add up to it.
     */
    private fun fixDays(days: List<DayRow>): List<DayRow> {
        if (days.none { it.dist > 0 }) return days
        val snaps = store.snapshots().filter { it.odo != null }
        if (snaps.size < 2) return days
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fun at(day: String, hhmmss: String): Long? = runCatching { stamp.parse(day + hhmmss.padStart(6, '0'))!!.time }.getOrNull()
        return days.map { r ->
            if (r.dist <= 0.0) return@map r
            val es = r.entries()
            val starts = es.mapNotNull { e -> at(r.day, e.startHhmmss) }
            if (starts.isEmpty()) return@map r
            val ends = es.mapNotNull { e ->
                val s0 = at(r.day, e.startHhmmss) ?: return@mapNotNull null
                val e0 = e.endHhmmss?.let { at(r.day, it) } ?: e.driveMin?.let { s0 + it * 60_000L } ?: return@mapNotNull null
                if (e0 < s0) e0 + 86_400_000L else e0
            }
            val lastEnd = ends.maxOrNull() ?: return@map r
            val first = starts.minOrNull() ?: return@map r
            // parked readings are the safe ends: the odometer does not move while the engine is off
            val a = (snaps.lastOrNull { it.ts <= first && it.engine != true } ?: snaps.lastOrNull { it.ts <= first })?.odo
            val b = (snaps.firstOrNull { it.ts >= lastEnd && it.engine != true } ?: snaps.firstOrNull { it.ts >= lastEnd })?.odo
            if (a == null || b == null) return@map r
            val odoKm = b - a
            if (odoKm <= r.dist + 0.05 || odoKm > r.dist * 1.35 + 5.0) r else scaleDay(r, odoKm)
        }
    }

    private fun scaleDay(r: DayRow, odoKm: Double): DayRow {
        val arr = runCatching { JSONArray(r.trips) }.getOrDefault(JSONArray())
        val sum = (0 until arr.length()).sumOf { arr.getJSONObject(it).optDouble("d", 0.0) }
        val f = odoKm / (if (sum > 0) sum else r.dist)
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.has("d")) { t.put("c", t.optDouble("d")); t.put("d", t.optDouble("d") * f) }
        }
        return r.copy(dist = odoKm, trips = arr.toString())
    }

    /** Distance since the engine started, from the last parked odometer reading, and a rough economy from the gauge. */
    private fun liveTripOf(car: CarState): LiveTrip? {
        if (car.engineOn != true) return null
        val odoNow = car.odometerKm ?: return null
        val snaps = store.snapshots(System.currentTimeMillis() - 12 * 3_600_000L).filter { it.odo != null }
        if (snaps.isEmpty()) return null
        var i = snaps.size
        while (i > 0 && snaps[i - 1].engine == true) i--
        val start = if (i > 0) snaps[i - 1] else snaps[0]
        val km = odoNow - start.odo!!
        if (km < 0.0) return null
        val tank = _data.value.settings.tankLitres
        val used = if (start.fuel != null && car.fuelPct != null) (start.fuel - car.fuelPct) / 100.0 * tank else null
        val kmpl = if (used != null && used >= 1.0 && km >= 5.0) (km / used).takeIf { it in 3.0..40.0 } else null
        return LiveTrip(km, used?.takeIf { it > 0.0 }, kmpl)
    }

    fun currentMonth(): String = SimpleDateFormat("yyyyMM", Locale.US).format(Date())

    suspend fun loadTrips(yyyymm: String) {
        _data.update { it.copy(tripsLoading = true) }
        val cached = withContext(Dispatchers.IO) { fixDays(store.days(yyyymm)) }
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
            val days = withContext(Dispatchers.IO) { fixDays(store.days(yyyymm)) }
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

    // ------------------------------------------------------------------ places and routes

    private fun monthBounds(yyyymm: String): Pair<Long, Long>? = runCatching {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US)
        val from = f.parse(yyyymm + "01")!!.time
        val cal = java.util.Calendar.getInstance().apply { time = Date(from); add(java.util.Calendar.MONTH, 1) }
        from - 1 to cal.timeInMillis - 1
    }.getOrNull()

    /** Where the car parked during [yyyymm], newest first. */
    suspend fun placesFor(yyyymm: String): List<Place> = withContext(Dispatchers.IO) {
        val (from, to) = monthBounds(yyyymm) ?: return@withContext emptyList()
        store.placesBetween(from, to).asReversed()
    }

    /** The month of the oldest recorded parking spot, so the month picker knows where to stop. */
    suspend fun earliestPlaceMonth(): String? = withContext(Dispatchers.IO) {
        store.firstPlaceTs()?.let { SimpleDateFormat("yyyyMM", Locale.US).format(Date(it)) }
    }

    /** When a day's first trip began and its last one ended, from the trip times Bluelink gave. */
    private fun spanOf(r: DayRow): Pair<Long, Long>? {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fun at(hhmmss: String): Long? = runCatching { stamp.parse(r.day + hhmmss.padStart(6, '0'))!!.time }.getOrNull()
        val es = r.entries()
        val starts = es.mapNotNull { at(it.startHhmmss) }
        val ends = es.mapNotNull { e ->
            val s0 = at(e.startHhmmss) ?: return@mapNotNull null
            val e0 = e.endHhmmss?.let { at(it) } ?: e.driveMin?.let { s0 + it * 60_000L } ?: return@mapNotNull null
            if (e0 < s0) e0 + 86_400_000L else e0
        }
        val a = starts.minOrNull() ?: return null
        val b = ends.maxOrNull() ?: return null
        return a to b
    }

    /**
     * The places the car stood during a day (where it started, then each place it parked) joined by road routes. Bluelink does not send a
     * GPS track, only where the car parks, so each leg is the road route between two consecutive stops; the exact streets driven may differ.
     */
    suspend fun dayRoute(day: String): DayRoute = withContext(Dispatchers.IO) {
        val row = store.days(day.take(6)).firstOrNull { it.day == day }
        val dayStart = runCatching { SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(day + "000000")!!.time }.getOrNull()
            ?: return@withContext DayRoute(emptyList(), emptyList(), row?.count ?: 0)
        val span = row?.let { spanOf(it) }
        val from = span?.first ?: dayStart
        val to = span?.second?.plus(45 * 60_000L) ?: (dayStart + 86_400_000L)
        val stops = listOfNotNull(store.placeAtOrBefore(from)) + store.placesBetween(from, to)
        val legs = stops.zipWithNext().map { (a, b) -> routeLeg(a, b) }
        DayRoute(stops, legs, row?.count ?: 0)
    }

    private suspend fun routeLeg(a: Place, b: Place): RouteLeg {
        val pa = a.lat to a.lon
        val pb = b.lat to b.lon
        val crowM = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
        fun lengthKm(p: List<Pair<Double, Double>>) = p.zipWithNext().sumOf { (x, y) -> Geo.distanceM(x.first, x.second, y.first, y.second) } / 1000.0
        if (crowM < 120.0) return RouteLeg(listOf(pa, pb), false, crowM / 1000.0)
        val key = Router.key(pa, pb)
        val enc = store.kvGet(key) ?: Router.fetch(pa, pb)?.also { store.kvPut(key, it) }
        val pts = enc?.let { runCatching { Router.decode(it) }.getOrNull() }?.takeIf { it.size >= 2 }
        return if (pts != null) RouteLeg(pts, false, lengthKm(pts)) else RouteLeg(listOf(pa, pb), true, crowM / 1000.0)
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
        _data.value.car?.let { com.debarun.cretaremote.quick.WidgetState.saveTyre(ctx, tyreLine(it), it.tyreAttention, widgetPsi(it)) }
    }

    /** Per-wheel pressures for the tyre widget: the car's own numbers if it sends them, else the driver's last logged ones. */
    private fun widgetPsi(car: CarState): List<Double?> =
        if (car.tyrePsi.any { it != null }) car.tyrePsi else _data.value.tyreLog?.psi ?: List(4) { null }

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

    // ------------------------------------------------------------------ app updates

    private fun setUpdate(f: (UpdateUi) -> UpdateUi) = _data.update { it.copy(update = f(it.update)) }

    /** Looks for a newer GitHub release: on every open (at most every 15 minutes) and, quietly, from the background check (every 6 hours). */
    fun checkUpdates(manual: Boolean = false) { scope.launch { checkUpdatesNow(manual, 15 * 60_000L) } }

    suspend fun checkUpdatesNow(manual: Boolean, minGapMs: Long) {
        if (!manual && !_data.value.settings.checkUpdates) return
        val now = System.currentTimeMillis()
        if (!manual && now - (store.kvGet("upd_check")?.toLongOrNull() ?: 0L) < minGapMs) return
        val busy = _data.value.update.status
        if (busy == UpdateStatus.DOWNLOADING || busy == UpdateStatus.CHECKING) return
        if (manual) setUpdate { it.copy(status = UpdateStatus.CHECKING, message = null) }
        try {
            val rel = Updater.latest()
            store.kvPut("upd_check", now.toString())
            when {
                rel == null -> if (manual) setUpdate { it.copy(status = UpdateStatus.ERROR, message = "No published release with an APK found at github.com/${Updater.REPO}. If that repository is private, releases can't be read from the app.", checkedAt = now) }
                Updater.isNewer(rel.version, BuildConfig.VERSION_NAME) -> {
                    setUpdate { if (it.status == UpdateStatus.READY && it.release?.version == rel.version) it.copy(checkedAt = now) else UpdateUi(UpdateStatus.AVAILABLE, rel, checkedAt = now) }
                    if (store.kvGet("upd_notified") != rel.version) {
                        store.kvPut("upd_notified", rel.version)
                        Notifier.update(ctx, rel.version, rel.notes)
                    }
                }
                else -> setUpdate { UpdateUi(UpdateStatus.UP_TO_DATE, checkedAt = now) }
            }
        } catch (e: Exception) {
            if (manual) setUpdate { it.copy(status = UpdateStatus.ERROR, message = friendly(e), checkedAt = now) }
            else setUpdate { if (it.status == UpdateStatus.CHECKING) it.copy(status = UpdateStatus.IDLE) else it }
        }
    }

    /** Downloads the available release and opens Android's installer, which updates the app in place. */
    fun installUpdate() { scope.launch { installUpdateNow() } }

    private suspend fun installUpdateNow() {
        val rel = _data.value.update.release ?: return
        if (_data.value.update.status == UpdateStatus.DOWNLOADING) return
        if (!Updater.canInstall(ctx)) {
            setUpdate { it.copy(message = "Allow \"Install unknown apps\" for Creta Remote on the page that just opened, then tap Update again.") }
            Updater.openInstallPermission(ctx)
            return
        }
        val file = java.io.File(ctx.cacheDir, "updates/creta-remote-${rel.version}.apk")
        try {
            if (!file.exists() || (rel.bytes > 0 && file.length() != rel.bytes)) {
                setUpdate { it.copy(status = UpdateStatus.DOWNLOADING, progress = 0f, message = null) }
                Updater.download(rel, file) { p -> setUpdate { u -> u.copy(progress = p) } }
            }
            val why = withContext(Dispatchers.IO) { Updater.problem(ctx, file) }
            if (why != null) {
                file.delete()
                setUpdate { it.copy(status = UpdateStatus.ERROR, message = why) }
                return
            }
            setUpdate { it.copy(status = UpdateStatus.READY, message = "Confirm in the installer to finish. Your data stays.") }
            Updater.launchInstaller(ctx, file)
        } catch (e: Exception) {
            setUpdate { it.copy(status = UpdateStatus.ERROR, message = friendly(e)) }
        }
    }

    /** Removes downloaded installers once the app has been updated to (or past) them. */
    private fun cleanUpdates() {
        java.io.File(ctx.cacheDir, "updates").listFiles()?.forEach { f ->
            val v = Updater.parseVersion(f.name.removePrefix("creta-remote-")) ?: return@forEach
            if (!Updater.isNewer(v.joinToString("."), BuildConfig.VERSION_NAME)) f.delete()
        }
    }

    // ------------------------------------------------------------------ wake-up

    @Volatile private var lastActivity = 0L
    @Volatile private var lastPing = store.kvGet("link")?.split("|")?.getOrNull(2)?.toLongOrNull() ?: 0L

    /** The last time the car is known to have answered (a live request, a command, or a drive read). Persisted, so closing the app does not forget it. */
    private fun lastContact(): Long = maxOf(lastPing, if (_data.value.link == Link.AWAKE) _data.value.linkAt ?: 0L else 0L)

    private fun persistLink() {
        val d = _data.value
        if (d.link != Link.AWAKE && d.link != Link.ASLEEP) return
        store.kvPut("link", "${d.link.name}|${d.linkAt ?: 0L}|$lastPing")
    }
    @Volatile private var lastQuick = 0L
    private var keepJob: Job? = null

    private fun hasCreds() = vault.get("email") != null && vault.get("password") != null

    /**
     * Reading the dashboard never needs a wake-up: the car pushes its status to Bluelink's cloud and that copy is what the
     * official app shows too. So while the app is on screen it just re-reads the cloud copy (status only, one request): every
     * 30 s with the engine on, every minute otherwise. Only commands need the modem awake. If "Wake the car on open" is
     * switched on, a live status request is also sent on open and every few minutes for [KEEP_AWAKE_MS], so commands are quicker.
     */
    fun onForeground(visible: Boolean) {
        AppForeground.visible = visible
        if (!visible) { keepJob?.cancel(); keepJob = null; return }
        checkUpdates()
        if (!hasCreds()) return
        lastActivity = System.currentTimeMillis()
        if (keepJob?.isActive == true) return
        keepJob = scope.launch {
            while (AppForeground.visible) {
                val nowMs = System.currentTimeMillis()
                val engine = _data.value.car?.engineOn == true
                val idle = !_data.value.cmd.busy
                if (idle && _data.value.settings.keepAwake && !engine && nowMs - lastActivity < KEEP_AWAKE_MS && nowMs - lastContact() > PING_EVERY_MS) {
                    wakePing()
                } else if (idle && !DriveState.active && nowMs - lastQuick > (if (engine) 30_000L else 60_000L)) {
                    lastQuick = nowMs
                    refresh(live = false, quiet = true, light = true)
                }
                delay(5.seconds)
            }
        }
    }

    /**
     * Live sync: asks the car for fresh data, which is what wakes its modem, and keeps the "Waking car / Car awake / Car asleep"
     * chip honest. A car that does not answer in time is "asleep"; a network or sign-in problem says nothing about the car, so
     * the chip is left as it was. Returns true when the car answered, false when it did not, null when nothing was done.
     */
    suspend fun syncLive(quiet: Boolean = false): Boolean? {
        if (_data.value.cmd.busy || !hasCreds()) return null
        lastPing = System.currentTimeMillis()
        val before = _data.value.link
        _data.update { it.copy(link = Link.WAKING) }
        val ok = refresh(live = true, quiet = quiet)
        val e = lastRefreshError
        val noAnswer = e is java.net.SocketTimeoutException || (e is BluelinkException && e.code in setOf("4081", "9999", "5921"))
        _data.update {
            when {
                ok == true -> it.copy(link = Link.AWAKE, linkAt = System.currentTimeMillis())
                ok == false && noAnswer -> it.copy(link = Link.ASLEEP, linkAt = System.currentTimeMillis())
                else -> it.copy(link = before)
            }
        }
        persistLink()
        return ok
    }

    /** One wake-up call, used by the keep-awake loop and when the Quick Settings panel is opened. */
    suspend fun wakePing() {
        syncLive(quiet = true)
    }

    /** For callers outside the app (Quick Settings tiles): wake the car if it has not been pinged lately. */
    fun wakeIfIdle() {
        if (!_data.value.settings.keepAwake || !hasCreds()) return
        if (System.currentTimeMillis() - lastContact() < PING_EVERY_MS) return
        scope.launch { wakePing() }
    }

    /**
     * One drive, run by [com.debarun.cretaremote.service.DriveService]. The engine is on, so the modem is already up and nothing
     * here wakes anything: it reads the cloud copy every 30 s, asks the car directly every 2 minutes (the cloud copy can lag),
     * and stores each reading, so the odometer and fuel gauge are captured as the car moves. It returns a few minutes after the
     * engine goes off, sooner when the car's Bluetooth has dropped. [onTick] receives a line for the notification.
     */
    suspend fun driveSession(onTick: (String) -> Unit) {
        if (!hasCreds()) return
        val t0 = System.currentTimeMillis()
        var lastLive = 0L
        var offSince = 0L
        var failures = 0
        while (System.currentTimeMillis() - t0 < com.debarun.cretaremote.service.DriveService.MAX_MS) {
            val nowMs = System.currentTimeMillis()
            val live = nowMs - lastLive >= DRIVE_LIVE_MS
            val ok = if (_data.value.cmd.busy) null else refresh(live = live, quiet = true, light = true)
            if (ok != null && live) lastLive = nowMs
            if (ok == true) {
                failures = 0
                _data.update { it.copy(link = Link.AWAKE, linkAt = System.currentTimeMillis()) }
                persistLink()
                val car = _data.value.car
                if (car?.engineOn == true) {
                    // a cached copy that has not changed since the engine was seen off can be stale; only a live answer or a newer report counts
                    if (live || (car.updatedAt ?: 0L) > offSince) offSince = 0L
                } else if (offSince == 0L) offSince = nowMs
            } else if (ok == false) {
                val e = lastRefreshError
                // a live request the car does not answer means its modem has gone to sleep, which is what the engine going off looks like
                val noAnswer = live && (e is java.net.SocketTimeoutException || (e is BluelinkException && e.code in setOf("4081", "9999", "5921")))
                if (noAnswer) { if (offSince == 0L) offSince = nowMs } else failures++
            }
            val lt = _data.value.liveTrip
            onTick(
                when {
                    failures >= 3 -> "Can't reach Bluelink, retrying…"
                    lt != null -> "%.1f km this trip".format(Locale.US, lt.km) + (lt.kmpl?.let { " · %.1f km/L".format(Locale.US, it) } ?: "")
                    offSince != 0L -> "Engine off, finishing up…"
                    else -> "Waiting for the car…"
                },
            )
            // the engine must read off for a few minutes (a minute once the car's Bluetooth has gone) and the session must be 3 minutes old
            val offFor = if (com.debarun.cretaremote.service.DriveState.btOffAt > 0L) 60_000L else 4 * 60_000L
            if (offSince != 0L && nowMs - offSince >= offFor && nowMs - t0 >= 3 * 60_000L) break
            if (failures >= 12) break
            delay((if (failures == 0) 30L else if (failures < 3) 45L else 90L).seconds)
        }
        // one last live read for the closing odometer and fuel, then pick up the trip Bluelink recorded
        if (!_data.value.cmd.busy) refresh(live = true, quiet = true)
        loadTrips(currentMonth())
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
                delay(when { polls == 0 -> 700.milliseconds; polls < 12 -> 1.seconds; else -> 2.seconds }); polls++
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
                    persistLink()
                    lastActivity = System.currentTimeMillis()
                    log(Outcome.SUCCESS, doneText(cmd))
                    scope.launch { delay(4.seconds); refresh(live = false) }
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
        scope.launch { delay(if (_data.value.cmd.phase == Phase.CANCELLED) 5.seconds else 3_500.milliseconds); _data.update { if (it.cmd.busy) it else it.copy(cmd = CmdUi()) } }
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
        const val PASSWORD_WARN_DAYS = 7
        const val PING_EVERY_MS = 3 * 60_000L
        const val DRIVE_LIVE_MS = 2 * 60_000L
    }

    private fun setCmd(c: Cmd, p: Phase, m: String) = _data.update { it.copy(cmd = CmdUi(c, p, m)) }
}
