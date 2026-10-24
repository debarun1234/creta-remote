package com.debarun.cretaremote

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.debarun.cretaremote.data.Repository
import com.debarun.cretaremote.work.SnapshotWorker
import java.util.concurrent.TimeUnit

class CretaApp : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
        // Mappls map: a missing or mismatched config only means the app draws its own OpenStreetMap view instead
        try { com.mappls.sdk.maps.Mappls.getInstance(this) } catch (e: Throwable) { android.util.Log.w("Mappls", "not started: ${e.message}") }
        com.debarun.cretaremote.data.Notifier.ensureChannels(this)
        // Builds fuel / mileage history in the background (cheap cloud read, never wakes the car).
        val req = PeriodicWorkRequestBuilder<SnapshotWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("snapshots", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
