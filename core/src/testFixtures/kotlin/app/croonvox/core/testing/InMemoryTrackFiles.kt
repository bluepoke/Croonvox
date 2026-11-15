// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.library.TrackFiles
import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.TrackId

/** [TrackFiles] with synthetic paths; remembers which Tracks were deleted. */
class InMemoryTrackFiles : TrackFiles {
    val deleted = mutableListOf<TrackId>()

    override fun originalAudio(track: TrackId) =
        AudioFile("/mem/${track.key}/original")

    override suspend fun deleteAll(track: TrackId) {
        deleted += track
    }
}
