// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.track

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.player.OpenTrack
import app.croonvox.core.player.TrackPlayer
import app.croonvox.core.port.Playback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrackDetailViewModel(
    private val id: TrackId,
    tracks: TrackRepository,
    private val player: TrackPlayer,
) : ViewModel() {
    val track: StateFlow<Track?> = tracks.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val playback: StateFlow<Playback> = player.playback

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _loadProgress = MutableStateFlow(0f)
    /** How much of the Original Audio is prepared, 0..1, until [ready]. */
    val loadProgress: StateFlow<Float> = _loadProgress

    private val _ready = MutableStateFlow(false)
    /** The Original Audio of this Track is loaded and can be played. */
    val ready: StateFlow<Boolean> = _ready

    private var opened: OpenTrack? = null

    init {
        viewModelScope.launch {
            try {
                opened = player.open(id) { _loadProgress.value = it }
                _ready.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: e::class.simpleName
            }
        }
    }

    fun togglePlay() {
        val track = opened ?: return
        if (playback.value.playing) track.pause() else track.play()
    }

    fun seekTo(positionMs: Long) {
        opened?.seekTo(positionMs)
    }

    override fun onCleared() {
        // Stops playback, cancels background decoding and frees the audio — only if no other
        // Track was opened since (this screen may be torn down after the next one started).
        opened?.close()
    }
}
