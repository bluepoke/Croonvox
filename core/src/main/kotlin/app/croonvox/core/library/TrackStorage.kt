// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.library

import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.Take
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.pipeline.PipelineJobs
import app.croonvox.core.session.TakeRepository
import kotlinx.coroutines.flow.first

/**
 * The storage screen (ADR 8): what each Track takes up, and deleting stems, Takes or whole Tracks.
 * [models] are the Separation Models whose stems a Track may hold.
 */
class TrackStorage(
    private val tracks: TrackRepository,
    private val takes: TakeRepository,
    private val files: TrackFiles,
    private val jobs: PipelineJobs,
    private val models: List<SeparationModelId>,
) {
    /** Every Track's usage, largest first. */
    suspend fun usage(): List<TrackUsage> = tracks.observeAll().first().map { usage(it) }.sortedByDescending { it.totalBytes }

    /**
     * Deletes the Track's stems of [model]. If the Track is Ready with them, it becomes
     * [TrackPipelineState.StemsDeleted] and they are made again when the Track is next opened.
     * @throws IllegalStateException while Separation with [model] is running for the Track.
     */
    suspend fun deleteStems(id: TrackId, model: SeparationModelId) {
        val track = checkNotNull(tracks.get(id)) { "no Track $id" }
        when (track.pipeline) {
            is TrackPipelineState.Separating -> check(track.pipeline.model != model) { "Separation with ${model.value} is running" }
            // State first, then the files: nothing tries to play stems that are gone.
            TrackPipelineState.Ready(model) -> tracks.updatePipeline(id, TrackPipelineState.StemsDeleted(model))
            else -> Unit
        }
        files.deleteStems(id, model)
    }

    /** Deletes a kept Take and its audio. */
    suspend fun deleteTake(take: Take) {
        // Row first, then the file: the list never shows a Take whose audio is gone.
        takes.delete(take.id)
        files.deleteTake(take.track, take.id)
    }

    /**
     * Deletes the Track and everything it owns: its jobs stop, then its Takes, Lyrics and files go.
     * The UI asks for confirmation first.
     */
    suspend fun deleteTrack(id: TrackId) {
        // Jobs first, so they stop writing; rows before files, so nothing lists a Track whose
        // audio is gone. A job that still writes a moment later is cleared by removeOrphanedFiles().
        jobs.cancelAll(id)
        takes.deleteAll(id)
        tracks.delete(id)
        files.deleteAll(id)
    }

    /**
     * Removes files written by a job that hadn't stopped yet: those of Tracks no longer in the
     * Library (deleted), and stems of Vocal-Free Tracks (marked during Separation). Safe at any
     * time: a Track's row always exists before its files (imports and downloads add the Track first).
     */
    suspend fun removeOrphanedFiles() {
        val all = tracks.observeAll().first()
        files.deleteAllExcept(all.mapTo(HashSet()) { it.id })
        for (track in all.filter { it.vocalFree }) models.forEach { files.deleteStems(track.id, it) }
    }

    private suspend fun usage(track: Track): TrackUsage {
        // A Vocal-Free Track has no stems to show or regenerate (ADR 14); leftovers go at start.
        val stems = if (track.vocalFree) {
            emptyMap()
        } else {
            models.associateWith { files.sizeOfStems(track.id, it) + files.sizeOf(files.separationCheckpoint(track.id, it)) }
                .filterValues { it > 0 }
        }
        return TrackUsage(
            track = track,
            originalAudioBytes = files.sizeOf(files.originalAudio(track.id)),
            stemBytes = stems,
            takes = takes.observe(track.id).first().map { TakeUsage(it, files.sizeOf(files.take(track.id, it.id))) },
        )
    }
}

/** What one Track takes up, by category. */
data class TrackUsage(
    val track: Track,
    val originalAudioBytes: Long,
    /** Instrumental and Vocal Stem (and any unfinished Separation) per Separation Model that has some. */
    val stemBytes: Map<SeparationModelId, Long>,
    /** The Track's kept Takes, oldest first. */
    val takes: List<TakeUsage>,
) {
    val totalBytes: Long get() = originalAudioBytes + stemBytes.values.sum() + takes.sumOf { it.bytes }
}

data class TakeUsage(val take: Take, val bytes: Long)
