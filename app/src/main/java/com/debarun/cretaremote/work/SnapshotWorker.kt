package com.debarun.cretaremote.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.debarun.cretaremote.CretaApp

class SnapshotWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as CretaApp).repo
        val ok = repo.backgroundSnapshot()
        if (repo.needsFastWatch()) repo.scheduleFastWatch(append = true)
        return if (ok) Result.success() else Result.retry()
    }
}
