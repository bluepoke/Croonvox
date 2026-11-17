// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.library.ImportLocalFile
import app.croonvox.core.model.PipelineStep
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.pipeline.TrackPipeline
import app.croonvox.core.player.TrackPlayer
import app.croonvox.core.session.BackingAudio
import app.croonvox.core.session.Sessions
import app.croonvox.core.testing.FakeAudioEngine
import app.croonvox.core.testing.InMemorySettingsRepository
import app.croonvox.core.testing.InMemoryTakeRepository
import app.croonvox.core.port.SeparationProgress
import app.croonvox.core.testing.FakeLocalFile
import app.croonvox.core.testing.FakePipelineJobs
import app.croonvox.core.testing.InMemoryTrackFiles
import app.croonvox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TrackPipelineTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val jobs = FakePipelineJobs()
    private val import = ImportLocalFile(tracks, files)
    private val fast = SeparationModelId("fast")
    private val youtube = SourceId("youtube")
    private val pipeline = TrackPipeline(tracks, jobs, defaultModel = fast, downloadFrom = setOf(youtube))

    private fun TestScope.startPipeline() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pipeline.run() }
    }

    @Test
    fun `a Downloaded Track is queued for Separation with the default model`() = runTest {
        startPipeline()

        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))

        assertEquals(listOf(FakePipelineJobs.Separation(track.id, fast)), jobs.separations)
        assertEquals(TrackPipelineState.Separating(fast), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `at start a Track left Separating by an earlier process is queued again once with its model`() = runTest {
        val best = SeparationModelId("best")
        val interrupted = import(FakeLocalFile(displayName = "a.mp3", content = "audio-a"))
        tracks.updatePipeline(interrupted.id, TrackPipelineState.Separating(best, SeparationProgress(SeparationProgress.Stage.Separating, 0.3f)))
        val ready = import(FakeLocalFile(displayName = "b.mp3", content = "audio-b"))
        tracks.updatePipeline(ready.id, TrackPipelineState.Ready(fast))
        val failed = import(FakeLocalFile(displayName = "c.mp3", content = "audio-c"))
        tracks.updatePipeline(failed.id, TrackPipelineState.Failed(PipelineStep.Separating, "broken file"))

        startPipeline()
        // The running job reports progress, which changes the Track again.
        tracks.updatePipeline(interrupted.id, TrackPipelineState.Separating(best, SeparationProgress(SeparationProgress.Stage.Separating, 0.4f)))

        assertEquals(listOf(FakePipelineJobs.Separation(interrupted.id, best)), jobs.separations)
        assertEquals(TrackPipelineState.Ready(fast), tracks.get(ready.id)?.pipeline)
        assertEquals(TrackPipelineState.Failed(PipelineStep.Separating, "broken file"), tracks.get(failed.id)?.pipeline)
    }

    @Test
    fun `retrying a Track whose Separation failed queues it again with the default model`() = runTest {
        startPipeline()
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.Failed(PipelineStep.Separating, "out of memory"))

        pipeline.retry(track.id)

        assertEquals(List(2) { FakePipelineJobs.Separation(track.id, fast) }, jobs.separations)
        assertEquals(TrackPipelineState.Separating(fast), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `an added Track of a network Source is queued for download, an unfinished local import is not`() = runTest {
        startPipeline()

        val found = tracks.addIfAbsent(Track(TrackId(youtube, "beat-it"), "Beat It", null, null, TrackPipelineState.Added))
        val unfinishedImport = tracks.addIfAbsent(Track(TrackId(SourceId.Local, "abc"), "Song", null, null, TrackPipelineState.Added))

        assertEquals(listOf(found.id), jobs.downloads)
        assertEquals(TrackPipelineState.Downloading(), tracks.get(found.id)?.pipeline)
        assertEquals(TrackPipelineState.Added, tracks.get(unfinishedImport.id)?.pipeline)
    }

    @Test
    fun `at start an interrupted download is queued again once, and a failed one can be retried`() = runTest {
        val interrupted = tracks.addIfAbsent(Track(TrackId(youtube, "a"), "A", null, null, TrackPipelineState.Downloading(0.4f)))
        val failed = tracks.addIfAbsent(
            Track(TrackId(youtube, "b"), "B", null, null, TrackPipelineState.Failed(PipelineStep.Downloading, "connection lost")),
        )

        startPipeline()
        tracks.updatePipeline(interrupted.id, TrackPipelineState.Downloading(0.5f)) // its job reports progress
        pipeline.retry(failed.id)

        assertEquals(listOf(interrupted.id, failed.id), jobs.downloads)
        assertEquals(TrackPipelineState.Downloading(), tracks.get(failed.id)?.pipeline)
    }

    @Test
    fun `a Track whose stems were deleted is separated again with its model only once it is opened`() = runTest {
        val best = SeparationModelId("best")
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.StemsDeleted(best))
        startPipeline()
        assertEquals(emptyList(), jobs.separations)

        val engine = FakeAudioEngine()
        TrackPlayer(tracks, files, engine).open(track.id)

        // The Original Audio plays until the stems are back.
        assertEquals(files.originalAudio(track.id), engine.playback.value.loaded)
        assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
        assertEquals(listOf(FakePipelineJobs.Separation(track.id, best)), jobs.separations)
    }

    @Test
    fun `opening a Session on a Track whose stems were deleted brings them back too`() = runTest {
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.StemsDeleted(fast))
        startPipeline()
        val engine = FakeAudioEngine()
        val sessions = Sessions(tracks, InMemoryTakeRepository(), files, engine, InMemorySettingsRepository())

        val session = sessions.open(track.id, backgroundScope)

        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
        assertEquals(TrackPipelineState.Separating(fast), tracks.get(track.id)?.pipeline)
        assertEquals(listOf(FakePipelineJobs.Separation(track.id, fast)), jobs.separations)
    }
}
