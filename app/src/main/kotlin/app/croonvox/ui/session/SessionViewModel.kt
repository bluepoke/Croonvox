// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.lyrics.LyricsOffset
import app.croonvox.core.lyrics.LyricsRepository
import app.croonvox.core.lyrics.MatchLyrics
import app.croonvox.core.lyrics.TrackLyrics
import app.croonvox.core.model.BackingChoice
import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.Transpose
import app.croonvox.core.pipeline.TrackPipeline
import app.croonvox.core.pipeline.VocalFreeToggle
import app.croonvox.core.port.InputLevel
import app.croonvox.core.port.Playback
import app.croonvox.core.session.BackingAudio
import app.croonvox.core.session.MicrophoneUnavailable
import app.croonvox.core.session.Session
import app.croonvox.core.session.SessionState
import app.croonvox.core.session.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Track screen: the Track's status, Lyrics and Vocal-Free toggle, and the [Session] on it,
 * which opens as soon as the Original Audio is stored (ADR 10) and stays open while the screen is
 * on the back stack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel(
    private val id: TrackId,
    tracks: TrackRepository,
    sessions: Sessions,
    private val pipeline: TrackPipeline,
    lyrics: LyricsRepository,
    private val match: MatchLyrics,
    private val lyricsOffset: LyricsOffset,
    private val vocalFree: VocalFreeToggle,
) : ViewModel() {
    /** The Track as it is now: Track Pipeline, Vocal-Free, Backing Audio choice. */
    val track: StateFlow<Track?> = tracks.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The Track's Lyrics; they may still change (e.g. a search finishing). */
    val lyrics: StateFlow<TrackLyrics?> = lyrics.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The Track's Lyrics Offset in ms (ADR 6). */
    val lyricsOffsetMs: StateFlow<Long> = lyricsOffset.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _karaoke = MutableStateFlow(true)
    /** Karaoke View (if the Lyrics are synced) rather than Page View; for this screen only. */
    val karaoke: StateFlow<Boolean> = _karaoke

    fun showKaraoke(karaoke: Boolean) {
        _karaoke.value = karaoke
    }

    fun nudgeLyricsOffset(deltaMs: Long) {
        viewModelScope.launch { lyricsOffset.nudge(id, deltaMs) }
    }

    fun resetLyricsOffset() {
        viewModelScope.launch { lyricsOffset.reset(id) }
    }

    /** Confirms the suggested Lyrics (ADR 6). */
    fun confirmLyrics() {
        viewModelScope.launch { match.confirm(id) }
    }

    /** Runs a failed step of the Track Pipeline again. */
    fun retry() {
        viewModelScope.launch { pipeline.retry(id) }
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

    private val session = MutableStateFlow<Session?>(null)

    private val _loadProgress = MutableStateFlow(0f)
    /** How much of the Backing Audio is prepared, 0..1, while the Session opens. */
    val loadProgress: StateFlow<Float> = _loadProgress

    private val _openError = MutableStateFlow<String?>(null)
    /** Why the Session couldn't open, if it couldn't. */
    val openError: StateFlow<String?> = _openError

    private val _message = MutableStateFlow<String?>(null)
    /** A one-off message for the user (shown once, then [messageShown]). */
    val message: StateFlow<String?> = _message

    val state: StateFlow<SessionState> = from(SessionState.Idle) { it.state }
    val playback: StateFlow<Playback> = from(Playback.Idle) { it.playback }
    val input: StateFlow<InputLevel> = from(InputLevel.Off) { it.input }
    val inputGainDb: StateFlow<Float> = from(0f) { it.inputGainDb }

    /** What plays now; null until the Session is open. */
    val backing: StateFlow<BackingAudio?> = from(null) { it.backing }
    val countdownEnabled: StateFlow<Boolean> = from(false) { it.countdownEnabled }
    val reviewBackingDb: StateFlow<Float> = from(Session.DEFAULT_REVIEW_BACKING_DB) { it.reviewBackingDb }
    val takes: StateFlow<List<Take>> = from(emptyList()) { it.takes }
    val transpose: StateFlow<Transpose> = from(Transpose.None) { it.transpose }

    init {
        viewModelScope.launch {
            // A Track still downloading opens once its Original Audio is stored (ADR 10).
            track.first { it?.hasOriginalAudio == true }
            try {
                session.value = sessions.open(id, viewModelScope) { _loadProgress.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _openError.value = e.message ?: e::class.simpleName
            }
        }
    }

    private fun <T> from(initial: T, select: (Session) -> Flow<T>): StateFlow<T> =
        session.filterNotNull().flatMapLatest(select).stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    /** The Backing Audio choice (Instrumental / Original Audio), kept for the Track. */
    fun chooseBacking(choice: BackingChoice) = act { it.chooseBacking(choice) }

    /** The microphone permission is granted: open the microphone for the meter. */
    fun onMicrophoneAllowed() = act { it.enableMicrophone() }

    fun onMicrophoneDenied() {
        _message.value = "Croonvox needs access to the microphone to record a Take."
    }

    fun messageShown() {
        _message.value = null
    }

    fun togglePlay() = act { if (playback.value.playing) it.pause() else it.play() }

    fun seekTo(positionMs: Long) = act { it.seekTo(positionMs) }

    fun record() = act { it.record() }

    fun stop() = act { it.stop() }

    fun replay() = act { it.replay() }

    fun pause() = act { it.pause() }

    fun keep() = act { it.keep() }

    fun discard() = act { it.discard() }

    fun recordAgain() = act { it.recordAgain() }

    fun playTake(take: TakeId) = act {
        if (!it.play(take)) _message.value = "This Take can't be played."
    }

    fun rename(take: TakeId, name: String) = act { it.rename(take, name) }

    fun delete(take: TakeId) = act { it.delete(take) }

    fun setCountdownEnabled(enabled: Boolean) = act { it.setCountdownEnabled(enabled) }

    fun setInputGain(gainDb: Float) = act { it.setInputGain(gainDb) }

    fun setReviewBackingDb(gainDb: Float) = act { it.setReviewBackingDb(gainDb) }

    fun setTranspose(transpose: Transpose) = act { it.setTranspose(transpose) }

    private fun act(block: suspend (Session) -> Unit) {
        val open = session.value ?: return
        viewModelScope.launch {
            try {
                block(open)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MicrophoneUnavailable) {
                _message.value = "The microphone can't be opened. Check that Croonvox may use it."
            } catch (e: Exception) {
                _message.value = e.message ?: e::class.simpleName
            }
        }
    }

    override fun onCleared() {
        // Does nothing if the next Track's Session has closed it already.
        session.value?.close()
    }
}
