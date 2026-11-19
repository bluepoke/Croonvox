// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.Transpose
import app.croonvox.core.port.MixRender
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Seam 2 (spec #1): the Offline Render of a Mix and of a Backing Export on synthetic Backing Audio and Takes (impulses,
 * sine tones), stored as FLAC like stems and Takes, asserted on the rendered PCM.
 */
@RunWith(AndroidJUnit4::class)
class NativeOfflineRendererTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "mix-render-test").apply { mkdirs() }
    private val renderer = NativeOfflineRenderer()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun flac(name: String, channels: Int, frames: Int, sample: (frame: Int, channel: Int) -> Float): AudioFile {
        val file = File(dir, "$name.flac")
        FlacFiles.writer(file, channels, RATE).use { writer ->
            val block = FloatArray(4096 * channels)
            var done = 0
            while (done < frames) {
                val n = minOf(4096, frames - done)
                for (i in 0 until n) for (c in 0 until channels) block[i * channels + c] = sample(done + i, c)
                writer.write(block, n)
                done += n
            }
            writer.finish()
        }
        return AudioFile(file.path)
    }

    private fun silentBacking(seconds: Int) = flac("backing", 2, seconds * RATE) { _, _ -> 0f }

    /** A mono Take that is silent except for [value] at [at]. */
    private fun impulseTake(frames: Int, at: Int, value: Float = 0.5f) = flac("take", 1, frames) { i, _ -> if (i == at) value else 0f }

    private fun render(
        backing: AudioFile,
        take: AudioFile,
        from: Long,
        to: Long,
        takeStart: Long = from,
        alignment: Long = 0,
        transpose: Transpose = Transpose.None,
        backingDb: Float = 0f,
        takeDb: Float = 0f,
    ): FloatArray {
        val out = ArrayList<Float>()
        runBlocking {
            renderer.render(MixRender(backing, take, RATE, takeStart, alignment, transpose, backingDb, takeDb, from, to)) { interleaved, frames ->
                for (i in 0 until frames * 2) out += interleaved[i]
            }
        }
        return out.toFloatArray()
    }

    private fun renderBacking(backing: AudioFile, transpose: Transpose): Pair<FloatArray, Long> {
        val out = ArrayList<Float>()
        val frames = runBlocking {
            renderer.renderBacking(backing, transpose) { interleaved, n -> for (i in 0 until n * 2) out += interleaved[i] }
        }
        return out.toFloatArray() to frames
    }

    @Test
    fun aBackingExportKeepsTheLengthOfTheBackingAudioAndShiftsATestToneByTheExpectedRatio() {
        val length = 5 * RATE + 123
        val backing = flac("backing", 2, length) { i, _ -> 0.4f * tone(440.0, i) }
        assertEquals(RATE, runBlocking { renderer.sampleRate(backing) })
        for (semitones in listOf(-6, -1, 2, 6)) {
            val (out, frames) = renderBacking(backing, Transpose(semitones))

            assertEquals(length.toLong(), frames)
            assertEquals(length, out.frames())
            val expected = 440.0 * 2.0.pow(semitones / 12.0)
            val measured = frequency(out, from = RATE / 2, to = length - RATE / 2)
            assertTrue("Transpose $semitones: $measured Hz, expected $expected Hz", abs(measured / expected - 1) < 0.01)
        }
    }

    @Test
    fun aBackingExportAtTransposeZeroIsTransparent() {
        val length = 3 * RATE + 7
        val backing = flac("backing", 2, length) { i, c -> if (c == 0) 0.3f * tone(440.0, i) else 0.2f * tone(330.0, i) }

        val (out, _) = renderBacking(backing, Transpose.None)

        assertEquals(length, out.frames())
        for (i in 0 until length) {
            assertEquals(0.3f * tone(440.0, i), out[2 * i], 1e-4f)
            assertEquals(0.2f * tone(330.0, i), out[2 * i + 1], 1e-4f)
        }
    }

    private fun FloatArray.left(frame: Int) = this[frame * 2]

    private fun FloatArray.frames() = size / 2

    private fun FloatArray.loudestFrame(): Int = (0 until frames()).maxBy { abs(left(it)) }

    @Test
    fun aTakeImpulseLandsAtItsStartPositionMinusTheCalibrationOffsetPlusTheNudge() {
        // Sung from 1 s; the engine's estimate is 100 ms (4 800 frames) and the Nudge +25 ms (1 200 frames).
        val take = impulseTake(frames = RATE, at = 10_000)
        val alignment = 4_800L - 1_200L

        val out = render(silentBacking(4), take, from = 48_000, to = 48_000 + RATE - alignment, alignment = alignment)

        assertEquals(RATE - 3_600, out.frames())
        // Take sample 10 000 sounds at Start Position + 10 000 − 4 800 + 1 200.
        assertEquals(6_400, out.loudestFrame())
        assertEquals(0.5f, out.left(6_400), 1e-3f)
        assertEquals(0.5f, out[6_400 * 2 + 1], 1e-3f)
    }

    @Test
    fun aTakeStaysAlignedWhenTheBackingIsTransposed() {
        val take = impulseTake(frames = RATE, at = 10_000)

        val out = render(silentBacking(4), take, from = 96_000, to = 96_000 + RATE - 3_000L, alignment = 3_000, transpose = Transpose(3))

        assertEquals(7_000, out.loudestFrame())
    }

    @Test
    fun transposedBackingStaysAlignedWithTheTimeline() {
        // A short burst in the Backing Audio, 30 000 frames after where the render starts.
        val from = 48_000
        val burst = from + 30_000
        val backing = flac("backing", 2, 6 * RATE) { i, _ -> if (i in burst until burst + 64) sin(PI * (i - burst) / 64).toFloat() else 0f }
        val silentTake = impulseTake(frames = 10, at = 0, value = 0f)

        val untransposed = render(backing, silentTake, from = from.toLong(), to = (from + RATE).toLong(), takeStart = 0)
        val transposed = render(backing, silentTake, from = from.toLong(), to = (from + RATE).toLong(), takeStart = 0, transpose = Transpose(2))

        // The stretcher reads ahead and is primed, so the burst's energy stays within 1 ms of where it was.
        assertEquals(energyCentre(untransposed, 20_000, 40_000), energyCentre(transposed, 20_000, 40_000), 48.0)
    }

    @Test
    fun theBackingAndTheTakeAreScaledByTheirLevels() {
        val backing = flac("backing", 2, 2 * RATE) { _, _ -> 0.5f }
        val take = flac("take", 1, RATE) { _, _ -> 0.25f }

        val out = render(backing, take, from = 0, to = RATE.toLong(), backingDb = -6f, takeDb = 3f)

        val expected = 0.5f * 10f.pow(-6f / 20f) + 0.25f * 10f.pow(3f / 20f) // 0.2506 + 0.3531
        assertEquals(expected, out.left(10_000), 1e-3f)
        assertEquals(expected, out[10_000 * 2 + 1], 1e-3f)
    }

    @Test
    fun transposeZeroIsTransparent() {
        val backing = flac("backing", 2, 3 * RATE) { i, c -> if (c == 0) 0.3f * tone(440.0, i) else 0.2f * tone(330.0, i) }
        val silentTake = impulseTake(frames = 10, at = 0, value = 0f)
        val from = 12_345

        val out = render(backing, silentTake, from = from.toLong(), to = (from + RATE).toLong(), takeStart = 0)

        assertEquals(RATE, out.frames())
        for (i in 0 until RATE) {
            // Within one step of the decoder's resolution (it may hand out 16-bit samples).
            assertEquals(0.3f * tone(440.0, from + i), out[2 * i], 1e-4f)
            assertEquals(0.2f * tone(330.0, from + i), out[2 * i + 1], 1e-4f)
        }
    }

    @Test
    fun transposeKeepsTheLengthAndShiftsATestToneByTheExpectedRatio() {
        val backing = flac("backing", 2, 6 * RATE) { i, _ -> 0.4f * tone(440.0, i) }
        val silentTake = impulseTake(frames = 10, at = 0, value = 0f)
        for (semitones in listOf(-6, -1, 2, 6)) {
            val length = 3 * RATE

            val out = render(backing, silentTake, from = RATE.toLong(), to = (RATE + length).toLong(), takeStart = 0, transpose = Transpose(semitones))

            assertEquals(length, out.frames())
            val expected = 440.0 * 2.0.pow(semitones / 12.0)
            val measured = frequency(out, from = RATE / 2, to = length - RATE / 2)
            assertTrue("Transpose $semitones: $measured Hz, expected $expected Hz", abs(measured / expected - 1) < 0.01)
        }
    }

    @Test
    fun theTakeRunsOnInSilencePastTheEndOfTheBacking() {
        val backing = flac("backing", 2, RATE) { _, _ -> 0.5f }
        val take = flac("take", 1, RATE) { _, _ -> 0.1f }

        val out = render(backing, take, from = 0, to = 2L * RATE, takeStart = RATE / 2L)

        assertEquals(2 * RATE, out.frames())
        assertEquals(0.6f, out.left(RATE / 2 + 100), 1e-3f)
        assertEquals(0.1f, out.left(RATE + RATE / 4), 1e-3f)
        assertEquals(0f, out.left(RATE * 3 / 2 + 100), 1e-6f)
    }

    private companion object {
        const val RATE = 48_000

        fun tone(hz: Double, frame: Int): Float = sin(2 * PI * hz * frame / RATE).toFloat()

        /** Energy-weighted mean frame of the left channel between [from] and [to]. */
        fun energyCentre(out: FloatArray, from: Int, to: Int): Double {
            var energy = 0.0
            var weighted = 0.0
            for (i in from until to) {
                val e = out[i * 2].toDouble() * out[i * 2]
                energy += e
                weighted += e * i
            }
            return weighted / energy
        }

        /** Frequency of the left channel from its rising zero crossings, interpolated. */
        fun frequency(out: FloatArray, from: Int, to: Int): Double {
            var first = -1.0
            var last = -1.0
            var cycles = 0
            for (i in from + 1 until to) {
                val a = out[(i - 1) * 2]
                val b = out[i * 2]
                if (a < 0 && b >= 0) {
                    val t = i - 1 + a / (a - b).toDouble()
                    if (first < 0) first = t else cycles++
                    last = t
                }
            }
            return cycles / (last - first) * RATE
        }
    }
}
