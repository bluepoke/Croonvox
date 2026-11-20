// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core

import eu.schicht8.meavox.core.lyrics.LyricsPipeline
import eu.schicht8.meavox.core.lyrics.TrackLyrics
import eu.schicht8.meavox.core.model.SeparationModelId
import eu.schicht8.meavox.core.model.SourceId
import eu.schicht8.meavox.core.model.Track
import eu.schicht8.meavox.core.model.TrackId
import eu.schicht8.meavox.core.model.TrackPipelineState
import eu.schicht8.meavox.core.port.LyricsQuery
import eu.schicht8.meavox.core.testing.FakePipelineJobs
import eu.schicht8.meavox.core.testing.InMemoryLyricsRepository
import eu.schicht8.meavox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsPipelineTest {
    private val tracks = InMemoryTrackRepository()
    private val lyrics = InMemoryLyricsRepository()
    private val jobs = FakePipelineJobs()
    private val pipeline = LyricsPipeline(tracks, lyrics, jobs)

    private fun TestScope.startPipeline() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pipeline.run() }
    }

    private suspend fun add(key: String, state: TrackPipelineState = TrackPipelineState.Added) =
        tracks.addIfAbsent(Track(TrackId(SourceId("youtube"), key), "Song $key", null, null, state))

    @Test
    fun `a new Track's Lyrics are searched whatever its Track Pipeline is doing`() = runTest {
        startPipeline()

        val added = add("a")
        val separating = add("b", TrackPipelineState.Separating(SeparationModelId("fast")))

        assertEquals(listOf(added.id, separating.id), jobs.lyricsFetches)
        assertEquals(TrackLyrics.Searching, lyrics.get(added.id))
        assertEquals(TrackLyrics.Searching, lyrics.get(separating.id))
        assertEquals(TrackPipelineState.Added, tracks.get(added.id)?.pipeline)
    }

    @Test
    fun `at start a search left Searching is queued again once, settled Lyrics are left alone`() = runTest {
        val interrupted = add("a")
        lyrics.set(interrupted.id, TrackLyrics.Searching)
        val settled = add("b")
        lyrics.set(settled.id, TrackLyrics.NotFound(LyricsQuery(null, "Song b", null)))

        startPipeline()
        add("c").let { lyrics.set(it.id, TrackLyrics.Searching) } // later changes don't queue "a" again

        assertEquals(listOf(interrupted.id, TrackId(SourceId("youtube"), "c")), jobs.lyricsFetches)
    }
}
