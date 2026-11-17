// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.search

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Streams a search result's audio for a quick listen before it is added. Plain `MediaPlayer`, apart
 * from the Audio Engine, which only plays stored files.
 */
class PreviewPlayer {
    enum class State { Idle, Loading, Playing, Paused, Failed }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state
    private var player: MediaPlayer? = null

    fun play(uri: String) {
        stop()
        _state.value = State.Loading
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setOnPreparedListener {
                it.start()
                _state.value = State.Playing
            }
            setOnCompletionListener { _state.value = State.Paused }
            setOnErrorListener { _, _, _ ->
                _state.value = State.Failed
                true
            }
            try {
                setDataSource(uri)
                prepareAsync()
            } catch (e: Exception) {
                _state.value = State.Failed
            }
        }
    }

    fun toggle() {
        val p = player ?: return
        when (_state.value) {
            State.Playing -> {
                p.pause()
                _state.value = State.Paused
            }
            State.Paused -> {
                p.start()
                _state.value = State.Playing
            }
            else -> Unit
        }
    }

    fun pause() {
        if (_state.value == State.Playing) toggle()
    }

    fun stop() {
        player?.release()
        player = null
        _state.value = State.Idle
    }
}
