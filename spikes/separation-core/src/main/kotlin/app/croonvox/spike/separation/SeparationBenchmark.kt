// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation

import app.croonvox.spike.separation.audio.Alignment
import app.croonvox.spike.separation.audio.RawStereoFile
import app.croonvox.spike.separation.audio.RawStereoWriter
import app.croonvox.spike.separation.audio.Resampler
import app.croonvox.spike.separation.audio.StereoSource
import app.croonvox.spike.separation.audio.WavWriter
import app.croonvox.spike.separation.model.RuntimeOptions
import java.io.File

/** Resident memory (RSS) of this process in bytes: `/proc/self/statm` on Android and Linux. */
fun interface MemoryProbe {
    fun residentBytes(): Long
}

data class BenchmarkOptions(val runtime: RuntimeOptions = RuntimeOptions(), val overlap: Float = 0.25f)

/** Where a run is, for the progress display. */
enum class Stage { Resampling, LoadingModel, Separating, Writing }

data class BenchmarkResult(
    val candidateId: String,
    val options: BenchmarkOptions,
    val inputRate: Int,
    val inputFrames: Long,
    val resampleInMs: Long,
    val loadMs: Long,
    val separationMs: Long,
    val writeMs: Long,
    val segments: Int,
    val segmentMsMean: Double,
    val segmentMsMax: Long,
    val baselineRssBytes: Long,
    val peakRssBytes: Long,
    val instrumentalFrames: Long,
    val vocalStemFrames: Long,
    val instrumentalLag: Int?,
    val vocalStemLag: Int?,
    val instrumental: File,
    val vocalStem: File,
) {
    val audioSeconds: Double get() = inputFrames.toDouble() / inputRate
    val totalMs: Long get() = resampleInMs + loadMs + separationMs + writeMs

    /** Separation wall time ÷ audio duration; below 1 is faster than real time. */
    val realTimeFactor: Double get() = separationMs / 1000.0 / audioSeconds
    val realTimeFactorTotal: Double get() = totalMs / 1000.0 / audioSeconds
    val peakDeltaBytes: Long get() = peakRssBytes - baselineRssBytes
}

/**
 * One measured Separation: Original Audio at its own rate → 44.1 kHz → [ChunkedSeparation] →
 * back to the original rate, as two 16-bit WAVs exactly as long as the input. Intermediate audio
 * goes through temp files in [workDir], so memory doesn't grow with the song.
 */
