// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation

import app.croonvox.core.model.AppDirectory
import app.croonvox.core.model.AudioFile
import app.croonvox.core.port.Stems
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Separator contract with the real Fast model on ONNX Runtime: a short clip yields stems that are
 * as long as and sample-aligned with it. The model is downloaded once into the Gradle user home
 * (`caches/croonvox-models`), checksum-verified like on the device.
 */
class FastModelContractTest {
    @TempDir
    lateinit var dir: File

    private val files = RawPcmFiles()
    private val cache = File(checkNotNull(System.getProperty("modelCache")) { "run through Gradle" })

    /** 15 s at 48 kHz: a vibrato "voice" in the centre over a bass, a pad and noise hits on the sides. */
    private fun clip(): RawPcmFiles.Audio {
        val rate = 48_000
        val frames = 15 * rate + 123
        val random = java.util.Random(11)
        val samples = FloatArray(2 * frames)
        var hit = 0f
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val pitch = 220 * (1 + 0.5 * ((t * 0.5).toInt() % 3)) * (1 + 0.01 * sin(2 * PI * 5.5 * t))
            var voice = 0.0
            for (h in 1..8) voice += sin(2 * PI * h * pitch * t) / h
            voice *= 0.15 * (0.6 + 0.4 * sin(2 * PI * 0.7 * t))
            val bass = 0.2 * sin(2 * PI * 55 * t)
            val pad = 0.08 * (sin(2 * PI * 330 * t) + sin(2 * PI * 415 * t))
            if (i % (rate / 2) == 0) hit = 1f
            hit *= 0.9995f
            val noise = 0.1f * hit * random.nextGaussian().toFloat()
            samples[2 * i] = (voice + bass + pad).toFloat() + noise
            samples[2 * i + 1] = (voice + bass - pad).toFloat() - noise
        }
        return RawPcmFiles.Audio(2, rate, samples)
    }

    @Test
    fun `the Fast model separates a clip into stems as long as and aligned with it`() = runTest(timeout = 10.minutes) {
        val input = clip()
        val original = File(dir, "original").also { files.write(it, input) }
        val stems = Stems(AudioFile(File(dir, "instrumental").path), AudioFile(File(dir, "vocals").path))
        val separator = ModelSeparator(SeparationCatalogue.models, ModelStore(cache), files, files)

        separator.separate(AudioFile(original.path), SeparationCatalogue.default.id, stems, AppDirectory(File(dir, "checkpoint").path))

        val instrumental = files.read(File(stems.instrumental.path))
        val vocals = files.read(File(stems.vocalStem.path))
        for (stem in listOf(instrumental, vocals)) {
            assertEquals(input.sampleRate, stem.sampleRate)
            assertEquals(input.frames, stem.frames)
        }
        val inputMono = mono(input)
        val instrumentalMono = mono(instrumental)
        val vocalsMono = mono(vocals)
        assertTrue(rms(vocalsMono) > 0.05 * rms(inputMono), "the Vocal Stem carries the voice")
        assertTrue(rms(instrumentalMono) > 0.05 * rms(inputMono), "the Instrumental carries the band")
        assertEquals(0, assertNotNull(lag(inputMono, instrumentalMono)), "Instrumental lag")
        assertEquals(0, assertNotNull(lag(inputMono, vocalsMono)), "Vocal Stem lag")
    }

    private fun mono(audio: RawPcmFiles.Audio) = FloatArray(audio.frames) { audio.interleaved[2 * it] + audio.interleaved[2 * it + 1] }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    /** Shift (positive: the stem is late) at which [stem] best correlates with [reference], within ±1024 frames. */
    private fun lag(reference: FloatArray, stem: FloatArray, maxLag: Int = 1024): Int? {
        val excerpt = 48_000
        val start = (reference.size - excerpt) / 2
        var best: Int? = null
        var bestValue = 0.0
        for (lag in -maxLag..maxLag) {
            var sum = 0.0
            for (i in start until start + excerpt) sum += reference[i] * stem[i + lag]
            if (sum > bestValue) {
                bestValue = sum
                best = lag
            }
        }
        return best
    }
}
