// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.pipeline

import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.PipelineStep
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState

/**
 * Moves Tracks through the Track Pipeline (ADR 10): every Track that reaches Downloaded is
 * queued for Separation with [defaultModel].
 */
class TrackPipeline(
    private val tracks: TrackRepository,
    private val jobs: PipelineJobs,
    private val defaultModel: SeparationModelId,
) {
    /** Follows the Library until cancelled; runs for the lifetime of the app process. */
    suspend fun run() {
        // Tracks this run has queued a job for. Each Track is queued once per run: progress
        // updates change the Track many times while its job runs.
        val queued = mutableSetOf<TrackId>()
        tracks.observeAll().collect { all ->
            for (track in all) {
                when (val state = track.pipeline) {
                    TrackPipelineState.Downloaded -> {
                        tracks.updatePipeline(track.id, TrackPipelineState.Separating(defaultModel))
                        queued += track.id
                        jobs.separate(track.id, defaultModel)
                    }
                    // Left Separating by an earlier process (or queued above): make sure its job exists.
                    is TrackPipelineState.Separating -> if (queued.add(track.id)) jobs.separate(track.id, state.model)
                    else -> queued -= track.id
                }
            }
        }
    }

    /**
     * Runs a failed Separation again: the Track goes back to Downloaded (its Original Audio is
     * still stored), and [run] queues it like a newly downloaded Track.
     */
    suspend fun retry(id: TrackId) {
        val state = tracks.get(id)?.pipeline
        if (state is TrackPipelineState.Failed && state.step == PipelineStep.Separating) {
            tracks.updatePipeline(id, TrackPipelineState.Downloaded)
        }
    }
}
