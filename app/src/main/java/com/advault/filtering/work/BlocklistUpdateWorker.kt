package com.advault.filtering.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.advault.core.common.UpdateFrequency
import com.advault.data.repository.BlocklistRepository
import com.advault.data.repository.FilterEngine
import com.advault.filtering.data.BlocklistUpdateScheduler
import com.advault.filtering.data.UpdateSchedule
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Periodic blocklist refresh. Only downloads validated text lists over HTTPS. */
@HiltWorker
class BlocklistUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: BlocklistRepository,
    private val engine: FilterEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val report = repository.updateAll(force = false)
        engine.reload()
        val allFailed = report.failed.isNotEmpty() && report.updated.isEmpty() && report.unchanged.isEmpty()
        return if (allFailed && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "advault-blocklist-update"
        private const val MAX_RETRIES = 3
    }
}

class WorkManagerUpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : BlocklistUpdateScheduler {

    override fun apply(schedule: UpdateSchedule) {
        val wm = WorkManager.getInstance(context)
        if (schedule.frequency == UpdateFrequency.MANUAL || schedule.frequency.hours <= 0) {
            wm.cancelUniqueWork(BlocklistUpdateWorker.UNIQUE_NAME)
            return
        }
        wm.enqueueUniquePeriodicWork(
            BlocklistUpdateWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            buildRequest(schedule),
        )
    }

    companion object {
        fun buildRequest(schedule: UpdateSchedule) =
            PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(schedule.frequency.hours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (schedule.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .addTag(BlocklistUpdateWorker.UNIQUE_NAME)
                .build()
    }
}
