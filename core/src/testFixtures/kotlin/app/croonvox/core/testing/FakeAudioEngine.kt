// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.model.AudioFile
import app.croonvox.core.port.AudioEngine
import app.croonvox.core.port.Playback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** State-only [AudioEngine]: tracks loaded file, play/pause and position. Durations are given per file. */
class FakeAudioEngine(private val durationsMs: Map<AudioFile, Long> = emptyMap()) : AudioEngine {
    private val state = MutableStateFlow(Playback.Idle)
    override val playback: StateFlow<Playback> = state

    override suspend fun load(backingAudio: AudioFile) {
        state.value = Playback(backingAudio, playing = false, positionMs = 0, durationMs = durationsMs[backingAudio] ?: 0)
    }

    override fun play() {
        state.update { check(it.loaded != null) { "nothing loaded" }; it.copy(playing = true) }
    }

    override fun pause() {
        state.update { it.copy(playing = false) }
    }

    override fun seekTo(positionMs: Long) {
        state.update { it.copy(positionMs = positionMs.coerceIn(0, it.durationMs)) }
    }

    /** Simulates playback time passing. */
    fun advance(ms: Long) {
        state.update { if (it.playing) it.copy(positionMs = (it.positionMs + ms).coerceAtMost(it.durationMs)) else it }
    }
}
