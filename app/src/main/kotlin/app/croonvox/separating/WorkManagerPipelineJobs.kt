// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separating

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.TrackId
import app.croonvox.core.pipeline.PipelineJobs
import app.croonvox.downloading.DownloadWorker
import app.croonvox.lyricsearch.LyricsWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * [PipelineJobs] on WorkManager: jobs survive process death and retry with backoff (ADR 10).
 * Lyrics searches wait for a network.
 */
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
        workManager.enqueueUniqueWork(SEPARATE.of(track), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun download(track: TrackId) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.SOURCE to track.source.value, DownloadWorker.SOURCE_TRACK_ID to track.sourceTrackId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Like Separation: queued behind a job still running, which a retry may race with.
        workManager.enqueueUniqueWork(DOWNLOAD.of(track), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override suspend fun cancelSeparation(track: TrackId) = cancelAndWait(listOf(SEPARATE.of(track)))

    override suspend fun cancelAll(track: TrackId) = cancelAndWait(listOf(SEPARATE, DOWNLOAD, LYRICS).map { it.of(track) })

    private suspend fun cancelAndWait(names: List<String>) {
        names.forEach(workManager::cancelUniqueWork)
        // A running worker is stopped through coroutine cancellation; wait until WorkManager reports it.
        withTimeoutOrNull(CANCEL_TIMEOUT_MS) {
            names.forEach { name -> workManager.getWorkInfosForUniqueWorkFlow(name).first { infos -> infos.all { it.state.isFinished } } }
        }
    }

    override fun fetchLyrics(track: TrackId) {
        val request = OneTimeWorkRequestBuilder<LyricsWorker>()
            .setInputData(workDataOf(LyricsWorker.SOURCE to track.source.value, LyricsWorker.SOURCE_TRACK_ID to track.sourceTrackId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // A search that finds the Lyrics settled does nothing (FetchLyrics), so keeping a queued one is enough.
        workManager.enqueueUniqueWork(LYRICS.of(track), ExistingWorkPolicy.KEEP, request)
    }

    /** One chain of unique work per Track and kind of job. */
    @JvmInline
    private value class JobKind(val prefix: String) {
        fun of(track: TrackId) = "$prefix:${track.key}"
    }

    private companion object {
        val SEPARATE = JobKind("separate")
        val DOWNLOAD = JobKind("download")
        val LYRICS = JobKind("lyrics")
        const val CANCEL_TIMEOUT_MS = 10_000L
    }
}
