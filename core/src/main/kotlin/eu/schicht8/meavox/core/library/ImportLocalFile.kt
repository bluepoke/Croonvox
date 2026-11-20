// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.library

import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.port.LocalFile

/**
 * Imports a picked or shared file as a Track of the Local File Source: the content is copied
 * into app storage as Original Audio, and the Track moves Added → Downloaded (ADR 8, ADR 10).
 */
class ImportLocalFile(private val tracks: TrackRepository, private val files: TrackFiles) {
    /**
     * Imports [file], marked [vocalFree] if asked, to be separated with [separationModel] (ADR 19);
     * content imported before keeps its Track unchanged (ADR 14).
     */
    suspend operator fun invoke(file: LocalFile, vocalFree: Boolean = false, separationModel: SeparationModelId? = null): Track {
        val staged = file.open().use { files.stage(it) }
        val id = TrackId(SourceId.Local, staged.sha256)
        val track = tracks.addIfAbsent(
            Track(
                id = id,
                title = titleFrom(file.displayName),
                artist = null,
                durationMs = null,
                pipeline = TrackPipelineState.Added,
                vocalFree = vocalFree,
                separationModel = separationModel,
            ),
        )
        // Same content imported before: keep that Track and its stored file untouched. Only a
        // Track still in Added (a previous import that never finished) gets its file now.
        if (track.pipeline != TrackPipelineState.Added) {
            files.discard(staged)
            return track
        }
        files.commitOriginalAudio(staged, id)
        return tracks.updatePipeline(id, TrackPipelineState.Downloaded)
    }

    private fun titleFrom(displayName: String?): String =
        displayName?.substringBeforeLast('.')?.trim()?.ifEmpty { null } ?: "Untitled"
}
