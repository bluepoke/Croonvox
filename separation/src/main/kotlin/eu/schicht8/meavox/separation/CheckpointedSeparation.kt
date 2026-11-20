// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.separation

import eu.schicht8.meavox.separation.audio.FlacCodec
import eu.schicht8.meavox.separation.audio.FrameSink
import eu.schicht8.meavox.separation.audio.PcmDecoder
import eu.schicht8.meavox.separation.audio.PcmStream
import eu.schicht8.meavox.separation.audio.StreamingResampler
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * One Separation of a file, checkpointed per segment (ADR 15). Blocking.
 *
 * 1. The Original Audio is decoded, made stereo and resampled to the model's rate, then run
 *    through [SegmentedSeparation]. Each finished segment is stored in [checkpoint] as a 4-channel
 *    FLAC (Instrumental L, R, Vocal Stem L, R). A run that finds stored segments decodes the input
 *    again but only separates from the first missing segment.
 * 2. Once every segment is stored, the segments are resampled back to the Original Audio's rate
 *    and written as the two stereo FLAC stems, exactly as long as the Original Audio.
 *
 * The stems are written next to their final names and renamed into place; then [checkpoint] is
 * removed. Killing the process at any point loses at most one segment's work.
 */
internal class CheckpointedSeparation(
    private val original: File,
    private val model: SeparationModel,
    private val modelDir: File,
    private val instrumental: File,
    private val vocalStem: File,
    private val checkpoint: File,
    private val decoder: PcmDecoder,
    private val flac: FlacCodec,
    /** Fraction of the whole Separation done, 0..1. */
    private val onProgress: (Float) -> Unit,
) {
    /** What step 1 found out about the Original Audio; stored once every segment is. */
    private class Separated(val frames: Long, val sampleRate: Int, val modelRate: Int, val segments: Int)

    fun run() {
        // A run that died after renaming both stems but before removing the checkpoint.
        if (!checkpoint.exists() && instrumental.isFile && vocalStem.isFile) return
        val separated = readSeparated() ?: separateSegments()
        writeStems(separated)
        checkpoint.deleteRecursively()
        onProgress(1f)
    }

    private fun separateSegments(): Separated = model.open(modelDir).use { separator ->
        val overlap = 0.25f
        val run = Properties().apply {
            setProperty("format", FORMAT)
            setProperty("model", model.id.value)
            setProperty("segmentFrames", separator.segmentFrames.toString())
            setProperty("sampleRate", separator.sampleRate.toString())
            setProperty("overlap", overlap.toString())
        }
        val runFile = File(checkpoint, RUN)
        if (load(runFile) != run) checkpoint.deleteRecursively() // another model or layout: start over
        checkpoint.mkdirs()
        if (!runFile.exists()) store(run, runFile)
        checkpoint.listFiles { f -> f.name.endsWith(PART) }?.forEach { it.delete() }
        var first = 0
        while (segmentFile(first).isFile) first++

        var expectedSegments = 0
        val segmented = SegmentedSeparation(
            separator,
            out = { index, interleaved, frames ->
                storeAtomically(segmentFile(index)) { part ->
                    flac.writer(part, 4, separator.sampleRate).use {
                        it.write(interleaved, frames)
                        it.finish()
                    }
                }
                if (expectedSegments > 0) onProgress(SEPARATING_SHARE * minOf(1f, (index + 1f) / expectedSegments))
            },
            firstSegment = first,
            overlap = overlap,
        )
        var resampler: StreamingResampler? = null
        var inputRate = 0
        var inputChannels = 0
        var inputFrames = 0L
        var stereo = FloatArray(0)
        decoder.decode(original, object : PcmStream {
            override fun begin(channelCount: Int, sampleRate: Int, estimatedFrames: Long) {
                require(channelCount >= 1) { "no audio channels" }
                inputChannels = channelCount
                inputRate = sampleRate
                resampler = StreamingResampler(sampleRate, separator.sampleRate, 2, FrameSink(segmented::write))
                expectedSegments = SegmentedSeparation.segmentCount(
                    StreamingResampler.framesAt(estimatedFrames, sampleRate, separator.sampleRate),
                    segmented.hop,
                )
                if (expectedSegments > 0) onProgress(SEPARATING_SHARE * minOf(1f, first.toFloat() / expectedSegments))
            }

            override fun write(interleaved: FloatArray, frames: Int) {
                if (stereo.size < 2 * frames) stereo = FloatArray(2 * frames)
                // Mono plays on both sides; beyond stereo, the front left and right are kept.
                for (i in 0 until frames) {
                    val l = interleaved[i * inputChannels]
                    stereo[2 * i] = l
                    stereo[2 * i + 1] = if (inputChannels == 1) l else interleaved[i * inputChannels + 1]
                }
                checkNotNull(resampler).write(stereo, frames)
                inputFrames += frames
            }
        })
        if (inputFrames == 0L) throw IOException("no audio in ${original.name}")
        val modelFrames = StreamingResampler.framesAt(inputFrames, inputRate, separator.sampleRate)
        checkNotNull(resampler).finish(modelFrames)
        segmented.finish(modelFrames)
        val separated = Separated(inputFrames, inputRate, separator.sampleRate, SegmentedSeparation.segmentCount(modelFrames, segmented.hop))
        store(
            Properties().apply {
                setProperty("frames", separated.frames.toString())
                setProperty("sampleRate", separated.sampleRate.toString())
                setProperty("modelRate", separated.modelRate.toString())
                setProperty("segments", separated.segments.toString())
            },
            File(checkpoint, SEPARATED),
        )
        separated
    }

    private fun writeStems(separated: Separated) {
        instrumental.parentFile?.mkdirs()
        vocalStem.parentFile?.mkdirs()
        val instrumentalPart = File(instrumental.path + PART)
        val vocalStemPart = File(vocalStem.path + PART)
        flac.writer(instrumentalPart, 2, separated.sampleRate).use { inst ->
            flac.writer(vocalStemPart, 2, separated.sampleRate).use { voc ->
                val a = FloatArray(BLOCK * 2)
                val b = FloatArray(BLOCK * 2)
                val split = FrameSink { interleaved, frames ->
                    var done = 0
                    while (done < frames) {
                        val n = minOf(BLOCK, frames - done)
                        for (i in 0 until n) {
                            val s = 4 * (done + i)
                            a[2 * i] = interleaved[s]
                            a[2 * i + 1] = interleaved[s + 1]
                            b[2 * i] = interleaved[s + 2]
                            b[2 * i + 1] = interleaved[s + 3]
                        }
                        inst.write(a, n)
                        voc.write(b, n)
                        done += n
                    }
                }
                val back = StreamingResampler(separated.modelRate, separated.sampleRate, 4, split)
                for (index in 0 until separated.segments) {
                    if (Thread.interrupted()) throw InterruptedException("Separation cancelled")
                    flac.read(segmentFile(index), object : PcmStream {
                        override fun begin(channelCount: Int, sampleRate: Int, estimatedFrames: Long) {
                            if (channelCount != 4) throw IOException("segment $index has $channelCount channels")
                        }

                        override fun write(interleaved: FloatArray, frames: Int) = back.write(interleaved, frames)
                    })
                    onProgress(SEPARATING_SHARE + (1 - SEPARATING_SHARE) * (index + 1f) / separated.segments)
                }
                back.finish(separated.frames)
                inst.finish()
                voc.finish()
            }
        }
        rename(instrumentalPart, instrumental)
        rename(vocalStemPart, vocalStem)
    }

    private fun readSeparated(): Separated? {
        val p = load(File(checkpoint, SEPARATED)) ?: return null
        return Separated(
            frames = p.getProperty("frames").toLong(),
            sampleRate = p.getProperty("sampleRate").toInt(),
            modelRate = p.getProperty("modelRate").toInt(),
            segments = p.getProperty("segments").toInt(),
        ).takeIf { s -> (0 until s.segments).all { segmentFile(it).isFile } }
    }

    private fun segmentFile(index: Int) = File(checkpoint, "segment-%06d.flac".format(index))

    private fun storeAtomically(file: File, write: (File) -> Unit) {
        val part = File(file.path + PART)
        write(part)
        rename(part, file)
    }

    private fun store(properties: Properties, file: File) = storeAtomically(file) { part ->
        part.outputStream().use { properties.store(it, null) }
    }

    private fun load(file: File): Properties? =
        if (file.isFile) Properties().apply { file.inputStream().use { load(it) } } else null

    private fun rename(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("could not move $from to $to")
    }

    private companion object {
        const val FORMAT = "1"
        const val RUN = "run.properties"
        const val SEPARATED = "separated.properties"
        const val PART = ".part"
        const val BLOCK = 4096

        /** Share of the progress for step 1; writing the stems takes the rest. */
        const val SEPARATING_SHARE = 0.95f
    }
}
