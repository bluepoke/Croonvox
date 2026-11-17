// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation.audio

/** Receives interleaved float frames in order. [interleaved] may be reused after the call. */
fun interface FrameSink {
    fun write(interleaved: FloatArray, frames: Int)
}

/** Collects interleaved frames in memory; for short audio and tests. */
class InterleavedBuffer(val channels: Int) : FrameSink {
    private var data = FloatArray(0)
    var frames = 0
        private set

    override fun write(interleaved: FloatArray, frames: Int) {
        val needed = (this.frames + frames) * channels
        if (needed > data.size) data = data.copyOf(maxOf(needed, data.size * 2))
        interleaved.copyInto(data, this.frames * channels, 0, frames * channels)
        this.frames += frames
    }

    /** All frames, interleaved. */
    fun interleaved(): FloatArray = data.copyOf(frames * channels)

    /** One channel's samples. */
    fun channel(index: Int): FloatArray = FloatArray(frames) { data[it * channels + index] }
}
