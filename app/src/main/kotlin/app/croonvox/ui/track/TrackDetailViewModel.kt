// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.track

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.lyrics.LyricsRepository
import app.croonvox.core.lyrics.MatchLyrics
import app.croonvox.core.lyrics.TrackLyrics
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.pipeline.TrackPipeline
import app.croonvox.core.player.OpenTrack
import app.croonvox.core.player.TrackPlayer
import app.croonvox.core.port.Playback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrackDetailViewModel(
    private val id: TrackId,
    tracks: TrackRepository,
    private val player: TrackPlayer,
    private val pipeline: TrackPipeline,
    lyrics: LyricsRepository,
    private val match: MatchLyrics,
) : ViewModel() {
    val lyrics: StateFlow<TrackLyrics?> = lyrics.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Confirms the suggested Lyrics (ADR 6). */
    fun confirmLyrics() {
        viewModelScope.launch { match.confirm(id) }
    }

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
    private var opening: Job? = null

    private var resumed = false

    init {
        open()
        // A Session opened from here releases the Audio Engine only after this screen resumes.
        viewModelScope.launch { playback.collect { if (resumed) reopenIfReleased() } }
        // Separation finished while this screen is open: switch to the Instrumental (ADR 10).
        viewModelScope.launch { track.collect { if (resumed) switchIfReady() } }
    }

    fun onResume() {
        resumed = true
        reopenIfReleased()
        switchIfReady()
    }

    fun onPause() {
        resumed = false
    }

    private fun open(atMs: Long = 0, play: Boolean = false) {
        opening = viewModelScope.launch {
            try {
                val track = player.open(id) { _loadProgress.value = it }
                opened = track
                if (atMs > 0) track.seekTo(atMs)
                if (play) track.play()
                _ready.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: e::class.simpleName
            }
        }
    }

    /** Opens the audio again if something else (a Session) released it meanwhile. */
    private fun reopenIfReleased() {
        if (opening?.isActive == true || opened == null || playback.value.loaded != null) return
        _ready.value = false
        _loadProgress.value = 0f
        open()
    }

    /**
     * Loads the Instrumental in place of the Original Audio once the Track is Ready, at the same
     * position and still playing if it was: the stems are sample-aligned with the Original Audio.
     */
    private fun switchIfReady() {
        val current = opened ?: return
        if (opening?.isActive == true) return
        if (current.track.pipeline is TrackPipelineState.Ready || track.value?.pipeline !is TrackPipelineState.Ready) return
        val state = playback.value
        _ready.value = false
        _loadProgress.value = 0f
        open(atMs = state.positionMs, play = state.playing)
    }

    /** Runs a failed Separation again. */
    fun retry() {
        viewModelScope.launch { pipeline.retry(id) }
    }

    fun togglePlay() {
        val track = opened ?: return
        if (playback.value.playing) track.pause() else track.play()
    }

    fun pause() {
        opened?.pause()
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
