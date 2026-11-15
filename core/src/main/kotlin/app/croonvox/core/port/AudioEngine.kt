// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.port

import app.croonvox.core.model.AudioFile
import kotlinx.coroutines.flow.StateFlow

/**
 * The native Audio Engine as the domain sees it (ADR 4, ADR 13). Starts with playback of one
 * Backing Audio file; recording, Transpose and Monitoring are added by later slices.
 */
interface AudioEngine {
    val playback: StateFlow<Playback>

    /**
     * Prepares [audio] for playback, paused at the start. Returns as soon as playback can start;
     * the rest may still be prepared in the background (see [Playback.bufferedMs]).
     * [onProgress] receives 0..1 until the whole file is prepared; it may be called on any thread.
     */
    suspend fun load(audio: AudioFile, onProgress: (Float) -> Unit = {}): LoadId

    /**
     * Stops playback, cancels background preparation and releases the audio of [load] — unless a
     * newer [load] call has replaced it, in which case nothing happens.
     */
    fun unload(load: LoadId)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)
}

/** Identifies one [AudioEngine.load] call, so an old owner can't unload a newer load. */
@JvmInline
value class LoadId(val value: Long)

data class Playback(
    val loaded: AudioFile?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** How far from the start the audio is ready; equals [durationMs] once fully prepared. */
    val bufferedMs: Long = durationMs,
) {
    companion object {
        val Idle = Playback(loaded = null, playing = false, positionMs = 0, durationMs = 0)
    }
}
