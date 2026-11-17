// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation

/** Planar stereo frames of one segment. */
class StereoFrames(val left: FloatArray, val right: FloatArray) {
    init {
        require(left.size == right.size) { "channels differ in length" }
    }

    val size: Int get() = left.size
}

/** The Vocal Stem and Instrumental of one segment, each as long as the input segment. */
class SegmentStems(val vocals: StereoFrames, val instrumental: StereoFrames)

/**
 * One Separation Model, run on a fixed-length segment of stereo audio at [sampleRate]. Model
 * adapters implement it; [SegmentedSeparation] runs it over a whole song.
 */
interface SegmentSeparator : AutoCloseable {
    val sampleRate: Int
    val segmentFrames: Int

    /** [input] holds exactly [segmentFrames] frames; the result's stems are as long and aligned with it. */
    fun separate(input: StereoFrames): SegmentStems
}
