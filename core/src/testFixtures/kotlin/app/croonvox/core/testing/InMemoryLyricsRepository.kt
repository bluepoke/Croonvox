// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.lyrics.LyricsRepository
import app.croonvox.core.lyrics.TrackLyrics
import app.croonvox.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryLyricsRepository : LyricsRepository {
    private val all = MutableStateFlow<Map<TrackId, TrackLyrics>>(emptyMap())

    override fun observe(track: TrackId): Flow<TrackLyrics?> = all.map { it[track] }

    override fun observeAll(): Flow<Map<TrackId, TrackLyrics>> = all

    override suspend fun get(track: TrackId): TrackLyrics? = all.value[track]

    override suspend fun set(track: TrackId, lyrics: TrackLyrics) {
        all.update { it + (track to lyrics) }
    }

    override suspend fun addIfAbsent(track: TrackId, lyrics: TrackLyrics): Boolean {
        var added = false
        all.update { current ->
            added = track !in current
            if (added) current + (track to lyrics) else current
        }
        return added
    }
}
