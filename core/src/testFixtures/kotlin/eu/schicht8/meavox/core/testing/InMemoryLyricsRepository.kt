// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.testing

import eu.schicht8.meavox.core.lyrics.LyricsRepository
import eu.schicht8.meavox.core.lyrics.TrackLyrics
import eu.schicht8.meavox.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield

class InMemoryLyricsRepository : LyricsRepository {
    private val all = MutableStateFlow<Map<TrackId, TrackLyrics>>(emptyMap())

    override fun observe(track: TrackId): Flow<TrackLyrics?> = all.map { it[track] }

    override fun observeAll(): Flow<Map<TrackId, TrackLyrics>> = all

    override suspend fun get(track: TrackId): TrackLyrics? = all.value[track]

    override suspend fun set(track: TrackId, lyrics: TrackLyrics) {
        all.update { it + (track to lyrics) }
    }

    private val offsets = MutableStateFlow<Map<TrackId, Long>>(emptyMap())

    override fun observeOffset(track: TrackId): Flow<Long> = offsets.map { it[track] ?: 0L }

    override suspend fun setOffset(track: TrackId, offsetMs: Long) {
        yield() // a real write suspends (Room), so other callers can run in between
        offsets.update { it + (track to offsetMs) }
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
