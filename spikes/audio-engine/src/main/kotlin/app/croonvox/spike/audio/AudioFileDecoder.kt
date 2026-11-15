// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decodes the first audio track of a file into the engine's Backing Audio, chunk by chunk. */
object AudioFileDecoder {

    /** Blocking; call off the main thread. [onProgress] receives 0..1. */
    fun decodeIntoEngine(context: Context, uri: Uri, onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track in file")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                decodeLoop(extractor, codec, durationUs, onProgress)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun decodeLoop(extractor: MediaExtractor, codec: MediaCodec, durationUs: Long, onProgress: (Float) -> Unit) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var begun = false
        var channels = 1
        var floatPcm = false
        var scratch = FloatArray(0)

        fun begin(outputFormat: MediaFormat) {
            channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            floatPcm = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            NativeEngine.beginBacking(channels, outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            begun = true
        }

        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
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
            val index = codec.dequeueOutputBuffer(info, 10_000)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (!begun) begin(codec.outputFormat)
                index >= 0 -> {
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
                    if (samples > 0) NativeEngine.appendBacking(scratch, samples / channels)
                    if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        if (!begun) error("Decoder produced no audio")
        NativeEngine.endBacking()
        onProgress(1f)
    }
}
