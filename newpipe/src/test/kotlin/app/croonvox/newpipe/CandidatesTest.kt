// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.newpipe

import app.croonvox.core.model.SourceId
import app.croonvox.core.model.TrackId
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CandidatesTest {
    private val youtube = ServiceList.YouTube
    private val source = SourceId("youtube")

    private fun item(url: String, duration: Long, thumbnails: List<Image>) =
        StreamInfoItem(youtube.serviceId, url, "Michael Jackson - Beat It (Official Video)", StreamType.VIDEO_STREAM).apply {
            uploaderName = "Michael Jackson"
            this.duration = duration
            this.thumbnails = thumbnails
        }

    @Test
    fun `a search result becomes a candidate identified by the service's own ID`() {
        val thumbnails = listOf(
            Image("https://i.ytimg.com/vi/oRdxUFDoQe0/default.jpg", 90, 120, Image.ResolutionLevel.LOW),
            Image("https://i.ytimg.com/vi/oRdxUFDoQe0/mqdefault.jpg", 180, 320, Image.ResolutionLevel.MEDIUM),
            Image("https://i.ytimg.com/vi/oRdxUFDoQe0/maxresdefault.jpg", 720, 1280, Image.ResolutionLevel.HIGH),
        )

        val candidate = Candidates.from(item("https://www.youtube.com/watch?v=oRdxUFDoQe0", 298, thumbnails), youtube, source)

        assertEquals(TrackId(source, "oRdxUFDoQe0"), candidate.id)
        assertEquals("Michael Jackson - Beat It (Official Video)", candidate.title)
        assertEquals("Michael Jackson", candidate.artist)
        assertEquals(298_000L, candidate.durationMs)
        assertEquals("https://i.ytimg.com/vi/oRdxUFDoQe0/mqdefault.jpg", candidate.thumbnailUrl) // big enough for a list row
    }

    @Test
    fun `an unknown duration stays unknown`() {
        val candidate = Candidates.from(item("https://youtu.be/oRdxUFDoQe0", -1, emptyList()), youtube, source)

        assertNull(candidate.durationMs)
        assertNull(candidate.thumbnailUrl)
    }
}
