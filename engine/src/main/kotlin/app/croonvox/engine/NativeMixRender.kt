// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.engine

/** JNI surface of the native `OfflineMixRender`. Every handle from [create] must be [release]d. Use [NativeOfflineRenderer]. */
internal object NativeMixRender {
    init {
        System.loadLibrary("croonvox_engine")
    }

    /** As `to`: render until the Backing Audio ends (the native `kUntilEnd`). */
    const val UNTIL_END = Long.MAX_VALUE

    /** Gains are linear; Take sample j sounds at timeline frame takeStart + j − alignment. */
    external fun create(
        sampleRate: Int,
        from: Long,
        to: Long,
        takeStart: Long,
        alignment: Long,
        transpose: Int,
        backingGain: Float,
        takeGain: Float,
    ): Long
    external fun appendTake(handle: Long, interleaved: FloatArray, frames: Int, channels: Int)
    external fun appendBacking(handle: Long, interleaved: FloatArray, frames: Int, channels: Int)
    external fun endBacking(handle: Long)
    external fun needsBacking(handle: Long): Boolean
    /** Moves up to [maxFrames] interleaved stereo frames into [out]; returns how many. */
    external fun drain(handle: Long, out: FloatArray, maxFrames: Int): Int
    external fun release(handle: Long)
}
