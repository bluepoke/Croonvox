// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.separating

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.pipeline.PipelineJobs
import eu.schicht8.meavox.downloading.DownloadWorker
import eu.schicht8.meavox.lyricsearch.LyricsWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * [PipelineJobs] on WorkManager: jobs survive process death and retry with backoff (ADR 10).
 * Lyrics searches wait for a network. Separation jobs are unique per Track and Separation Model,
 * and tagged per Track, so all of a Track's Separations can be cancelled whatever their model.
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
            .addTag(separationsOf(track))
            .build()
        // One chain of Separation jobs per Track and model. A job still queued or running is kept,
        // and the new one runs after it: a job that is just finishing (e.g. the user retried the
        // moment it wrote Failed) would otherwise swallow the new request. A job that finds the
        // Track already done does nothing (SeparateTrack), so a redundant one costs nothing.
        workManager.enqueueUniqueWork(separation(track, model), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
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

    // The legacy name too: a job of an earlier version may still run until stopLegacySeparations is done.
    override suspend fun cancelSeparation(track: TrackId) =
        cancelAndWait(tags = listOf(separationsOf(track)), names = listOf(legacySeparation(track)))

    override suspend fun cancelAll(track: TrackId) =
        cancelAndWait(tags = listOf(separationsOf(track)), names = listOf(legacySeparation(track)) + listOf(DOWNLOAD, LYRICS).map { it.of(track) })

    /**
     * Stops Separation jobs queued by versions before #28, named `separate:<track>` without the
     * model and without the per-Track tag, and waits until they have stopped. Run it before the
     * Track Pipeline starts: it queues each Track still Separating again under the new name, and
     * the job continues from its checkpoint. Without this, the old job would run first and the new
     * one would wait for it (SeparateTrack), and cancelling the Track's Separations would miss it.
     */
    suspend fun stopLegacySeparations() {
        val legacy = workManager.getWorkInfosByTagFlow(SeparationWorker.TAG).first()
            .filter { info -> !info.state.isFinished && info.tags.none { it.startsWith(SEPARATIONS_OF) } }
            .map { it.id }
        legacy.forEach(workManager::cancelWorkById)
        withTimeoutOrNull(CANCEL_TIMEOUT_MS) {
            legacy.forEach { id -> workManager.getWorkInfoByIdFlow(id).first { it == null || it.state.isFinished } }
        }
    }

    private suspend fun cancelAndWait(tags: List<String>, names: List<String> = emptyList()) {
        tags.forEach(workManager::cancelAllWorkByTag)
        names.forEach(workManager::cancelUniqueWork)
        // A running worker is stopped through coroutine cancellation; wait until WorkManager reports it.
        withTimeoutOrNull(CANCEL_TIMEOUT_MS) {
            tags.forEach { tag -> workManager.getWorkInfosByTagFlow(tag).first { infos -> infos.all { it.state.isFinished } } }
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
        val DOWNLOAD = JobKind("download")
        val LYRICS = JobKind("lyrics")
        const val CANCEL_TIMEOUT_MS = 10_000L
        const val SEPARATIONS_OF = "separation:"

        /** The unique work name of the Separation chain of [track] with [model] (ADR 10). */
        fun separation(track: TrackId, model: SeparationModelId) = "separate:${track.key}:${model.value}"

        /** The unique work name of [track]'s Separation chain in versions before #28. */
        fun legacySeparation(track: TrackId) = "separate:${track.key}"

        /** The tag of every Separation job of [track], whatever its model. */
        fun separationsOf(track: TrackId) = "$SEPARATIONS_OF${track.key}"
    }
}
