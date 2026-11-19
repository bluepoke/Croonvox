// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.library

import app.croonvox.core.lyrics.LyricsRepository
import app.croonvox.core.lyrics.TrackLyrics
import app.croonvox.core.model.TrackId

/** The user renames a Track: its title and artist. Its identity (ADR 10) and files stay as they are. */
class RenameTrack(
    private val tracks: TrackRepository,
    private val lyrics: LyricsRepository,
) {
    /** @throws IllegalArgumentException if [title] is blank. A blank [artist] clears it. */
    suspend operator fun invoke(id: TrackId, title: String, artist: String?) {
        require(title.isNotBlank()) { "a Track's title can't be blank" }
        val newArtist = artist?.trim()?.ifEmpty { null }
        tracks.update(id) { it.copy(title = title.trim(), artist = newArtist) } ?: return
        // A fixed title is often what makes the Lyrics findable: search again unless there are some (ADR 6).
        when (lyrics.get(id)) {
            is TrackLyrics.NotFound, is TrackLyrics.Failed -> lyrics.set(id, TrackLyrics.Searching)
            else -> Unit
        }
    }
}
