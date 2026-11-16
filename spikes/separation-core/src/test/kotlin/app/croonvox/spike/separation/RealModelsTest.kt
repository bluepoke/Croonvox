// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation

import app.croonvox.spike.separation.audio.Resampler
import app.croonvox.spike.separation.audio.StereoBuffer
import app.croonvox.spike.separation.model.RuntimeOptions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in: runs every installed candidate on the JVM with real ONNX Runtime, end to end.
 * `./gradlew :spikes:separation-core:test -PseparationModels=<dir>`, where `<dir>/<candidate id>/`
 * holds the model files and `<dir>/test.wav` a 16-bit PCM clip. Stems land in `build/separation-out/`.
 */
class RealModelsTest {
    private val modelsDir = System.getProperty("separationModels")?.let(::File)

    @TestFactory
    fun `installed candidates separate a real song into aligned, full-length stems`(): List<DynamicTest> {
        assumeTrue(modelsDir != null, "set -PseparationModels to run real models")
        val clip = readWav(File(modelsDir, "test.wav"))
        // Through 48 kHz, as most YouTube audio is (Opus is always 48 kHz).
        val at48 = StereoBuffer(48_000).also {
            Resampler.resample(clip, 48_000, Resampler.framesAt(clip.frames, clip.sampleRate, 48_000), it)
        }
        val out = File("build/separation-out").apply { mkdirs() }
        return Catalogue.candidates.filter { it.isInstalled(File(modelsDir, it.id)) }.map { candidate ->
            DynamicTest.dynamicTest(candidate.id) {
                val result = SeparationBenchmark(File(out, "work"), SeparationBenchmark.procStatm(4096)).run(
                    at48, candidate, File(modelsDir, candidate.id), BenchmarkOptions(RuntimeOptions(threads = 4, cpuArena = System.getProperty("cpuArena") != "false")), out, candidate.id,
                )
                println(
                    "%-22s load %5d ms  separation %6d ms  RTF %.2f  segments %d (max %d ms)  peak RSS %d MB (+%d)  lags %s/%s".format(
                        candidate.id, result.loadMs, result.separationMs, result.realTimeFactor, result.segments,
                        result.segmentMsMax, result.peakRssBytes shr 20, result.peakDeltaBytes shr 20,
                        result.instrumentalLag, result.vocalStemLag,
                    ),
                )
                assertEquals(at48.frames, result.instrumentalFrames)
                assertEquals(at48.frames, result.vocalStemFrames)
                assertEquals(0, result.instrumentalLag)
                assertEquals(0, result.vocalStemLag)
                val vocals = rms(readWav(result.vocalStem))
                val instrumental = rms(readWav(result.instrumental))
                assertTrue(vocals > 0.01 * instrumental && instrumental > 0.01 * vocals, "both stems carry audio: $vocals / $instrumental")
            }
        }
    }

    private fun rms(b: StereoBuffer): Double {
        var sum = 0.0
        for (x in b.left) sum += x * x
        return sqrt(sum / b.left.size)
    }

    /** 16-bit PCM WAV, mono or stereo. */
    private fun readWav(file: File): StereoBuffer {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 0
        var rate = 0
        while (pos + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(pos + it) }, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            if (id == "fmt ") {
                require(b.getShort(pos + 8).toInt() == 1 && b.getShort(pos + 22).toInt() == 16) { "16-bit PCM only" }
                channels = b.getShort(pos + 10).toInt()
                rate = b.getInt(pos + 12)
            } else if (id == "data") {
                val frames = minOf(size, b.limit() - pos - 8) / (2 * channels)
                val l = FloatArray(frames) { b.getShort(pos + 8 + 2 * channels * it) / 32768f }
                val r = FloatArray(frames) { b.getShort(pos + 8 + 2 * channels * it + 2 * (channels - 1)) / 32768f }
                return StereoBuffer(rate, l, r)
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in $file")
    }
}
