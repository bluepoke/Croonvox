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
import app.croonvox.core.pipeline.TrackPipeline
import app.croonvox.core.pipeline.VocalFreeToggle
import app.croonvox.core.player.OpenTrack
import app.croonvox.core.player.TrackPlayer
import app.croonvox.core.port.Playback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrackDetailViewModel(
    private val id: TrackId,
    tracks: TrackRepository,
    private val player: TrackPlayer,
    private val pipeline: TrackPipeline,
    lyrics: LyricsRepository,
    private val match: MatchLyrics,
    private val vocalFree: VocalFreeToggle,
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
        // A Track still downloading opens once its Original Audio is there.
        viewModelScope.launch {
            track.first { it?.hasOriginalAudio == true }
            open()
        }
        // A Session opened from here releases the Audio Engine only after this screen resumes.
        viewModelScope.launch { playback.collect { if (resumed) reopenIfReleased() } }
        // Separation finished, or Vocal-Free changed, while this screen is open: switch audio (ADR 10, ADR 14).
        viewModelScope.launch { track.collect { if (resumed) followTrackAudio() } }
    }

    fun onResume() {
        resumed = true
        reopenIfReleased()
        followTrackAudio()
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
     * Loads the audio the Track now calls for (the Instrumental once Ready, the Original Audio when
     * marked Vocal-Free), at the same position and still playing if it was: the stems are
     * sample-aligned with the Original Audio.
     */
    private fun followTrackAudio() {
        val current = opened ?: return
        if (opening?.isActive == true) return
        val now = track.value ?: return
        if (player.audioOf(now) == player.audioOf(current.track)) return
        val state = playback.value
        _ready.value = false
        _loadProgress.value = 0f
        open(atMs = state.positionMs, play = state.playing)
    }

    private val _confirmVocalFree = MutableStateFlow<Long?>(null)

    /** Bytes of stems that marking the Track Vocal-Free would delete, while waiting for the user to confirm. */
    val confirmVocalFree: StateFlow<Long?> = _confirmVocalFree

    /** The Vocal-Free toggle (ADR 14): turning it on asks first if stems would be deleted. */
    fun setVocalFree(on: Boolean) {
        viewModelScope.launch {
            if (!on) return@launch vocalFree.unmark(id)
            val bytes = vocalFree.stemBytes(id)
            if (bytes > 0) _confirmVocalFree.value = bytes else vocalFree.mark(id)
        }
    }

    fun confirmVocalFree(confirmed: Boolean) {
        _confirmVocalFree.value = null
        if (confirmed) viewModelScope.launch { vocalFree.mark(id) }
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
