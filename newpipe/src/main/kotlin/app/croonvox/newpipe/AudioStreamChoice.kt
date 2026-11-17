// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.newpipe

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod

/**
 * Which of a song's audio streams becomes its Original Audio (ADR 8: kept as downloaded). Only
 * plain HTTP files can be downloaded and resumed with byte ranges; of those, the original-language
 * track with the highest bitrate in a format Android decodes. [key] names that stream, so a
 * resumed download can check it is still the same file.
 */
class AudioStreamChoice private constructor(val stream: AudioStream) {
    val key: String
        get() = "${stream.format?.name}-${stream.averageBitrate}-${stream.id}"

    companion object {
        /** Formats Android's decoders read, best first when bitrates are equal. */
        private val decodable = listOf(MediaFormat.WEBMA_OPUS, MediaFormat.OPUS, MediaFormat.M4A, MediaFormat.MP3, MediaFormat.WEBMA, MediaFormat.OGG)

        fun best(streams: List<AudioStream>): AudioStreamChoice? = streams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.format in decodable }
            // Dubbed or described versions are not the song.
            .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .maxWithOrNull(compareBy<AudioStream> { it.averageBitrate }.thenByDescending { decodable.indexOf(it.format) })
            ?.let(::AudioStreamChoice)
    }
}
