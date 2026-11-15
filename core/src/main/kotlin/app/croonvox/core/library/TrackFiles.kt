// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.library

import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.TrackId
import java.io.InputStream

/** Where a Track's audio files live in app-private storage (ADR 8). */
interface TrackFiles {
    /** Location for the Track's Original Audio, kept exactly as downloaded or imported. */
    fun originalAudio(track: TrackId): AudioFile

    /**
     * Copies [content] into a staging file and hashes it (SHA-256) on the way, because a Local
     * File's identity is only known once its content has been read (ADR 10).
     */
    suspend fun stage(content: InputStream): StagedFile

    /** Moves a staged file into place as the Original Audio of [track]. */
    suspend fun commitOriginalAudio(staged: StagedFile, track: TrackId)

    /** Deletes a staged file that is not needed (e.g. the content was already imported). */
    suspend fun discard(staged: StagedFile)

    /** Deletes staged files left behind by imports that never finished (e.g. process death). */
    suspend fun discardAbandonedStaging()

    /** Removes every file the Track owns. */
    suspend fun deleteAll(track: TrackId)
}

/** A copied file awaiting commit, with the lowercase hex SHA-256 of its content. */
data class StagedFile(val file: AudioFile, val sha256: String)
