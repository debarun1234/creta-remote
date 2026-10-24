package com.debarun.cretaremote.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Snapshot(val ts: Long, val odo: Double?, val fuel: Double?, val dte: Double?, val locked: Boolean?, val engine: Boolean?)
data class Place(val ts: Long, val lat: Double, val lon: Double, val label: String?)
data class DayRow(val day: String, val count: Int, val dist: Double, val drive: Int, val idle: Int, val avg: Double, val max: Double, val trips: String, val fetched: Long)

/** Local history: odometer/fuel snapshots, parking places and cached trip days. Plain SQLite, no extra deps. */
data class TrackPoint(val ts: Long, val lat: Double, val lon: Double)

class Store(context: Context) : SQLiteOpenHelper(context, "creta.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE snapshots(ts INTEGER PRIMARY KEY, odo REAL, fuel REAL, dte REAL, locked INTEGER, engine INTEGER)")
        db.execSQL("CREATE TABLE places(ts INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, label TEXT)")
        db.execSQL("CREATE TABLE trip_days(day TEXT PRIMARY KEY, cnt INTEGER, dist REAL, drive INTEGER, idle INTEGER, avg REAL, max REAL, trips TEXT, fetched INTEGER)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
        createTrack(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTrack(db)
    }

    private fun createTrack(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS track(ts INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, acc REAL, spd REAL)")
    }

    @Synchronized
    fun addTrack(ts: Long, lat: Double, lon: Double, acc: Float?, spd: Float?) {
        val cv = ContentValues().apply { put("ts", ts); put("lat", lat); put("lon", lon); put("acc", acc); put("spd", spd) }
        writableDatabase.insertWithOnConflict("track", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** GPS positions recorded in [from, to], oldest first. */
    @Synchronized
    fun track(from: Long, to: Long): List<TrackPoint> {
        val out = mutableListOf<TrackPoint>()
        readableDatabase.rawQuery("SELECT ts,lat,lon FROM track WHERE ts>=? AND ts<=? ORDER BY ts ASC", arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += TrackPoint(c.getLong(0), c.getDouble(1), c.getDouble(2))
        }
        return out
    }

    @Synchronized
    fun pruneTrack(before: Long) {
        writableDatabase.execSQL("DELETE FROM track WHERE ts<?", arrayOf<Any>(before))
    }

    @Synchronized
    fun clearTrack() {
        writableDatabase.execSQL("DELETE FROM track")
    }

    @Synchronized
    fun addSnapshot(s: Snapshot) {
        val cv = ContentValues().apply {
            put("ts", s.ts)
            put("odo", s.odo)
            put("fuel", s.fuel)
            put("dte", s.dte)
            put("locked", s.locked?.let { if (it) 1 else 0 })
            put("engine", s.engine?.let { if (it) 1 else 0 })
        }
        writableDatabase.insertWithOnConflict("snapshots", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun snapshots(sinceTs: Long = 0L): List<Snapshot> {
        val out = mutableListOf<Snapshot>()
        readableDatabase.rawQuery("SELECT ts,odo,fuel,dte,locked,engine FROM snapshots WHERE ts>=? ORDER BY ts ASC", arrayOf(sinceTs.toString())).use { c ->
            while (c.moveToNext()) {
                out += Snapshot(
                    c.getLong(0),
                    if (c.isNull(1)) null else c.getDouble(1),
                    if (c.isNull(2)) null else c.getDouble(2),
                    if (c.isNull(3)) null else c.getDouble(3),
                    if (c.isNull(4)) null else c.getInt(4) == 1,
                    if (c.isNull(5)) null else c.getInt(5) == 1,
                )
            }
        }
        return out
    }

    @Synchronized
    fun lastSnapshot(): Snapshot? =
        readableDatabase.rawQuery("SELECT ts,odo,fuel,dte,locked,engine FROM snapshots ORDER BY ts DESC LIMIT 1", null).use { c ->
            if (!c.moveToFirst()) null else Snapshot(
                c.getLong(0),
                if (c.isNull(1)) null else c.getDouble(1),
                if (c.isNull(2)) null else c.getDouble(2),
                if (c.isNull(3)) null else c.getDouble(3),
                if (c.isNull(4)) null else c.getInt(4) == 1,
                if (c.isNull(5)) null else c.getInt(5) == 1,
            )
        }

    @Synchronized
    fun addPlace(p: Place) {
        val cv = ContentValues().apply { put("ts", p.ts); put("lat", p.lat); put("lon", p.lon); put("label", p.label) }
        writableDatabase.insertWithOnConflict("places", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun setPlaceLabel(ts: Long, label: String) {
        writableDatabase.execSQL("UPDATE places SET label=? WHERE ts=?", arrayOf<Any>(label, ts))
    }

    @Synchronized
    fun places(limit: Int = 60): List<Place> {
        val out = mutableListOf<Place>()
        readableDatabase.rawQuery("SELECT ts,lat,lon,label FROM places ORDER BY ts DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out += Place(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getString(3))
        }
        return out
    }

    /** Parking spots recorded in (from, to], oldest first. */
    @Synchronized
    fun placesBetween(from: Long, to: Long): List<Place> {
        val out = mutableListOf<Place>()
        readableDatabase.rawQuery("SELECT ts,lat,lon,label FROM places WHERE ts>? AND ts<=? ORDER BY ts ASC", arrayOf(from.toString(), to.toString())).use { c ->
            while (c.moveToNext()) out += Place(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getString(3))
        }
        return out
    }

    /** The last parking spot at or before [ts]: where the car was when a trip began. */
    @Synchronized
    fun placeAtOrBefore(ts: Long): Place? =
        readableDatabase.rawQuery("SELECT ts,lat,lon,label FROM places WHERE ts<=? ORDER BY ts DESC LIMIT 1", arrayOf(ts.toString())).use { c ->
            if (c.moveToFirst()) Place(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getString(3)) else null
        }

    @Synchronized
    fun firstPlaceTs(): Long? =
        readableDatabase.rawQuery("SELECT MIN(ts) FROM places", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    @Synchronized
    fun upsertDay(d: DayRow) {
        val cv = ContentValues().apply {
            put("day", d.day); put("cnt", d.count); put("dist", d.dist); put("drive", d.drive); put("idle", d.idle)
            put("avg", d.avg); put("max", d.max); put("trips", d.trips); put("fetched", d.fetched)
        }
        writableDatabase.insertWithOnConflict("trip_days", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun days(prefix: String): List<DayRow> {
        val out = mutableListOf<DayRow>()
        readableDatabase.rawQuery("SELECT day,cnt,dist,drive,idle,avg,max,trips,fetched FROM trip_days WHERE day LIKE ? ORDER BY day ASC", arrayOf("$prefix%")).use { c ->
            while (c.moveToNext()) {
                out += DayRow(c.getString(0), c.getInt(1), c.getDouble(2), c.getInt(3), c.getInt(4), c.getDouble(5), c.getDouble(6), c.getString(7) ?: "[]", c.getLong(8))
            }
        }
        return out
    }

    @Synchronized
    fun kvGet(k: String): String? =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    @Synchronized
    fun kvPut(k: String, v: String) {
        val cv = ContentValues().apply { put("k", k); put("v", v) }
        writableDatabase.insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun wipe() {
        writableDatabase.execSQL("DELETE FROM snapshots"); writableDatabase.execSQL("DELETE FROM places")
        writableDatabase.execSQL("DELETE FROM trip_days"); writableDatabase.execSQL("DELETE FROM kv")
    }
}
