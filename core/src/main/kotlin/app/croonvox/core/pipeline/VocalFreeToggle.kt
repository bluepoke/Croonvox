// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.pipeline

import app.croonvox.core.library.TrackFiles
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.library.sizeOfStems
import app.croonvox.core.model.PipelineStep
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState

/**
 * The Vocal-Free toggle on Track detail (GLOSSARY: Vocal-Free, ADR 14). Marking skips or undoes
 * Separation; unmarking separates the Track with [defaultModel] like a newly downloaded one.
 * [models] are the Separation Models whose stems a Track may hold.
 */
class VocalFreeToggle(
    private val tracks: TrackRepository,
    private val files: TrackFiles,
    private val jobs: PipelineJobs,
    private val defaultModel: SeparationModelId,
    private val models: List<SeparationModelId>,
) {
    /** Bytes that marking the Track would free: its stems. The UI asks for confirmation when this isn't 0. */
    suspend fun stemBytes(id: TrackId): Long {
        val model = (tracks.get(id)?.pipeline as? TrackPipelineState.Ready)?.model ?: return 0
        return files.sizeOfStems(id, model)
    }

    /**
     * Marks the Track Vocal-Free. Separation is cancelled or undone: a Track that is Separating,
     * whose Separation failed, or that has (or had) stems becomes Ready without stems, and what
     * Separation left is deleted. A Track still to be downloaded skips Separation once it is
     * (see [TrackPipeline]).
     */
    suspend fun mark(id: TrackId) {
        var before: TrackPipelineState? = null
        // One atomic change, so a Track Pipeline step can't slip in between the flag and the state.
        tracks.update(id) { track ->
            before = track.pipeline
            when {
                track.vocalFree -> null
                track.pipeline.separationStarted() -> track.copy(vocalFree = true, pipeline = TrackPipelineState.Ready(model = null))
                else -> track.copy(vocalFree = true)
            }
        } ?: return
        // State first, then jobs and files: a late Separation can no longer write over it (SeparateTrack).
        if (before is TrackPipelineState.Separating) jobs.cancelSeparation(id)
        // Unmarked again while waiting for the job to stop: the new Separation's files stay.
        if (tracks.get(id)?.vocalFree != true) return
        models.forEach { files.deleteStems(id, it) }
    }

    /** Unmarks the Track: a Ready one goes back through Separating with [defaultModel] (ADR 14). */
    suspend fun unmark(id: TrackId) {
        val track = tracks.update(id) { track ->
            when {
                !track.vocalFree -> null
                track.pipeline == TrackPipelineState.Ready(model = null) ->
                    track.copy(vocalFree = false, pipeline = TrackPipelineState.Separating(defaultModel))
                else -> track.copy(vocalFree = false)
            }
        }
        // Queued here, not left to the Track Pipeline: it may never see the short Ready in between.
        if (track?.pipeline == TrackPipelineState.Separating(defaultModel)) jobs.separate(id, defaultModel)
    }

    /** Separation is running, failed or done: marking makes the Track Ready at once. */
    private fun TrackPipelineState.separationStarted(): Boolean = when (this) {
        is TrackPipelineState.Separating, is TrackPipelineState.Ready, is TrackPipelineState.StemsDeleted -> true
        is TrackPipelineState.Failed -> step == PipelineStep.Separating
        TrackPipelineState.Added, is TrackPipelineState.Downloading, TrackPipelineState.Downloaded -> false
    }
}
