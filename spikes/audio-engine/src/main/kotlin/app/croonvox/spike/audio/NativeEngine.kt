// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.audio

/**
 * JNI surface of the spike Audio Engine (one process-wide native instance).
 *
 * Transport calls may block for up to ~200 ms while the audio thread acknowledges Idle,
 * so call them off the main thread.
 */
object NativeEngine {
    init {
        System.loadLibrary("spike_audio")
    }

    const val TRANSPOSE_MIN = -6
    const val TRANSPOSE_MAX = 6

    /** Opens and starts the full-duplex streams. Returns true on success. */
    fun open(): Boolean = nativeOpen() == 0

    fun close() = nativeClose()

    /** Closes and reopens the streams on the current route; stops the transport. */
    fun reopen(): Boolean = nativeReopen() == 0

    fun beginBacking(channels: Int, sampleRate: Int) = nativeBeginBacking(channels, sampleRate)

    /** [interleaved] holds at least `frames * channels` samples. */
    fun appendBacking(interleaved: FloatArray, frames: Int) = nativeAppendBacking(interleaved, frames)

    fun endBacking() = nativeEndBacking()

    fun generateTestBacking(seconds: Double) = nativeGenerateTestBacking(seconds)

    fun play(fromFrame: Long): Boolean = nativePlay(fromFrame)

    fun record(fromFrame: Long): Boolean = nativeRecord(fromFrame)

    fun review(): Boolean = nativeReview()

    fun stop() = nativeStop()

    fun startCalibration(): Boolean = nativeStartCalibration()

    fun setTranspose(semitones: Int) = nativeSetTranspose(semitones.coerceIn(TRANSPOSE_MIN, TRANSPOSE_MAX))

    fun setMonitoring(enabled: Boolean) = nativeSetMonitoring(enabled)

    fun setMonitorGain(gain: Float) = nativeSetMonitorGain(gain)

    /** Round-trip compensation for Takes in frames; `null` uses Oboe's latency estimate. */
    fun setLatencyCompensation(frames: Long?) = nativeSetLatencyCompensation(frames?.toInt() ?: -1)

    fun saveAlignmentWav(path: String): Boolean = nativeSaveAlignmentWav(path)

    fun stats(): EngineStats {
        val raw = DoubleArray(EngineStats.COUNT)
        nativeGetStats(raw)
        return EngineStats(raw)
    }

    private external fun nativeOpen(): Int
    private external fun nativeClose()
    private external fun nativeReopen(): Int
    private external fun nativeBeginBacking(channels: Int, sampleRate: Int)
    private external fun nativeAppendBacking(interleaved: FloatArray, frames: Int)
    private external fun nativeEndBacking()
    private external fun nativeGenerateTestBacking(seconds: Double)
    private external fun nativePlay(fromFrame: Long): Boolean
    private external fun nativeRecord(fromFrame: Long): Boolean
    private external fun nativeReview(): Boolean
    private external fun nativeStop()
    private external fun nativeStartCalibration(): Boolean
    private external fun nativeSetTranspose(semitones: Int)
    private external fun nativeSetMonitoring(enabled: Boolean)
    private external fun nativeSetMonitorGain(gain: Float)
    private external fun nativeSetLatencyCompensation(frames: Int)
    private external fun nativeGetStats(out: DoubleArray)
    private external fun nativeSaveAlignmentWav(path: String): Boolean
}