class SeparationBenchmark(private val workDir: File, private val memory: MemoryProbe) {
    fun run(
        original: StereoSource,
        candidate: Candidate,
        modelDir: File,
        options: BenchmarkOptions,
        outDir: File,
        baseName: String,
        onProgress: (Stage, Float) -> Unit = { _, _ -> },
    ): BenchmarkResult {
        workDir.mkdirs()
        outDir.mkdirs()
        val baseline = memory.residentBytes()
        val peak = PeakSampler(memory).apply { start() }
        val temps = mutableListOf<File>()
        val opened = mutableListOf<AutoCloseable>() // closed even when a stage throws
        fun temp(name: String) = File(workDir, "$name.f32").also { temps += it }
        fun writer(name: String) = RawStereoWriter(temp(name), RATE).also { opened += it }
        fun RawStereoWriter.done() = finish().also { opened += it }
        try {
            onProgress(Stage.Resampling, 0f)
            var t0 = System.nanoTime()
            val in44: StereoSource = if (original.sampleRate == RATE) {
                original
            } else {
                writer("input-44k").run {
                    Resampler.resample(original, RATE, Resampler.framesAt(original.frames, original.sampleRate, RATE), this)
                    done()
                }
            }
            val resampleInMs = elapsedMs(t0)

            onProgress(Stage.LoadingModel, 0f)
            t0 = System.nanoTime()
            val segmentTimes = mutableListOf<Long>()
            val (vocals44, instrumental44, loadMs, separationMs) = candidate.open(modelDir, options.runtime).use { separator ->
                val loadMs = elapsedMs(t0)
                val timed = TimedSeparator(separator, segmentTimes)
                val vocals = writer("vocals-44k")
                val instrumental = writer("instrumental-44k")
                t0 = System.nanoTime()
                ChunkedSeparation.run(in44, timed, vocals, instrumental, options.overlap) { done, total ->
                    onProgress(Stage.Separating, done.toFloat() / total)
                }
                Separated(vocals.done(), instrumental.done(), loadMs, elapsedMs(t0))
            }

            onProgress(Stage.Writing, 0f)
            t0 = System.nanoTime()
            val vocalFile = File(outDir, "$baseName-vocal-stem.wav")
            val instrumentalFile = File(outDir, "$baseName-instrumental.wav")
            // Each stem goes to its WAV and, at the same rate, to a temp file: the lag is measured
            // against the Original Audio on exactly what was written, resampling back included.
            fun writeStem(stem: RawStereoFile, wav: File, name: String): Pair<Long, RawStereoFile> {
                val copy = RawStereoWriter(temp(name), original.sampleRate).also { opened += it }
                val frames = WavWriter(wav, original.sampleRate).use { out ->
                    Resampler.resample(stem, original.sampleRate, original.frames) { l, r, n ->
                        out.write(l, r, n)
                        copy.write(l, r, n)
                    }
                    out.frames
                }
                return frames to copy.done()
            }
            val (vocalFrames, vocalsOut) = writeStem(vocals44, vocalFile, "vocals-out")
            val (instrumentalFrames, instrumentalOut) = writeStem(instrumental44, instrumentalFile, "instrumental-out")
            val writeMs = elapsedMs(t0)
            val vocalLag = Alignment.lag(original, vocalsOut)
            val instrumentalLag = Alignment.lag(original, instrumentalOut)

            return BenchmarkResult(
                candidateId = candidate.id,
                options = options,
                inputRate = original.sampleRate,
                inputFrames = original.frames,
                resampleInMs = resampleInMs,
                loadMs = loadMs,
                separationMs = separationMs,
                writeMs = writeMs,
                segments = segmentTimes.size,
                segmentMsMean = segmentTimes.average(),
                segmentMsMax = segmentTimes.maxOrNull() ?: 0,
                baselineRssBytes = baseline,
                peakRssBytes = peak.stop(),
                instrumentalFrames = instrumentalFrames,
                vocalStemFrames = vocalFrames,
                instrumentalLag = instrumentalLag,
                vocalStemLag = vocalLag,
                instrumental = instrumentalFile,
                vocalStem = vocalFile,
            )
        } finally {
            peak.stop()
            opened.forEach { runCatching { it.close() } } // never [original]: the caller owns it
            temps.forEach { it.delete() }
        }
    }

    private data class Separated(val vocals: RawStereoFile, val instrumental: RawStereoFile, val loadMs: Long, val separationMs: Long)

    private class TimedSeparator(private val inner: SegmentSeparator, private val times: MutableList<Long>) : SegmentSeparator by inner {
        override fun separate(input: StereoFrames): Stems {
            val t0 = System.nanoTime()
            return inner.separate(input).also { times += elapsedMs(t0) }
        }
    }

    /** Samples [MemoryProbe] every 20 ms on a daemon thread and keeps the maximum. */
    private class PeakSampler(private val probe: MemoryProbe) {
        @Volatile private var running = true
        @Volatile private var peak = probe.residentBytes()
        private val thread = Thread({
            while (running) {
                peak = maxOf(peak, probe.residentBytes())
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "rss-sampler").apply { isDaemon = true }

        fun start() = thread.start()

        fun stop(): Long {
            if (running) {
                running = false
                thread.interrupt()
                thread.join()
                peak = maxOf(peak, probe.residentBytes())
            }
            return peak
        }
    }

    companion object {
        const val RATE = 44_100

        private fun elapsedMs(t0: Long) = (System.nanoTime() - t0) / 1_000_000

        /** RSS from `/proc/self/statm` (Linux and Android; pages may be 16 KiB there); 0 elsewhere. */
        fun procStatm(pageSize: Long) = MemoryProbe {
            try {
                File("/proc/self/statm").readText().trim().split(' ')[1].toLong() * pageSize
            } catch (_: Exception) {
                0L
            }
        }
    }
}
