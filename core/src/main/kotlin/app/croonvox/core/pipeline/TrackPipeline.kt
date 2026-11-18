// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.pipeline

import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.PipelineStep
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState

/**
 * Moves Tracks through the Track Pipeline (ADR 10): a Track added from one of the network Sources
 * in [downloadFrom] is queued for download, and every Track that reaches Downloaded is queued for
 * Separation with [defaultModel], or is Ready at once if it is Vocal-Free (ADR 14). Local imports aren't downloaded: a local Track left in Added is
 * an import that never finished, and importing the file again completes it.
 */
class TrackPipeline(
    private val tracks: TrackRepository,
    private val jobs: PipelineJobs,
    private val defaultModel: SeparationModelId,
    private val downloadFrom: Set<SourceId> = emptySet(),
) {
    /** Follows the Library until cancelled; runs for the lifetime of the app process. */
    suspend fun run() {
        // Tracks this run has queued a job for. Each Track is queued once per run: progress
        // updates change the Track many times while its job runs.
        val queued = mutableSetOf<TrackId>()
        tracks.observeAll().collect { all ->
            for (track in all) {
                when (val state = track.pipeline) {
                    TrackPipelineState.Added -> if (track.id.source in downloadFrom) {
                        tracks.updatePipeline(track.id, TrackPipelineState.Downloading())
                        queued += track.id
                        jobs.download(track.id)
                    }
                    // Left Downloading by an earlier process (or queued above): make sure its job exists.
                    is TrackPipelineState.Downloading -> if (queued.add(track.id)) jobs.download(track.id)
                    TrackPipelineState.Downloaded -> {
                        // One atomic step, so marking the Track Vocal-Free meanwhile is never missed.
                        val next = tracks.update(track.id) { now ->
                            if (now.pipeline != TrackPipelineState.Downloaded) return@update null
                            // A Vocal-Free Track skips Separation (ADR 14).
                            now.copy(pipeline = if (now.vocalFree) TrackPipelineState.Ready(model = null) else TrackPipelineState.Separating(defaultModel))
                        }
                        if (next?.pipeline == TrackPipelineState.Separating(defaultModel)) {
                            queued += track.id
                            jobs.separate(track.id, defaultModel)
                        }
                    }
                    // Left Separating by an earlier process (or queued above): make sure its job exists.
                    is TrackPipelineState.Separating -> if (queued.add(track.id)) jobs.separate(track.id, state.model)
                    else -> queued -= track.id
                }
            }
        }
    }

    /**
     * Separates a Track that is Separating with another model with [model] instead (ADR 10, #28):
     * the running Separation is replaced, not queued behind. Its checkpoint stays, so separating
     * with that model again continues where it stopped. Re-separating a Ready Track comes with #17.
     */
    suspend fun reseparate(id: TrackId, model: SeparationModelId) {
        // State first: the replaced job writes only while the Track waits for its model (SeparateTrack).
        var replaced = false
        tracks.update(id) { track ->
            val now = track.pipeline
            replaced = now is TrackPipelineState.Separating && now.model != model
            if (replaced) track.copy(pipeline = TrackPipelineState.Separating(model)) else null
        }
        if (!replaced) return
        // Then stop the replaced job, and only then queue the new one, which the cancel would stop
        // too. If the replaced job is slow to stop, the new one waits for it (SeparateTrack).
        jobs.cancelSeparation(id)
        jobs.separate(id, model)
    }

    /**
     * Runs a failed step again. The Track goes back to where that step starts, and [run] queues it
     * like a new one: Added for a download (continuing after the bytes it has), Downloaded for a
     * Separation (its Original Audio is still stored).
     */
    suspend fun retry(id: TrackId) {
        val state = tracks.get(id)?.pipeline as? TrackPipelineState.Failed ?: return
        val restart = when (state.step) {
            PipelineStep.Downloading -> TrackPipelineState.Added
            PipelineStep.Separating -> TrackPipelineState.Downloaded
        }
        tracks.updatePipeline(id, restart)
    }
}
