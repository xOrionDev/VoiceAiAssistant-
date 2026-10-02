package tj.orion.recorder

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

/** Schedules uploads: hourly, plus an immediate run when enough text is waiting. */
object SyncScheduler {
    private const val JOB_PERIODIC = 100
    private const val JOB_NOW = 101

    fun schedulePeriodic(ctx: Context) {
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(JOB_PERIODIC, ComponentName(ctx, SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(Config.SYNC_PERIOD_MS)
                .setPersisted(true)
                .build()
            js.schedule(job)
        } catch (t: Throwable) {
            Diag.log(ctx, "schedulePeriodic EXC $t")
        }
    }

    /** Fire a one-off upload now (e.g. after a recording, or when the buffer is big). */
    fun kickNow(ctx: Context) {
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(JOB_NOW, ComponentName(ctx, SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(0)
                .build()
            js.schedule(job)
        } catch (t: Throwable) {
            Diag.log(ctx, "kickNow EXC $t")
        }
    }

    /** Kick immediately only if enough text has piled up. */
    fun kickIfThreshold(ctx: Context) {
        try {
            val chars = Db(ctx).use { it.pendingCharCount() }
            if (chars >= Config.SYNC_CHAR_THRESHOLD) kickNow(ctx)
        } catch (t: Throwable) {
            Diag.log(ctx, "kickIfThreshold EXC $t")
        }
    }
}

class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            val ok = try { SyncClient.syncNow(this) } catch (_: Exception) { false }
            try { Db(this).use { it.purgeOlderThan(Config.LOCAL_PURGE_DAYS) } } catch (_: Exception) {}
            jobFinished(params, !ok) // reschedule if it failed (e.g. offline)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
