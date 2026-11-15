// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.library

import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.TrackId

/** Where a Track's audio files live in app-private storage (ADR 8). */
interface TrackFiles {
    /** Location for the Track's Original Audio, kept exactly as downloaded or imported. */
    fun originalAudio(track: TrackId): AudioFile

    /** Removes every file the Track owns. */
    suspend fun deleteAll(track: TrackId)
}
