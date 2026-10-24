package com.debarun.cretaremote.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.debarun.cretaremote.CretaApp
import com.debarun.cretaremote.MainActivity
import com.debarun.cretaremote.R
import com.debarun.cretaremote.data.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Shared flags between the service, the Bluetooth trigger and the repository. */
object DriveState {
    /** True while a drive session is running, so the open screen does not poll a second time. */
    @Volatile var active = false

    /** When the car's Bluetooth dropped (ms), 0 while it is connected or not used. Lets a session end sooner. */
    @Volatile var btOffAt = 0L

    @Volatile internal var lastTry = 0L
}

/**
 * Keeps the app attached to the car for as long as the engine is on: a foreground service that reads the car's cloud copy
 * every 30 seconds and asks the car directly every couple of minutes. The engine being on already means the car's modem is up,
 * so none of this is a wake-up call. It ends by itself a few minutes after the engine goes off.
 */
class DriveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var locListener: LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val repo = (application as CretaApp).repo
        // recording the route needs the location type; Android only allows that from the background with "Allow all the time"
        val wantRoute = repo.data.value.settings.recordRoute && com.debarun.cretaremote.ui.Perms.fineLocationAllowed(this)
        var recording = false
        try {
            if (wantRoute) {
                try {
                    ServiceCompat.startForeground(this, ID, build("Connecting to the car…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    recording = true
                } catch (e: Exception) {
                    ServiceCompat.startForeground(this, ID, build("Connecting to the car…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
            } else {
                ServiceCompat.startForeground(this, ID, build("Connecting to the car…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } catch (e: Exception) {
            // Android refused to let a background app start this; the next trigger will try again
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_STICKY
        DriveState.active = true
        // the CPU must stay awake between polls or a screen-off phone would sleep through its own timers
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cretaremote:drive")
            .apply { setReferenceCounted(false); acquire(MAX_MS + 60_000L) }
        if (recording) startTrack(repo)
        repo.pruneTrack()
        job = scope.launch {
            try {
                repo.driveSession { text -> update(text) }
            } finally {
                DriveState.active = false
                stopSelf()
            }
        }
        // if the system kills the process mid-drive, ask for the session back; it ends itself once the engine has been off a while
        return START_STICKY
    }

    /** Android 15 caps data-sync services at about six hours; stop cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        DriveState.active = false
        stopTrack()
        scope.cancel()
        try { wake?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startTrack(repo: com.debarun.cretaremote.data.Repository) {
        if (locListener != null) return
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.hasAccuracy() && location.accuracy > 60f) return
                val ts = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                repo.recordTrackPoint(ts, location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else null, if (location.hasSpeed()) location.speed else null)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 10f, l, Looper.getMainLooper())
            locListener = l
        } catch (e: Exception) {
            // GPS is off or the permission was withdrawn: the drive session still runs without a route
        }
    }

    private fun stopTrack() {
        val l = locListener ?: return
        try { (getSystemService(LOCATION_SERVICE) as? LocationManager)?.removeUpdates(l) } catch (e: Exception) { }
        locListener = null
    }

    private fun update(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(ID, build(text))
        } catch (e: SecurityException) {
            // notifications were switched off; the service keeps running
        }
    }

    private fun build(text: String): Notification {
        Notifier.ensureChannels(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DriveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifier.CH_DRIVE)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setColor(0xFFE3192D.toInt())
            .setContentTitle("Creta connected")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val ID = 310
        private const val ACTION_STOP = "com.debarun.cretaremote.DRIVE_STOP"
        const val MAX_MS = 10 * 3_600_000L

        /** Starts a drive session. False when Android would not allow it from where we are (app in the background without an exemption). */
        fun start(ctx: Context): Boolean {
            if (DriveState.active) return true
            val now = System.currentTimeMillis()
            if (now - DriveState.lastTry < 20_000L) return false
            DriveState.lastTry = now
            return try {
                ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx.applicationContext, DriveService::class.java))
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
