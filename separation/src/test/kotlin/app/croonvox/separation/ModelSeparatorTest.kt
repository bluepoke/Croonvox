// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation

import app.croonvox.core.model.AppDirectory
import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.port.Stems
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelSeparatorTest {
    @TempDir
    lateinit var dir: File

    /** Vocals are a quarter of the input, the Instrumental the rest. Fails like a killed process at [failAtCall]. */
    private class LinearSeparator(private val failAtCall: Int? = null) : SegmentSeparator {
        override val sampleRate = 44_100
        override val segmentFrames = 4_000
        var calls = 0

        override fun separate(input: StereoFrames): SegmentStems {
            if (calls == failAtCall) throw ProcessKilled()
            calls++
            return SegmentStems(
                vocals = StereoFrames(FloatArray(input.size) { input.left[it] * 0.25f }, FloatArray(input.size) { input.right[it] * 0.25f }),
                instrumental = StereoFrames(FloatArray(input.size) { input.left[it] * 0.75f }, FloatArray(input.size) { input.right[it] * 0.75f }),
            )
        }

        override fun close() {}
    }

    private class ProcessKilled : RuntimeException("killed")

    private val files = RawPcmFiles()
    private val model = SeparationModelId("linear")
    private var separator = LinearSeparator()
    private val catalogue = listOf(SeparationModel(model, "Linear", download = null) { separator })

    private fun modelSeparator() = ModelSeparator(catalogue, ModelStore(File(dir, "models")), files, files)

    /**
     * 48 kHz stereo, as most YouTube audio, so Separation resamples there and back. Tones below
     * 15 kHz: they pass 44.1 kHz unchanged.
     */
    private val original by lazy {
        val random = java.util.Random(5)
        val tones = List(20) { Triple(50 + random.nextDouble() * 14_950, random.nextDouble() * 6.28, 0.05 * random.nextDouble()) }
        val samples = FloatArray(2 * 50_001) { i ->
            val t = (i / 2) / 48_000.0 + (i % 2) * 0.001 // right channel slightly shifted
            tones.sumOf { (f, phase, a) -> a * kotlin.math.sin(2 * Math.PI * f * t + phase) }.toFloat()
        }
        File(dir, "original").also { files.write(it, RawPcmFiles.Audio(2, 48_000, samples)) }
    }

    private fun stemsIn(name: String) = File(dir, name).let { d ->
        d.mkdirs()
        Stems(AudioFile(File(d, "instrumental").path), AudioFile(File(d, "vocals").path))
    }

    @Test
    fun `a Separation killed part-way continues from its checkpoint and stores the same stems`() = runTest {
        val expected = stemsIn("expected")
        modelSeparator().separate(AudioFile(original.path), model, expected, AppDirectory(File(dir, "cp-a").path))
        val stems = stemsIn("resumed")
        val checkpoint = AppDirectory(File(dir, "cp-b").path)
        separator = LinearSeparator(failAtCall = 7)
        assertFailsWith<ProcessKilled> { modelSeparator().separate(AudioFile(original.path), model, stems, checkpoint) }
        separator = LinearSeparator()

        modelSeparator().separate(AudioFile(original.path), model, stems, checkpoint)

        for (stem in listOf(Stems::instrumental, Stems::vocalStem)) {
            val want = files.read(File(stem(expected).path))
            val got = files.read(File(stem(stems).path))
            assertEquals(48_000, got.sampleRate)
            assertContentEquals(want.interleaved, got.interleaved)
        }
        // 16 segments at 44.1 kHz; 7 were stored, segment 6 runs again for its overlap with 7.
        assertEquals(10, separator.calls)
        assertFalse(File(checkpoint.path).exists(), "checkpoint removed once the stems are stored")
    }

    @Test
    fun `stems are sample-aligned with and as long as the Original Audio`() = runTest {
        val stems = stemsIn("stems")

        modelSeparator().separate(AudioFile(original.path), model, stems, AppDirectory(File(dir, "cp").path))

        val input = files.read(original)
        val instrumental = files.read(File(stems.instrumental.path))
        val vocals = files.read(File(stems.vocalStem.path))
        assertEquals(input.frames, instrumental.frames)
        assertEquals(input.frames, vocals.frames)
        // The stems add up to the input and keep the model's 3:1 split, at the same positions.
        for (c in 0..1) {
            val x = input.channel(c)
            val inst = instrumental.channel(c)
            val voc = vocals.channel(c)
            var error = 0.0
            var energy = 0.0
            for (i in 2_000 until input.frames - 2_000) {
                val sum = inst[i] + voc[i]
                error += (sum - x[i]) * (sum - x[i])
                energy += x[i] * x[i]
                assertEquals(inst[i], 3 * voc[i], 1e-4f, "split at $i")
            }
            assertTrue(error / energy < 1e-4, "stems add up to the input (relative error ${error / energy})")
        }
    }
}
