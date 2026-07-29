package com.gios.lightnews.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gios.lightnews.data.NewsRepository
import com.gios.lightnews.data.SyncResult
import java.util.concurrent.TimeUnit

/**
 * Background fetch.
 *
 * Gmail's push notifications route through Cloud Pub/Sub to Firebase, and Firebase
 * needs Play Services, which LightOS does not have. So this polls. WorkManager itself
 * is fine without Play Services — it sits on JobScheduler.
 *
 * Hourly on unmetered only. Newsletters are published on a human schedule; nothing
 * here is worth a cellular wake-up.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return when (val result = NewsRepository.get(applicationContext).sync()) {
            // A first sync only fetches a capped number of messages. Retry rather than
            // waiting an hour to collect the rest of the backlog.
            is SyncResult.Ok -> if (result.more && runAttemptCount < 6) Result.retry() else Result.success()
            // Nothing a retry can fix: the user has to sign in or fix the label name.
            SyncResult.NeedsAuth, SyncResult.NoLabel -> Result.success()
            is SyncResult.Failed -> if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val NAME = "lightnews-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                // KEEP, so reopening the app doesn't reset the interval and starve the job.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
