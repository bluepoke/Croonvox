// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.engine

/** JNI surface of the native `PlaybackEngine`. Every call takes the handle from [create]. Use [OboeAudioEngine]. */
internal object NativePlayback {
    init {
        System.loadLibrary("croonvox_engine")
    }

    /** Values of [transport], mirroring the native `Transport` enum. */
    const val IDLE = 0
    const val PLAYING = 1
    const val RECORDING = 2
    const val PLAYING_TAKE = 3

    /** The engine is never destroyed; it lives as long as the process. */
    external fun create(): Long
    external fun beginLoad(handle: Long, channels: Int, sampleRate: Int, estimatedFrames: Long)
    /** Returns false once the reserved capacity is exhausted: stop decoding. */
    external fun appendLoad(handle: Long, interleaved: FloatArray, frames: Int): Boolean
    external fun endLoad(handle: Long)
    external fun discardLoad(handle: Long)
    external fun play(handle: Long): Boolean
    external fun pause(handle: Long)
    external fun seekTo(handle: Long, frame: Long)
    external fun transport(handle: Long): Int
    external fun isPlaying(handle: Long): Boolean
    external fun positionFrames(handle: Long): Long
    external fun durationFrames(handle: Long): Long
    external fun decodedFrames(handle: Long): Long
    external fun sampleRate(handle: Long): Int

    // Session
    external fun setSessionMode(handle: Long, enabled: Boolean)
    external fun enableInput(handle: Long): Boolean
    external fun inputEnabled(handle: Long): Boolean
    external fun setInputGain(handle: Long, linear: Float)
    external fun setReviewBackingGain(handle: Long, linear: Float)
    external fun inputPeak(handle: Long): Float
    external fun clipCount(handle: Long): Long
    external fun outputDeviceId(handle: Long): Int
    external fun inputDeviceId(handle: Long): Int
    external fun startRecording(handle: Long, fromFrame: Long): Boolean
    external fun stopRecording(handle: Long)
    external fun takeStartFrame(handle: Long): Long
    external fun takeFrames(handle: Long): Long
    external fun takeReportedLatencyFrames(handle: Long): Long
    external fun takeXRunCorrectionFrames(handle: Long): Long
    external fun playTake(handle: Long, alignmentFrames: Long): Boolean
    external fun clearTake(handle: Long)
    external fun beginTakeLoad(handle: Long, startFrame: Long, sampleRate: Int, estimatedFrames: Long): Boolean
    external fun appendTakeLoad(handle: Long, interleaved: FloatArray, frames: Int, channels: Int)
    external fun writeTakeFlac(handle: Long, path: String): Boolean
}
