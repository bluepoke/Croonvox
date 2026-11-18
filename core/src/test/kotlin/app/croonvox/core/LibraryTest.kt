// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.library.Library
import app.croonvox.core.library.LibraryEntry
import app.croonvox.core.model.OutputRoute
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.model.Transpose
import app.croonvox.core.session.Sessions
import app.croonvox.core.testing.FakeAudioEngine
import app.croonvox.core.testing.FakeSource
import app.croonvox.core.testing.InMemorySettingsRepository
import app.croonvox.core.testing.InMemoryTakeRepository
import app.croonvox.core.testing.InMemoryTrackFiles
import app.croonvox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryTest {
    private val tracks = InMemoryTrackRepository()
    private val takes = InMemoryTakeRepository()
    private val library = Library(tracks, takes)
    private val source = FakeSource(SourceId("fake")).apply {
        add(sourceTrackId = "v1", title = "Fly Me to the Moon", artist = "Frank Sinatra", durationMs = 148_000)
    }

    @Test
    fun `adding a search result keeps the Separation Model chosen for it`() = runTest {
        val best = SeparationModelId("best")

        val track = library.add(source.search("fly me").single(), separationModel = best)

        assertEquals(best, tracks.get(track.id)?.separationModel)
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

    @Test
    fun `each entry shows the Track Pipeline state, its Take count and when it was last sung`() = runTest {
        val sung = library.add(source.search("fly me").single())
        val other = library.add(source.add(sourceTrackId = "v2", title = "My Way", artist = "Frank Sinatra", durationMs = 275_000))
        tracks.updatePipeline(sung.id, TrackPipelineState.Ready(SeparationModelId("fast")))
        takes.add(take("t1", sung.id))
        takes.add(take("t2", sung.id))
        val files = InMemoryTrackFiles()
        val sessions = Sessions(tracks, takes, files, FakeAudioEngine(), InMemorySettingsRepository(), clock = { SUNG_AT })

        sessions.open(sung.id, backgroundScope).apply {
            record() // sung: a Take started recording
            stop()
            discard()
            close()
        }

        val entries = library.entries().first().associateBy { it.track.id }
        assertEquals(LibraryEntry(tracks.get(sung.id)!!, takeCount = 2), entries[sung.id])
        assertEquals(TrackPipelineState.Ready(SeparationModelId("fast")), entries[sung.id]?.track?.pipeline)
        assertEquals(SUNG_AT, entries[sung.id]?.track?.lastSessionAt)
        assertEquals(0, entries[other.id]?.takeCount)
        assertEquals(null, entries[other.id]?.track?.lastSessionAt)
    }

    private fun take(id: String, track: TrackId) =
        Take(TakeId(id), track, id, 0, 48_000, 48_000, OutputRoute.Wired, 0, Transpose.None, recordedAt = 0)

    private companion object {
        const val SUNG_AT = 1_700_000_000_000L
    }
}
