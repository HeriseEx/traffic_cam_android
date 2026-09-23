package com.example.illegalcapture

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Android owns network availability, process restart and backoff. */
class UploadJobService : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(Dispatchers.IO).launch {
            val sync = TaskSync.get(this@UploadJobService)
            try { sync.tick() } finally { if (isActive) jobFinished(params, sync.needsBackgroundWork()) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); work = null; return true }

    companion object {
        private const val ID = 61616
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(ID) != null) return
            scheduler.schedule(JobInfo.Builder(ID, ComponentName(context, UploadJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(15_000)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build())
        }
    }
}
