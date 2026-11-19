// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.port

import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.Transpose

/**
 * The Audio Engine's Offline Render (spec #1, ADR 7): Backing Audio plus a Take into PCM, with the
 * same DSP and parameters as Take playback in Review and the Mix Preview, so an Export sounds like them.
 */
interface MixRenderer {
    /**
     * Renders timeline frames [MixRender.fromFrame] until [MixRender.toFrame] as interleaved stereo
     * float at [MixRender.sampleRate] into [sink], in order, then returns. Cancellable.
     * @throws java.io.IOException if a file can't be read or its sample rate doesn't match.
     */
    suspend fun render(render: MixRender, sink: PcmSink)
}

/**
 * One Offline Render. Frames are on the Session timeline at [sampleRate]. Take sample i sounds at
 * timeline frame [takeStartFrame] + i − [alignmentFrames], as in [AudioEngine.playTake]. The Backing
 * Audio plays at [transpose] (ADR 5).
 */
data class MixRender(
    val backing: AudioFile,
    val take: AudioFile,
    val sampleRate: Int,
    val takeStartFrame: Long,
    val alignmentFrames: Long,
    val transpose: Transpose,
    val backingGainDb: Float,
    val takeGainDb: Float,
    val fromFrame: Long,
    val toFrame: Long,
) {
    val frames: Long get() = toFrame - fromFrame
}

/** Receives rendered audio: interleaved stereo float frames, in order. */
fun interface PcmSink {
    /** [interleaved] holds `frames * 2` valid samples; it may be reused after the call. */
    fun write(interleaved: FloatArray, frames: Int)
}
