// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * Decodes the first audio track of a file (audio or video container) to float PCM, chunk by chunk.
 * Used for playback and, by the same code path, for Separation, so stems line up with what plays.
 */
object AudioDecoder {
    interface Sink {
        /** [estimatedFrames] is derived from the container's duration, 0 if unknown. */
        fun begin(channels: Int, sampleRate: Int, estimatedFrames: Long)

        /** [interleaved] holds `frames * channels` valid samples; it is reused after the call. */
        fun append(interleaved: FloatArray, frames: Int)

        /** Fraction of the file decoded so far, 0..1 (only when the duration is known). */
        fun progress(fraction: Float) {}
    }

    /**
     * The PCM encoding to ask the decoder of a [mime] track for; null to leave the track's own.
     * A raw PCM track (on Android 17, MediaExtractor decodes FLAC itself and hands out 16-bit
     * "audio/raw") goes through a pass-through decoder, for which the encoding describes its
     * input: asking it for float would declare the samples float without converting them.
     */
    internal fun requestedPcmEncoding(mime: String): Int? =
        if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) null else AudioFormat.ENCODING_PCM_FLOAT

    /** Blocking; call off the main thread. Interrupting the thread cancels the decode. */
    fun decode(path: String, sink: Sink) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudioException("no audio track in $path")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            requestedPcmEncoding(format.getString(MediaFormat.KEY_MIME)!!)?.let { format.setInteger(MediaFormat.KEY_PCM_ENCODING, it) }
            val codec = try {
                MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            } catch (e: Exception) {
                throw UnsupportedAudioException("no decoder for ${format.getString(MediaFormat.KEY_MIME)}", e)
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                Decoding(extractor, codec, sink, durationUs).run()
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /**
     * Synchronous MediaCodec loop that never sleeps while there is work: it fills every free
     * input buffer and drains every ready output buffer, and only blocks briefly when neither
     * side made progress. (Waiting on each output after each single input made a 4-minute song
     * take about a minute.)
     */
    private class Decoding(
        private val extractor: MediaExtractor,
        private val codec: MediaCodec,
        private val sink: Sink,
        private val durationUs: Long,
    ) {
        private val info = MediaCodec.BufferInfo()
        private var inputDone = false
        private var outputDone = false
        private val layout = PcmLayout()
        private var begun = false
        private var scratch = FloatArray(0)
        private var lastProgress = -1f

        fun run() {
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("decode cancelled")
                val fed = feedInput()
                val drained = drainOutput(timeoutUs = 0)
                if (!fed && !drained) drainOutput(timeoutUs = WAIT_US)
            }
            if (!begun) throw UnsupportedAudioException("decoder produced no audio")
        }

        /** Queues compressed samples into every free input buffer. Returns whether any was queued. */
        private fun feedInput(): Boolean {
            var fed = false
            while (!inputDone) {
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) break
                val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                if (size < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
                fed = true
            }
            return fed
        }

        /** Hands every ready output buffer to the sink. Returns whether any was handled. */
        private fun drainOutput(timeoutUs: Long): Boolean {
            var drained = false
            var wait = timeoutUs
            while (!outputDone) {
                val index = codec.dequeueOutputBuffer(info, wait)
                wait = 0
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return drained
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                    index >= 0 -> {
                        handle(index)
                        drained = true
                    }
                }
            }
            return drained
        }

        private fun handle(index: Int) {
            if (!layout.known) onFormat(codec.outputFormat)
            // The sink begins with the first buffer, under the format that buffer has: a decoder may
            // report more than one format before (see [PcmLayout]).
            if (!begun) {
                sink.begin(layout.channels, layout.sampleRate, durationUs * layout.sampleRate / 1_000_000)
                begun = true
            }
            val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val needed = layout.samplesIn(info.size)
            if (scratch.size < needed) scratch = FloatArray(needed)
            val samples = layout.read(buffer, scratch)
            if (samples >= layout.channels) sink.append(scratch, samples / layout.channels)
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            reportProgress()
        }

        /** Every output format counts, also after the first (see [PcmLayout]). */
        private fun onFormat(outputFormat: MediaFormat) {
            val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else null
            layout.onFormat(
                encoding,
                outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            )
        }

        private fun reportProgress() {
            if (durationUs <= 0) return
            val fraction = if (outputDone) 1f else (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
            if (fraction - lastProgress >= 0.01f || outputDone) {
                lastProgress = fraction
                sink.progress(fraction)
            }
        }

        private companion object {
            const val WAIT_US = 2_000L
        }
    }
}

/** The file has no audio the device can decode. */
class UnsupportedAudioException(message: String, cause: Throwable? = null) : Exception(message, cause)
