// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.library.AddSharedLink
import app.croonvox.core.library.Library
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.testing.FakeSource
import app.croonvox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddSharedLinkTest {
    private val tracks = InMemoryTrackRepository()
    private val library = Library(tracks)
    private val youtube = FakeSource(SourceId("youtube"))
    private val soundcloud = FakeSource(SourceId("soundcloud"))
    private val addLink = AddSharedLink(listOf(youtube, soundcloud), library)

    @Test
    fun `a shared link adds the item of the Source it belongs to, once`() = runTest {
        val song = soundcloud.add("rolling", "Rolling in the Deep", artist = "Adele", durationMs = 228_000)

        val track = addLink(soundcloud.urlOf("rolling"))
        val again = addLink(soundcloud.urlOf("rolling"))

        assertEquals(song.id, track?.id)
        assertEquals("Rolling in the Deep", track?.title)
        assertEquals(TrackPipelineState.Added, track?.pipeline)
        assertEquals(track, again)
        assertEquals(listOf(track), library.tracks().first())
    }

    @Test
    fun `a link no Source knows adds nothing`() = runTest {
        assertNull(addLink("https://example.com/some/page"))
        assertEquals(emptyList(), library.tracks().first())
    }
}
