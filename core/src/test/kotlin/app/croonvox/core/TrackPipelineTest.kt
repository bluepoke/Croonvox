// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.library.ImportLocalFile
import app.croonvox.core.library.Library
import app.croonvox.core.model.PipelineStep
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.pipeline.DefaultSeparationModel
import app.croonvox.core.pipeline.TrackPipeline
import app.croonvox.core.session.BackingAudio
import app.croonvox.core.session.Sessions
import app.croonvox.core.session.backingAudio
import app.croonvox.core.testing.FakeAudioEngine
import app.croonvox.core.testing.InMemorySettingsRepository
import app.croonvox.core.testing.InMemoryTakeRepository
import app.croonvox.core.port.SeparationProgress
import app.croonvox.core.testing.FakeLocalFile
import app.croonvox.core.testing.FakePipelineJobs
import app.croonvox.core.testing.FakeSource
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
    private val medium = SeparationModelId("medium")
    private val best = SeparationModelId("best")
    private val settings = InMemorySettingsRepository()
    private val defaultModel = DefaultSeparationModel(settings, listOf(fast, medium, best), fallback = fast)
    private val pipeline = TrackPipeline(tracks, jobs, defaultModel, downloadFrom = setOf(youtube))

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
    fun `a Downloaded Track is separated with the model chosen when it was added`() = runTest {
        startPipeline()

        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"), separationModel = best)

        assertEquals(listOf(FakePipelineJobs.Separation(track.id, best)), jobs.separations)
        assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `a Track added without a model choice is separated with the Default Separation Model`() = runTest {
        defaultModel.set(medium)
        startPipeline()

        // E.g. added before models could be chosen.
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))

        assertEquals(TrackPipelineState.Separating(medium), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `at start a Track left Separating by an earlier process is queued again once with its model`() = runTest {
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
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.StemsDeleted(best))
        startPipeline()
        assertEquals(emptyList(), jobs.separations)
        val engine = FakeAudioEngine()
        val sessions = Sessions(tracks, InMemoryTakeRepository(), files, engine, InMemorySettingsRepository())

        val session = sessions.open(track.id, backgroundScope)

        // The Original Audio plays until the stems are back.
        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
        assertEquals(files.originalAudio(track.id), engine.playback.value.loaded)
        assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
        assertEquals(listOf(FakePipelineJobs.Separation(track.id, best)), jobs.separations)
    }

    @Test
    fun `a file imported Vocal-Free goes Downloaded to Ready without Separating`() = runTest {
        startPipeline()

        val track = import(FakeLocalFile(displayName = "karaoke.mp3", content = "audio-1"), vocalFree = true)

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(track.id)?.pipeline)
        assertEquals(true, tracks.get(track.id)?.vocalFree)
        assertEquals(emptyList(), jobs.separations)
    }

    @Test
    fun `a search result added Vocal-Free is downloaded and then becomes Ready without Separating`() = runTest {
        startPipeline()
        val library = Library(tracks, InMemoryTakeRepository())
        val source = FakeSource(youtube).apply { add(sourceTrackId = "karaoke", title = "Fly Me to the Moon (Karaoke)") }

        val track = library.add(source.search("karaoke").single(), vocalFree = true)
        assertEquals(listOf(track.id), jobs.downloads)
        tracks.updatePipeline(track.id, TrackPipelineState.Downloaded) // the download finished

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(track.id)?.pipeline)
        assertEquals(emptyList(), jobs.separations)
    }

    @Test
    fun `re-separating with another model while one runs replaces the running Separation`() = runTest {
        startPipeline()
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.Separating(fast, SeparationProgress(SeparationProgress.Stage.Separating, 0.4f)))
        // While the running job is being stopped, the Track already waits for the new model, so the
        // stopping job can't write over it, and the new job isn't queued yet, so it isn't cancelled too.
        jobs.onCancelSeparation = {
            assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
            assertEquals(listOf(FakePipelineJobs.Separation(track.id, fast)), jobs.separations)
        }

        pipeline.reseparate(track.id, best)

        assertEquals(listOf(track.id), jobs.cancelledSeparations)
        assertEquals(listOf(FakePipelineJobs.Separation(track.id, fast), FakePipelineJobs.Separation(track.id, best)), jobs.separations)
        assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `re-separating with the model that is running keeps the running Separation`() = runTest {
        startPipeline()
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        val running = TrackPipelineState.Separating(fast, SeparationProgress(SeparationProgress.Stage.Separating, 0.4f))
        tracks.updatePipeline(track.id, running)

        pipeline.reseparate(track.id, fast)

        assertEquals(emptyList(), jobs.cancelledSeparations)
        assertEquals(listOf(FakePipelineJobs.Separation(track.id, fast)), jobs.separations)
        assertEquals(running, tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `re-separating a Ready Track plays the Original Audio until the new Instrumental is there`() = runTest {
        startPipeline()
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.Ready(fast))

        pipeline.reseparate(track.id, best)

        assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
        assertEquals(FakePipelineJobs.Separation(track.id, best), jobs.separations.last())
        assertEquals(BackingAudio.OriginalAudio, tracks.get(track.id)!!.backingAudio())
        tracks.updatePipeline(track.id, TrackPipelineState.Ready(best)) // the job finished
        assertEquals(BackingAudio.Instrumental(best), tracks.get(track.id)!!.backingAudio())
    }

    @Test
    fun `a Track whose stems were deleted or whose Separation failed can be re-separated with another model`() = runTest {
        startPipeline()
        val deleted = import(FakeLocalFile(displayName = "a.mp3", content = "audio-a"))
        tracks.updatePipeline(deleted.id, TrackPipelineState.StemsDeleted(fast))
        val failed = import(FakeLocalFile(displayName = "b.mp3", content = "audio-b"))
        tracks.updatePipeline(failed.id, TrackPipelineState.Failed(PipelineStep.Separating, "out of memory"))

        pipeline.reseparate(deleted.id, medium)
        pipeline.reseparate(failed.id, medium)

        assertEquals(TrackPipelineState.Separating(medium), tracks.get(deleted.id)?.pipeline)
        assertEquals(TrackPipelineState.Separating(medium), tracks.get(failed.id)?.pipeline)
    }

    @Test
    fun `retrying a failed re-separation uses the model it was re-separated with`() = runTest {
        startPipeline()
        val track = import(FakeLocalFile(displayName = "song.mp3", content = "audio-1"))
        tracks.updatePipeline(track.id, TrackPipelineState.Ready(fast))
        pipeline.reseparate(track.id, best)
        tracks.updatePipeline(track.id, TrackPipelineState.Failed(PipelineStep.Separating, "out of memory"))

        pipeline.retry(track.id)

        assertEquals(TrackPipelineState.Separating(best), tracks.get(track.id)?.pipeline)
    }

    @Test
    fun `re-separating with the model a Track is Ready with, or a Vocal-Free Track, changes nothing`() = runTest {
        startPipeline()
        val ready = import(FakeLocalFile(displayName = "a.mp3", content = "audio-a"))
        tracks.updatePipeline(ready.id, TrackPipelineState.Ready(fast))
        val vocalFree = import(FakeLocalFile(displayName = "karaoke.mp3", content = "audio-b"), vocalFree = true)
        val queued = jobs.separations.toList()

        pipeline.reseparate(ready.id, fast)
        pipeline.reseparate(vocalFree.id, best)

        assertEquals(TrackPipelineState.Ready(fast), tracks.get(ready.id)?.pipeline)
        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(vocalFree.id)?.pipeline)
        assertEquals(queued, jobs.separations)
    }

    @Test
    fun `adding an existing Track again with the Vocal-Free option leaves it unchanged`() = runTest {
        startPipeline()
        val file = FakeLocalFile(displayName = "song.mp3", content = "audio-1")
        val first = import(file)
        val library = Library(tracks, InMemoryTakeRepository())
        val source = FakeSource(youtube).apply { add(sourceTrackId = "v1", title = "Song") }
        val found = library.add(source.search("song").single())

        val again = import(file, vocalFree = true)
        val foundAgain = library.add(source.search("song").single(), vocalFree = true)

        assertEquals(false, again.vocalFree)
        assertEquals(TrackPipelineState.Separating(fast), tracks.get(first.id)?.pipeline)
        assertEquals(false, foundAgain.vocalFree)
        assertEquals(false, tracks.get(found.id)?.vocalFree)
    }
}
