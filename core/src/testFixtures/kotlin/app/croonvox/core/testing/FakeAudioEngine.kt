// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.model.AudioFile
import app.croonvox.core.port.AudioEngine
import app.croonvox.core.port.LoadId
import app.croonvox.core.port.Playback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** State-only [AudioEngine]: tracks loaded file, play/pause and position. Durations are given per file. */
class FakeAudioEngine(private val durationsMs: Map<AudioFile, Long> = emptyMap()) : AudioEngine {
    private val state = MutableStateFlow(Playback.Idle)
    override val playback: StateFlow<Playback> = state

    private var current = LoadId(0)

    override suspend fun load(audio: AudioFile, onProgress: (Float) -> Unit): LoadId {
        listOf(0f, 0.5f, 1f).forEach(onProgress)
        state.value = Playback(audio, playing = false, positionMs = 0, durationMs = durationsMs[audio] ?: 0)
        current = LoadId(current.value + 1)
        return current
    }

    override fun unload(load: LoadId) {
        if (load == current) state.value = Playback.Idle
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
