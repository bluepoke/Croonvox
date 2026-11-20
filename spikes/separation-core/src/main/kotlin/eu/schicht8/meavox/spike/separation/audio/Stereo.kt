// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.separation.audio

/** Stereo float audio of known length, readable at any position (a temp file or a buffer). */
interface StereoSource {
    val sampleRate: Int
    val frames: Long

    /** Reads [count] frames from [start] into [left] and [right]; frames outside `0 until frames` read as 0. */
    fun read(start: Long, left: FloatArray, right: FloatArray, count: Int)
}

/** Receives stereo float audio in order. */
fun interface StereoSink {
    fun write(left: FloatArray, right: FloatArray, count: Int)
}

/** In-memory [StereoSource] and [StereoSink]; for short audio and tests. */
class StereoBuffer(
    override val sampleRate: Int,
    left: FloatArray = FloatArray(0),
    right: FloatArray = left.copyOf(),
) : StereoSource, StereoSink {
    private var l = left.copyOf()
    private var r = right.copyOf()
    private var size = left.size

    init {
        require(left.size == right.size) { "channels differ in length" }
    }

    override val frames: Long get() = size.toLong()
    val left: FloatArray get() = l.copyOf(size)
    val right: FloatArray get() = r.copyOf(size)

    override fun read(start: Long, left: FloatArray, right: FloatArray, count: Int) {
        for (i in 0 until count) {
            val p = start + i
            val inside = p in 0 until size
            left[i] = if (inside) l[p.toInt()] else 0f
            right[i] = if (inside) r[p.toInt()] else 0f
        }
    }

    override fun write(left: FloatArray, right: FloatArray, count: Int) {
        if (size + count > l.size) {
            val capacity = maxOf(size + count, l.size * 2)
            l = l.copyOf(capacity)
            r = r.copyOf(capacity)
        }
        left.copyInto(l, size, 0, count)
        right.copyInto(r, size, 0, count)
        size += count
    }
}
