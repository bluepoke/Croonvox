// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.engine

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
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
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
        private var begun = false
        private var channels = 1
        private var floatPcm = false
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
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (!begun) begin(codec.outputFormat)
                    index >= 0 -> {
                        handle(index)
                        drained = true
                    }
                }
            }
            return drained
        }

        private fun handle(index: Int) {
            if (!begun) begin(codec.outputFormat)
            val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val samples = if (floatPcm) info.size / 4 else info.size / 2
            if (scratch.size < samples) scratch = FloatArray(samples)
            if (floatPcm) {
                buffer.asFloatBuffer().get(scratch, 0, samples)
            } else {
                val shorts = buffer.asShortBuffer()
                for (i in 0 until samples) scratch[i] = shorts.get(i) / 32768f
            }
            if (samples >= channels) sink.append(scratch, samples / channels)
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            reportProgress()
        }

        private fun begin(outputFormat: MediaFormat) {
            channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            floatPcm = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            val rate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            sink.begin(channels, rate, durationUs * rate / 1_000_000)
            begun = true
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
