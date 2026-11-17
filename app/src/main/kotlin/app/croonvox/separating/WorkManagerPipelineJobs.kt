// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separating

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.TrackId
import app.croonvox.core.pipeline.PipelineJobs
import java.util.concurrent.TimeUnit

/** [PipelineJobs] on WorkManager: jobs survive process death and retry with backoff (ADR 10). */
class WorkManagerPipelineJobs(private val workManager: WorkManager) : PipelineJobs {
    override fun separate(track: TrackId, model: SeparationModelId) {
        val request = OneTimeWorkRequestBuilder<SeparationWorker>()
            .setInputData(
                workDataOf(
                    SeparationWorker.SOURCE to track.source.value,
                    SeparationWorker.SOURCE_TRACK_ID to track.sourceTrackId,
                    SeparationWorker.MODEL to model.value,
                ),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(SeparationWorker.TAG)
            .build()
        // One chain of Separation jobs per Track. A job still queued or running is kept, and the new
        // one runs after it: a job that is just finishing (e.g. the user retried the moment it
        // wrote Failed) would otherwise swallow the new request. A job that finds the Track
        // already done does nothing (SeparateTrack), so a redundant one costs nothing.
        workManager.enqueueUniqueWork("separate:${track.key}", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
