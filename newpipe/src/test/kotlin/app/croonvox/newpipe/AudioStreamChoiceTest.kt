// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.newpipe

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AudioStreamChoiceTest {
    private fun stream(
        id: String,
        format: MediaFormat,
        bitrate: Int,
        delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        track: AudioTrackType? = null,
    ): AudioStream = AudioStream.Builder()
        .setId(id)
        .setContent("https://media.example/$id", true)
        .setMediaFormat(format)
        .setDeliveryMethod(delivery)
        .setAverageBitrate(bitrate)
        .apply { if (track != null) setAudioTrackType(track) }
        .build()

    @Test
    fun `the best downloadable original audio is chosen`() {
        // What YouTube typically offers, plus a dubbed track and a stream only reachable over DASH.
        val streams = listOf(
            stream("139", MediaFormat.M4A, 48),
            stream("140", MediaFormat.M4A, 128),
            stream("251", MediaFormat.WEBMA_OPUS, 160),
            stream("251-dub", MediaFormat.WEBMA_OPUS, 160, track = AudioTrackType.DUBBED),
            stream("dash", MediaFormat.M4A, 256, delivery = DeliveryMethod.DASH),
        )

        assertEquals("251", AudioStreamChoice.best(streams)?.stream?.id)
    }

    @Test
    fun `the choice names the stream so a resumed download can tell it is the same file`() {
        val opus = listOf(stream("251", MediaFormat.WEBMA_OPUS, 160))
        val m4a = listOf(stream("140", MediaFormat.M4A, 128))

        assertEquals(AudioStreamChoice.best(opus)?.key, AudioStreamChoice.best(opus)?.key)
        assertNotEquals(AudioStreamChoice.best(opus)?.key, AudioStreamChoice.best(m4a)?.key)
    }

    @Test
    fun `no downloadable audio gives no choice`() {
        // SoundCloud sometimes offers HLS only.
        assertNull(AudioStreamChoice.best(listOf(stream("hls", MediaFormat.OPUS, 64, delivery = DeliveryMethod.HLS))))
    }
}
