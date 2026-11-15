// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.engine

/** JNI surface of the native `PlaybackEngine`. Every call takes the handle from [create]. Use [OboeAudioEngine]. */
internal object NativePlayback {
    init {
        System.loadLibrary("croonvox_engine")
    }

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
    external fun isPlaying(handle: Long): Boolean
    external fun positionFrames(handle: Long): Long
    external fun durationFrames(handle: Long): Long
    external fun decodedFrames(handle: Long): Long
    external fun sampleRate(handle: Long): Int
}
