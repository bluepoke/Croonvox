// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.separation.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import eu.schicht8.meavox.spike.separation.audio.RawStereoFile
import eu.schicht8.meavox.spike.separation.audio.RawStereoWriter
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes the first audio track of a file (audio or video container) to stereo float at its own
 * sample rate, streamed into a temp file so a long song never sits in memory. Mono is duplicated;
 * more than two channels keep the first two.
 */
object SongDecoder {
    /** Blocking; call off the main thread. */
    fun decode(context: Context, uri: Uri, into: File, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): RawStereoFile {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return Loop(extractor, codec, into, durationUs, onProgress, isCancelled).run()
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private class Loop(
        private val extractor: MediaExtractor,
        private val codec: MediaCodec,
        private val into: File,
        private val durationUs: Long,
        private val onProgress: (Float) -> Unit,
        private val isCancelled: () -> Boolean,
    ) {
        private val info = MediaCodec.BufferInfo()
        private var writer: RawStereoWriter? = null
        private var channels = 1
        private var floatPcm = false
        private var interleaved = FloatArray(0)
        private var left = FloatArray(0)
        private var right = FloatArray(0)
        private var inputDone = false
        private var outputDone = false

        fun run(): RawStereoFile {
            while (!outputDone) {
                if (isCancelled()) throw InterruptedException("decode cancelled")
                feed()
                drain()
            }
            return (writer ?: error("decoder produced no audio")).finish()
        }

        private fun feed() {
            while (!inputDone) {
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) return
                val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                if (size < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }

        private fun drain() {
            var wait = 2_000L
            while (!outputDone) {
                val index = codec.dequeueOutputBuffer(info, wait)
                wait = 0
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> begin(codec.outputFormat)
                    index >= 0 -> handle(index)
                }
            }
        }

        private fun begin(format: MediaFormat) {
            if (writer != null) return
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            writer = RawStereoWriter(into, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        }

        private fun handle(index: Int) {
            begin(codec.outputFormat)
            val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val samples = if (floatPcm) info.size / 4 else info.size / 2
            if (interleaved.size < samples) interleaved = FloatArray(samples)
            if (floatPcm) {
                buffer.asFloatBuffer().get(interleaved, 0, samples)
            } else {
                val shorts = buffer.asShortBuffer()
                for (i in 0 until samples) interleaved[i] = shorts.get(i) / 32768f
            }
            val frames = samples / channels
            if (left.size < frames) {
                left = FloatArray(frames)
                right = FloatArray(frames)
            }
            for (f in 0 until frames) {
                left[f] = interleaved[f * channels]
                right[f] = interleaved[f * channels + if (channels > 1) 1 else 0]
            }
            if (frames > 0) writer!!.write(left, right, frames)
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
        }
    }
}
