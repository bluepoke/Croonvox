// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.library.Library
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.testing.FakeSource
import app.croonvox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryTest {
    private val library = Library(InMemoryTrackRepository())
    private val source = FakeSource(SourceId("fake")).apply {
        add(sourceTrackId = "v1", title = "Fly Me to the Moon", artist = "Frank Sinatra", durationMs = 148_000)
    }

    @Test
    fun `a new Library has no Tracks`() = runTest {
        assertEquals(emptyList(), library.tracks().first())
    }

    @Test
    fun `adding a search result puts it into the Library as an Added Track`() = runTest {
        val candidate = source.search("fly me").single()

        library.add(candidate)

        val track = library.tracks().first().single()
        assertEquals("Fly Me to the Moon", track.title)
        assertEquals("Frank Sinatra", track.artist)
        assertEquals(148_000, track.durationMs)
        assertEquals(TrackPipelineState.Added, track.pipeline)
    }

    @Test
    fun `adding the same Source item again returns the existing Track instead of a duplicate`() = runTest {
        val candidate = source.search("sinatra").single()
        val first = library.add(candidate)

        val second = library.add(candidate.copy(title = "Fly Me to the Moon (Remastered)"))

        assertEquals(first, second)
        assertEquals(listOf(first), library.tracks().first())
    }
}
