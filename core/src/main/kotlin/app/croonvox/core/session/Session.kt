// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.session

import app.croonvox.core.library.TrackFiles
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.model.Transpose
import app.croonvox.core.port.AudioEngine
import app.croonvox.core.port.CapturedTake
import app.croonvox.core.port.InputLevel
import app.croonvox.core.port.LoadId
import app.croonvox.core.port.Playback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Singing over one Track (GLOSSARY: Session, ADR 9): playback of the Backing Audio, recording
 * Takes from any Start Position, Review with Keep / Discard / Record again, and the Track's
 * kept Takes. Create with [Sessions.open]; call [close] when leaving.
 */
class Session private constructor(
    val track: Track,
    private val scope: CoroutineScope,
    private val deps: Dependencies,
) {
    internal class Dependencies(
        val tracks: TrackRepository,
        val takes: TakeRepository,
        val files: TrackFiles,
        val engine: AudioEngine,
        val settings: SettingsRepository,
        val clock: () -> Long,
        val newTakeId: () -> TakeId,
    )

    private val engine get() = deps.engine

    // Serialises commands and reactions to the engine, so state changes never interleave.
    private val mutex = Mutex()

    // Reactions to the engine and the Track Pipeline; cancelled by close().
    private val observers = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state

    private val _backing = MutableStateFlow<BackingAudio>(BackingAudio.OriginalAudio)

    /** What plays under the singer: the Instrumental once Separation finished, else the Original Audio. */
    val backing: StateFlow<BackingAudio> = _backing

    /** Backing Audio that became available while the Session was busy; loaded once it is [SessionState.Idle]. */
    private var pendingBacking: BackingAudio? = null

    private var load: LoadId? = null

    val playback: StateFlow<Playback> get() = engine.playback

    /** The microphone meter: level, clipping and which microphone is in use. */
    val input: StateFlow<InputLevel> get() = engine.input

    private val _inputGainDb = MutableStateFlow(0f)

    /** Input gain of the microphone in use, in dB (remembered per kind of microphone). */
    val inputGainDb: StateFlow<Float> = _inputGainDb

    private val _countdownEnabled = MutableStateFlow(false)

    /** Whether recording starts after a [COUNTDOWN_SECONDS] countdown (ADR 9). */
    val countdownEnabled: StateFlow<Boolean> = _countdownEnabled

    /** The Track's kept Takes, oldest first. */
    val takes: Flow<List<Take>> get() = deps.takes.observe(track.id)

    // --- Playback ---

    /** Plays the Backing Audio from the current position (only while [SessionState.Idle]). */
    fun play() {
        if (_state.value == SessionState.Idle) engine.play()
    }

    /** Pauses free playback, or the playback of the Take in Review. */
    fun pause() {
        val state = _state.value
        if (state == SessionState.Idle || state is SessionState.Review) engine.pause()
    }

    /** Moves the position where playback and the next Take start. */
    fun seekTo(positionMs: Long) {
        if (_state.value == SessionState.Idle) engine.seekTo(positionMs)
    }

    // --- Transpose (ADR 5) ---

    private val _transpose = MutableStateFlow(Transpose.None)

    /** The Transpose control: what free playback plays at and the next Take is recorded at. */
    val transpose: StateFlow<Transpose> = _transpose

    /**
     * Sets the Transpose control; free playback follows at once. A Take being recorded or played
     * back keeps its own Transpose, and existing Takes never change (ADR 5).
     */
    fun setTranspose(transpose: Transpose) {
        _transpose.value = transpose
        engine.setTranspose(transpose)
    }

    // --- Microphone ---

    /**
     * Opens the microphone for the meter. Needs the platform's microphone permission, which the
     * UI asks for only when the user first records. Returns false if it can't be opened.
     */
    suspend fun enableMicrophone(): Boolean = engine.input.value.enabled || engine.enableMicrophone()

    /** Sets the gain of the microphone in use and remembers it for that kind of microphone. */
    suspend fun setInputGain(gainDb: Float) {
        val clamped = gainDb.coerceIn(MIN_INPUT_GAIN_DB, MAX_INPUT_GAIN_DB)
        _inputGainDb.value = clamped
        engine.setInputGain(clamped)
        engine.input.value.device?.let { deps.settings.setInputGainDb(it, clamped) }
    }

    suspend fun setCountdownEnabled(enabled: Boolean) {
        _countdownEnabled.value = enabled
        deps.settings.setCountdownEnabled(enabled)
    }

    private val _reviewBackingDb = MutableStateFlow(DEFAULT_REVIEW_BACKING_DB)

    /**
     * Review balance: level of the Backing Audio, in dB, while a Take plays back (Review and kept
     * Takes). Lower than the Take by default, because an unprocessed voice is far quieter than a
     * mastered song. Only for listening; the Mix sets the real balance (ADR 7).
     */
    val reviewBackingDb: StateFlow<Float> = _reviewBackingDb

    suspend fun setReviewBackingDb(gainDb: Float) {
        val clamped = gainDb.coerceIn(MIN_REVIEW_BACKING_DB, 0f)
        _reviewBackingDb.value = clamped
        engine.setReviewBackingGain(clamped)
        deps.settings.setReviewBackingDb(clamped)
    }

    // --- Recording and Review ---

    /**
     * Records a Take from the current position, its Start Position, after the countdown if it
     * is on. Returns once recording has started, or once [stop] ended the countdown.
     * @throws MicrophoneUnavailable if the microphone can't be opened.
     */
    suspend fun record() {
        val countdown = mutex.withLock {
            check(_state.value == SessionState.Idle) { "can't record in ${_state.value}" }
            startRecordingOrCountdownLocked(engine.playback.value.positionMs)
        } ?: return
        runCountdown(countdown)
    }

    /** Ends the countdown, stops recording (and goes to Review) or stops playing a kept Take. */
    suspend fun stop() = mutex.withLock {
        when (_state.value) {
            is SessionState.CountingDown -> becomeIdleLocked()
            is SessionState.Recording -> finishRecordingLocked()
            is SessionState.PlayingTake -> {
                engine.clearTake()
                becomeIdleLocked()
            }
            else -> Unit
        }
    }

    /** Plays the Take in Review against the Backing Audio again, at its Transpose, from its Start Position. */
    suspend fun replay() = mutex.withLock {
        val review = _state.value as? SessionState.Review ?: return@withLock
        engine.playTake(review.take.reportedLatencyFrames, review.transpose)
    }

    /**
     * Keeps the Take in Review: its audio is written as mono FLAC, then it is stored and listed.
     * Returns the stored Take.
     */
    suspend fun keep(): Take = mutex.withLock {
        val review = _state.value as? SessionState.Review ?: error("no Take in Review")
        val captured = review.take
        val id = deps.newTakeId()
        // Audio first, then the row: a stored Take never points at a missing file.
        engine.writeTake(deps.files.take(track.id, id))
        val take = Take(
            id = id,
            track = track.id,
            name = nextTakeName(),
            startFrame = captured.startFrame,
            frames = captured.frames,
            sampleRate = captured.sampleRate,
            route = captured.route,
            reportedLatencyFrames = captured.reportedLatencyFrames,
            transpose = review.transpose,
            recordedAt = deps.clock(),
        )
        deps.takes.add(take)
        leaveReviewLocked(captured)
        take
    }

    /** Throws away the Take in Review. */
    suspend fun discard() = mutex.withLock {
        val review = _state.value as? SessionState.Review ?: return@withLock
        leaveReviewLocked(review.take)
    }

    /** Throws away the Take in Review and records a new one from the same Start Position. */
    suspend fun recordAgain() {
        val countdown = mutex.withLock {
            val review = _state.value as? SessionState.Review ?: error("no Take in Review")
            engine.clearTake()
            // Picks up an Instrumental that became ready during Review before the next Take.
            becomeIdleLocked(review.take.startPositionMs)
            startRecordingOrCountdownLocked(review.take.startPositionMs)
        } ?: return
        runCountdown(countdown)
    }

    // --- Kept Takes ---

    /**
     * Plays a kept Take against the Backing Audio at the Take's Transpose, from its Start Position (only while
     * [SessionState.Idle]). Returns false if its audio can't be played.
     */
    suspend fun play(take: TakeId): Boolean = mutex.withLock {
        if (_state.value != SessionState.Idle) return@withLock false
        val stored = deps.takes.get(take) ?: return@withLock false
        engine.pause()
        if (!engine.loadTake(deps.files.take(track.id, stored.id), stored.startFrame)) return@withLock false
        // Until Latency Calibration (#9), alignment uses the engine's estimate from recording.
        _state.value = SessionState.PlayingTake(stored)
        if (!engine.playTake(stored.reportedLatencyFrames, stored.transpose)) {
            becomeIdleLocked()
            return@withLock false
        }
        true
    }

    /** Renames a kept Take; blank names are ignored. */
    suspend fun rename(take: TakeId, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) deps.takes.rename(take, trimmed)
    }

    /** Deletes a kept Take and its audio. */
    suspend fun delete(take: TakeId) = mutex.withLock {
        val playing = _state.value as? SessionState.PlayingTake
        if (playing?.take?.id == take) {
            engine.clearTake()
            becomeIdleLocked()
        }
        // Row first, then the file: the list never shows a Take whose audio is gone.
        deps.takes.delete(take)
        deps.files.deleteTake(track.id, take)
    }

    /** Leaves the Session: stops audio and returns the Audio Engine to plain playback. */
    fun close() {
        observers.cancel()
        engine.endSession()
        load?.let(engine::unload)
    }

    // --- Internals ---

    /**
     * Starts recording from [from] now, or enters the countdown and returns it for [runCountdown].
     * The position is passed in: a seek just issued may not show in [playback] yet.
     */
    private suspend fun startRecordingOrCountdownLocked(from: Long): SessionState.CountingDown? {
        // Open the microphone before counting down, so a missing permission shows at once.
        if (!enableMicrophone()) throw MicrophoneUnavailable()
        engine.pause()
        engine.seekTo(from)
        if (!_countdownEnabled.value) {
            startRecordingLocked(from)
            return null
        }
        return SessionState.CountingDown(from, COUNTDOWN_SECONDS).also { _state.value = it }
    }

    private suspend fun runCountdown(start: SessionState.CountingDown) {
        var current = start
        while (true) {
            delay(1_000)
            mutex.withLock {
                // Identity, not equality: after stop() and a new record() a fresh countdown runs.
                if (_state.value !== current) return
                if (current.secondsLeft <= 1) {
                    startRecordingLocked(current.startPositionMs)
                    return
                }
                current = current.copy(secondsLeft = current.secondsLeft - 1)
                _state.value = current
            }
        }
    }

    /** Starts recording a Take at the Transpose the control is set to now. */
    private suspend fun startRecordingLocked(fromMs: Long) {
        val transpose = _transpose.value
        if (!engine.startRecording(fromMs, transpose)) {
            becomeIdleLocked()
            throw MicrophoneUnavailable()
        }
        _state.value = SessionState.Recording(fromMs, transpose)
    }

    private suspend fun finishRecordingLocked() {
        val recording = _state.value as SessionState.Recording
        val take = engine.stopRecording()
        if (take == null) {
            becomeIdleLocked()
            return
        }
        _state.value = SessionState.Review(take, recording.transpose)
        engine.playTake(take.reportedLatencyFrames, recording.transpose)
    }

    private suspend fun leaveReviewLocked(take: CapturedTake) {
        engine.clearTake()
        engine.seekTo(take.startPositionMs)
        becomeIdleLocked(take.startPositionMs)
    }

    /**
     * Enters [SessionState.Idle] and loads Backing Audio that became available meanwhile, at
     * [positionMs] if a seek to it was just issued (and may not show in [playback] yet).
     */
    private suspend fun becomeIdleLocked(positionMs: Long? = null) {
        _state.value = SessionState.Idle
        pendingBacking?.let { switchBackingLocked(it, positionMs ?: engine.playback.value.positionMs, resume = false) }
    }

    /** Loads [backing] and continues at [positionMs]; the stems are sample-aligned with the Original Audio. */
    private suspend fun switchBackingLocked(backing: BackingAudio, positionMs: Long, resume: Boolean) {
        pendingBacking = null
        if (backing == _backing.value) return
        loadBacking(backing)
        engine.seekTo(positionMs)
        if (resume) engine.play()
    }

    private suspend fun nextTakeName(): String {
        val names = deps.takes.observe(track.id).first().mapTo(HashSet()) { it.name }
        var n = names.size + 1
        while ("Take $n" in names) n++
        return "Take $n"
    }

    private suspend fun start() {
        _countdownEnabled.value = deps.settings.countdownEnabled()
        _reviewBackingDb.value = deps.settings.reviewBackingDb() ?: DEFAULT_REVIEW_BACKING_DB
        engine.setReviewBackingGain(_reviewBackingDb.value)
        engine.beginSession()
        engine.setTranspose(_transpose.value)
        loadBacking(backingFor(track))
        observe()
    }

    private suspend fun loadBacking(backing: BackingAudio) {
        load = engine.load(fileOf(backing))
        _backing.value = backing
    }

    private fun observe() {
        observers.launch {
            // A recording also ends without stop(): at the end of the song, or when the output
            // route goes away. Either way the captured part goes to Review. Take playback ends
            // by itself at the end of the Take.
            engine.playback.collect { playback ->
                val state = _state.value
                if (state is SessionState.Recording && !playback.recording) {
                    mutex.withLock {
                        if (_state.value is SessionState.Recording && !engine.playback.value.recording) {
                            finishRecordingLocked()
                        }
                    }
                } else if (state is SessionState.PlayingTake && !playback.playingTake) {
                    mutex.withLock {
                        if (_state.value is SessionState.PlayingTake && !engine.playback.value.playingTake) {
                            engine.clearTake()
                            becomeIdleLocked()
                        }
                    }
                }
            }
        }
        observers.launch {
            // Separation finished (ADR 10): switch to the Instrumental, but never under a Take
            // that is being recorded or reviewed.
            deps.tracks.observe(track.id).filterNotNull().map { backingFor(it) }.distinctUntilChanged().collect { backing ->
                mutex.withLock {
                    if (_state.value == SessionState.Idle) {
                        val now = engine.playback.value
                        switchBackingLocked(backing, now.positionMs, resume = now.playing)
                    } else {
                        pendingBacking = backing
                    }
                }
            }
        }
        observers.launch {
            // Each kind of microphone keeps its own input gain (ADR 9).
            engine.input.map { it.device }.filterNotNull().distinctUntilChanged().collect { device ->
                val gain = deps.settings.inputGainDb(device)
                _inputGainDb.value = gain
                engine.setInputGain(gain)
            }
        }
    }

    private fun backingFor(track: Track): BackingAudio = when (val pipeline = track.pipeline) {
        // A Vocal-Free Track (no stems) always sings over its Original Audio (ADR 14).
        is TrackPipelineState.Ready -> pipeline.model?.let(BackingAudio::Instrumental) ?: BackingAudio.OriginalAudio
        else -> BackingAudio.OriginalAudio
    }

    private fun fileOf(backing: BackingAudio): AudioFile = when (backing) {
        BackingAudio.OriginalAudio -> deps.files.originalAudio(track.id)
        is BackingAudio.Instrumental -> deps.files.stems(track.id, backing.model).instrumental
    }

    companion object {
        /** Length of the countdown before recording (time-based, ADR 9). */
        const val COUNTDOWN_SECONDS = 3
        const val MIN_INPUT_GAIN_DB = -12f
        const val MAX_INPUT_GAIN_DB = 24f
        const val DEFAULT_REVIEW_BACKING_DB = -20f
        const val MIN_REVIEW_BACKING_DB = -40f

        internal suspend fun open(track: Track, scope: CoroutineScope, deps: Dependencies): Session =
            Session(track, scope, deps).also { it.start() }
    }
}

/** What plays under the singer in a Session (GLOSSARY: Backing Audio). */
sealed interface BackingAudio {
    data object OriginalAudio : BackingAudio

    /** The Instrumental that Separation with [model] produced. */
    data class Instrumental(val model: SeparationModelId) : BackingAudio
}

/** Where a Session is in its workflow (ADR 9). */
sealed interface SessionState {
    /** Free playback: play, pause and seek to choose the next Start Position. */
    data object Idle : SessionState

    /** Recording from [startPositionMs] starts in [secondsLeft] seconds. */
    data class CountingDown(val startPositionMs: Long, val secondsLeft: Int) : SessionState

    /** A Take is being recorded from [startPositionMs], sung at [transpose]. */
    data class Recording(val startPositionMs: Long, val transpose: Transpose) : SessionState

    /**
     * A just-recorded [take], sung at [transpose], not kept yet: Keep, Discard or Record again
     * (GLOSSARY: Review).
     */
    data class Review(val take: CapturedTake, val transpose: Transpose) : SessionState

    /** A kept [take] plays against the Backing Audio. */
    data class PlayingTake(val take: Take) : SessionState
}

/** The microphone can't be opened (no permission or no input device). */
class MicrophoneUnavailable : IllegalStateException("the microphone is not available")
