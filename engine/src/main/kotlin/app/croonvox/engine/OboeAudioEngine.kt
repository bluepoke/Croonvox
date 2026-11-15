// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.engine

import android.util.Log
import app.croonvox.core.model.AudioFile
import app.croonvox.core.port.AudioEngine
import app.croonvox.core.port.LoadId
import app.croonvox.core.port.Playback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [AudioEngine] on the native Oboe `PlaybackEngine`.
 *
 * Loading is progressive: Android's decoders run at only ~8× real time on weak devices (each
 * small MP3 frame is a round trip to the codec process), so the file keeps decoding in the
 * background while it already plays. [load] returns after [START_BUFFER_MS] are decoded.
 * The decoded audio is held in memory (one copy; see known limitations in the README).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OboeAudioEngine : AudioEngine {
    private val handle = NativePlayback.create()
    private val state = MutableStateFlow(Playback.Idle)
    override val playback: StateFlow<Playback> = state

    // Native control calls are serialised on one thread; decoding runs on IO.
    private val control = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + control)
    private val loads = Mutex() // guards the fields below; loads and unloads never overlap
    private var decodeJob: Job? = null
    private var generation = 0L
    private var currentLoad: LoadId? = null
    private var ticker: Job? = null

    override suspend fun load(audio: AudioFile, onProgress: (Float) -> Unit): LoadId {
        val playable = CompletableDeferred<Unit>()
        val job: Job
        val id: LoadId
        loads.withLock {
            stopDecodingLocked()
            id = LoadId(++generation)
            currentLoad = id
            job = scope.launch(Dispatchers.IO) { decode(audio, onProgress, playable) }
            decodeJob = job
        }
        try {
            playable.await()
        } catch (e: CancellationException) {
            job.cancel() // this load's own decode; a newer load may already own decodeJob
            throw e
        }
        withContext(control) { publish(loaded = audio) }
        return id
    }

    override fun unload(load: LoadId) {
        scope.launch {
            loads.withLock {
                // An old owner (e.g. a screen torn down after the next one opened) must not
                // unload a newer load.
                if (load != currentLoad) return@withLock
                stopDecodingLocked()
                NativePlayback.discardLoad(handle)
                currentLoad = null
            }
        }
    }

    /** Cancels a background decode and resets the published state. Call with [loads] held. */
    private suspend fun stopDecodingLocked() {
        decodeJob?.cancelAndJoin()
        decodeJob = null
        withContext(control) {
            stopTicker()
            state.value = Playback.Idle
        }
    }

    private suspend fun decode(file: AudioFile, onProgress: (Float) -> Unit, playable: CompletableDeferred<Unit>) {
        val started = System.nanoTime()
        try {
            try {
                runInterruptible { AudioDecoder.decode(file.path, DecodeSink(file, onProgress, playable, started)) }
            } catch (e: CancellationException) {
                throw e // also an Exception: must not be mistaken for a decode error below
            } catch (e: CapacityExhausted) {
                Log.w(TAG, "${file.path} is longer than expected; playing the first part only")
            } catch (e: Exception) {
                if (!playable.isCompleted) throw e
                // Playback already started: keep what decoded instead of pulling the audio away.
                Log.w(TAG, "decoding ${file.path} failed part-way; playing the decoded part", e)
            }
            NativePlayback.endLoad(handle)
            Log.i(TAG, "decoded fully in ${(System.nanoTime() - started) / 1_000_000} ms")
            playable.complete(Unit) // short files: playable once complete
            onProgress(1f)
            withContext(control) { publish() }
        } catch (e: Throwable) {
            NativePlayback.discardLoad(handle) // free a half-decoded song, also on cancellation
            playable.completeExceptionally(e)
            if (e is CancellationException) throw e
            Log.w(TAG, "could not decode ${file.path}", e)
            withContext(control) { state.value = Playback.Idle }
        }
    }

    /** Feeds decoded PCM into the native engine and signals [playable] after [START_BUFFER_MS]. */
    private inner class DecodeSink(
        private val file: AudioFile,
        private val onProgress: (Float) -> Unit,
        private val playable: CompletableDeferred<Unit>,
        private val started: Long,
    ) : AudioDecoder.Sink {
        private var startFrames = Long.MAX_VALUE // known once the decoder reports the sample rate

        override fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long) {
            NativePlayback.beginLoad(handle, channels, sampleRate, estimatedFrames)
            startFrames = sampleRate.toLong() * START_BUFFER_MS / 1000
            Log.i(TAG, "decoding ${file.path}: $channels ch, $sampleRate Hz")
        }

        override fun append(interleaved: FloatArray, frames: Int) {
            if (!NativePlayback.appendLoad(handle, interleaved, frames)) throw CapacityExhausted()
            if (!playable.isCompleted && NativePlayback.decodedFrames(handle) >= startFrames) {
                Log.i(TAG, "playable after ${(System.nanoTime() - started) / 1_000_000} ms")
                playable.complete(Unit)
            }
        }

        override fun progress(fraction: Float) {
            onProgress(fraction)
            if (playable.isCompleted) scope.launch { publish() } // update bufferedMs
        }
    }

    /** The audio is longer than the reserved capacity (wrong duration in the container). */
    private class CapacityExhausted : RuntimeException()

    override fun play() {
        scope.launch {
            if (NativePlayback.play(handle)) startTicker()
            publish()
        }
    }

    override fun pause() {
        scope.launch {
            NativePlayback.pause(handle)
            stopTicker()
            publish()
        }
    }

    override fun seekTo(positionMs: Long) {
        scope.launch {
            val rate = NativePlayback.sampleRate(handle)
            if (rate > 0) NativePlayback.seekTo(handle, positionMs * rate / 1000)
            publish()
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(POSITION_INTERVAL_MS)
                if (!NativePlayback.isPlaying(handle)) {
                    // Reached the end, or a route change paused it: stop the output stream too.
                    NativePlayback.pause(handle)
                    publish()
                    break
                }
                publish()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun publish(loaded: AudioFile? = state.value.loaded) {
        val rate = NativePlayback.sampleRate(handle)
        fun ms(frames: Long) = if (rate > 0) frames * 1000 / rate else 0
        state.value = Playback(
            loaded = loaded,
            playing = NativePlayback.isPlaying(handle),
            positionMs = ms(NativePlayback.positionFrames(handle)),
            durationMs = ms(NativePlayback.durationFrames(handle)),
            bufferedMs = ms(NativePlayback.decodedFrames(handle)),
        )
    }

    private companion object {
        const val POSITION_INTERVAL_MS = 50L
        const val START_BUFFER_MS = 2_000L
        const val TAG = "CroonvoxEngine"
    }
}
