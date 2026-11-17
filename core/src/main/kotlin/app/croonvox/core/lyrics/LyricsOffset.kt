// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.lyrics

import app.croonvox.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Lyrics Offset (GLOSSARY): a per-Track shift between the Lyrics' studio timing and the Track's
 * audio, nudged during a Session (ADR 6). Positive values show the Lyrics later. It belongs to the
 * Track, so it stays when other Lyrics are chosen.
 */
class LyricsOffset(private val lyrics: LyricsRepository) {
    // One change at a time: quick taps each start their own coroutine, and every one must count.
    private val changes = Mutex()

    /** The Track's offset in ms, 0 until nudged, updated as it changes. */
    fun observe(track: TrackId): Flow<Long> = lyrics.observeOffset(track)

    /** Moves the Track's offset by [deltaMs]; positive shows the Lyrics later. */
    suspend fun nudge(track: TrackId, deltaMs: Long) = changes.withLock {
        lyrics.setOffset(track, lyrics.observeOffset(track).first() + deltaMs)
    }

    /** Sets the Track's offset back to 0. */
    suspend fun reset(track: TrackId) = changes.withLock {
        lyrics.setOffset(track, 0)
    }
}
