// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.model

/**
 * One microphone recording of the user singing over a Track (GLOSSARY: Take). Its audio, timing
 * and [transpose] never change after recording (ADR 5, ADR 7); only its [name] can. Positions are frames on the Session timeline at
 * [sampleRate]; the audio is stored as mono FLAC at that rate (ADR 8).
 */
data class Take(
    val id: TakeId,
    val track: TrackId,
    val name: String,
    /** Start Position: where on the Track timeline the Take begins. */
    val startFrame: Long,
    val frames: Long,
    val sampleRate: Int,
    /** The output route it was recorded on; Latency Calibration is chosen by it (ADR 4). */
    val route: OutputRoute,
    /** The engine's round-trip estimate at recording, used for alignment until calibrated. */
    val reportedLatencyFrames: Long,
    /** The Transpose it was sung at; its Backing Audio always plays at it (ADR 5). */
    val transpose: Transpose,
    /** Epoch milliseconds. */
    val recordedAt: Long,
) {
    val startPositionMs: Long get() = startFrame * 1000 / sampleRate
    val durationMs: Long get() = frames * 1000 / sampleRate
}

@JvmInline
value class TakeId(val value: String)

/** Kind of output a Session plays through (GLOSSARY: Latency Calibration is per route). */
enum class OutputRoute { Speaker, Wired, Usb, Bluetooth, Other }

/** Kind of microphone; the input gain is set per kind (ADR 9). */
enum class InputDevice { BuiltIn, Wired, Usb, Bluetooth, Other }
