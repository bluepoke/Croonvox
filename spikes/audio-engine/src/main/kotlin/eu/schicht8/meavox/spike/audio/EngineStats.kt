// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.audio

/** Snapshot of engine state; indices mirror `StatIndex` in SpikeEngine.h. */
class EngineStats(private val v: DoubleArray) {
    enum class State { Idle, Playing, Recording, Reviewing, Calibrating }
    enum class Api { Unspecified, OpenSLES, AAudio }

    val state: State get() = State.entries.getOrElse(v[0].toInt()) { State.Idle }
    val streamOpen: Boolean get() = v[1] != 0.0
    val sampleRate: Int get() = v[2].toInt()
    val framesPerBurst: Int get() = v[3].toInt()
    val bufferSize: Int get() = v[4].toInt()
    val outputApi: Api get() = Api.entries.getOrElse(v[5].toInt()) { Api.Unspecified }
    val outputExclusive: Boolean get() = v[6] != 0.0
    val inputExclusive: Boolean get() = v[7] != 0.0
    val outputLowLatency: Boolean get() = v[8] != 0.0
    val inputLowLatency: Boolean get() = v[9] != 0.0
    /** Oboe-reported latency, or null if unavailable. */
    val outputLatencyMs: Double? get() = v[10].takeIf { it >= 0 }
    val inputLatencyMs: Double? get() = v[11].takeIf { it >= 0 }
    /** Output xruns, accumulated across stream reopens. */
    val xRuns: Int get() = v[12].toInt()
    val cpuLoad: Double get() = v[13]
    val inputPeak: Float get() = v[14].toFloat()
    val positionFrames: Long get() = v[15].toLong()
    val backingFrames: Long get() = v[16].toLong()
    val takeFrames: Long get() = v[17].toLong()
    val takeStartFrame: Long get() = v[18].toLong()
    val takeTranspose: Int get() = v[19].toInt()
    /** Last measured round trip in frames, or null if none or failed. */
    val calibrationFrames: Long? get() = v[20].toLong().takeIf { it >= 0 }
    val calibrationClicks: Int get() = v[21].toInt()
    val stretchLatencyFrames: Int get() = v[22].toInt()
    val outputDeviceId: Int get() = v[23].toInt()
    val routeGeneration: Int get() = v[24].toInt()
    val latencyCompFrames: Long get() = v[25].toLong()
    val inputXRuns: Int get() = v[26].toInt()
    val inputShortfalls: Long get() = v[27].toLong()

    fun framesToMs(frames: Long): Double = if (sampleRate > 0) frames * 1000.0 / sampleRate else 0.0
    fun framesToSeconds(frames: Long): Double = if (sampleRate > 0) frames.toDouble() / sampleRate else 0.0

    companion object {
        const val COUNT = 28
        val EMPTY = EngineStats(DoubleArray(COUNT).also { it[10] = -1.0; it[11] = -1.0; it[20] = -1.0 })
    }
}
