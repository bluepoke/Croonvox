// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.lyrics.LyricsPipeline
import app.croonvox.core.lyrics.TrackLyrics
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.port.LyricsQuery
import app.croonvox.core.testing.FakePipelineJobs
import app.croonvox.core.testing.InMemoryLyricsRepository
import app.croonvox.core.testing.InMemoryTrackRepository
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
