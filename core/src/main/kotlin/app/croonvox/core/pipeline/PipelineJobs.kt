// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.pipeline

import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.TrackId

/**
 * Runs background jobs that survive process death (ADR 10): the Track Pipeline's steps, and the
 * Lyrics search that runs beside it (ADR 6). Implemented with WorkManager; faked in tests.
 */
interface PipelineJobs {
    /**
     * Queues [SeparateTrack] for [track] with [model], after any job already queued or running
     * for the Track with that model. Calling it again is harmless: a job finding the Track done
     * does nothing. Jobs are keyed by Track and Separation Model (ADR 10): a job with another model
     * isn't waited for, so whoever switches the model stops it first ([TrackPipeline.reseparate]).
     */
    fun separate(track: TrackId, model: SeparationModelId)

    /** Queues [DownloadTrack] for [track]. Calling it again is harmless. */
    fun download(track: TrackId)

    /**
     * Queues [app.croonvox.core.lyrics.FetchLyrics] for [track], to run once there is a network.
     * Calling it again is harmless.
     */
    fun fetchLyrics(track: TrackId)

    /** Cancels every Separation queued or running for [track], whatever its model, and waits until they have stopped. */
    suspend fun cancelSeparation(track: TrackId)

    /** Cancels every job queued or running for [track] (it is being deleted) and waits until they have stopped. */
    suspend fun cancelAll(track: TrackId)
}
