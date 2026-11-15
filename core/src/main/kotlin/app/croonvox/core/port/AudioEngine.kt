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

    suspend fun load(backingAudio: AudioFile)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)
}

data class Playback(
    val loaded: AudioFile?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
) {
    companion object {
        val Idle = Playback(loaded = null, playing = false, positionMs = 0, durationMs = 0)
    }
}
