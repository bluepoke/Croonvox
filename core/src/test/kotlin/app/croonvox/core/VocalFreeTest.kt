// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.model.PipelineStep
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.pipeline.DefaultSeparationModel
import app.croonvox.core.pipeline.TrackPipeline
import app.croonvox.core.pipeline.SeparateTrack
import app.croonvox.core.pipeline.VocalFreeToggle
import app.croonvox.core.session.BackingAudio
import app.croonvox.core.session.Sessions
import app.croonvox.core.session.backingAudio
import app.croonvox.core.port.SeparationProgress
import app.croonvox.core.testing.FakeAudioEngine
import app.croonvox.core.testing.FakePipelineJobs
import app.croonvox.core.testing.FakeSeparator
import app.croonvox.core.testing.InMemorySettingsRepository
import app.croonvox.core.testing.InMemoryTakeRepository
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
class VocalFreeTest {
    private val tracks = InMemoryTrackRepository()
    private val files = InMemoryTrackFiles()
    private val jobs = FakePipelineJobs()
    private val vocalFree = VocalFreeToggle(tracks, files, jobs, models = listOf(FAST, BEST))
    private val pipeline = TrackPipeline(tracks, jobs, DefaultSeparationModel(InMemorySettingsRepository(), listOf(FAST, BEST), fallback = FAST))
    private val id = TrackId(SourceId.Local, "abc")

    private suspend fun addTrack(pipeline: TrackPipelineState, vocalFree: Boolean = false) =
        tracks.addIfAbsent(Track(id, "Fly Me to the Moon (Karaoke)", null, 148_000, pipeline, vocalFree = vocalFree))

    private fun TestScope.startPipeline() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pipeline.run() }
    }

    @Test
    fun `marking a Separating Track cancels the Separation, drops its partial stems and makes it Ready`() = runTest {
        addTrack(TrackPipelineState.Separating(FAST, SeparationProgress(SeparationProgress.Stage.Separating, 0.4f)))
        files.store(files.separationCheckpoint(id, FAST), 20_000_000)

        vocalFree.mark(id)

        assertEquals(listOf(id), jobs.cancelledSeparations)
        assertEquals(0, files.sizeOf(files.separationCheckpoint(id, FAST)))
        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(id)?.pipeline)
        assertEquals(true, tracks.get(id)?.vocalFree)
    }

    @Test
    fun `marking a Track whose Separation failed makes it Ready and drops what the Separation left`() = runTest {
        addTrack(TrackPipelineState.Failed(PipelineStep.Separating, "out of memory"))
        files.store(files.separationCheckpoint(id, FAST), 20_000_000)

        vocalFree.mark(id)

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(id)?.pipeline)
        assertEquals(emptyList(), jobs.cancelledSeparations)
        assertEquals(0, files.sizeOf(files.separationCheckpoint(id, FAST)))
    }

    @Test
    fun `marking a Track with stems names the space freed, then deletes them`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        files.store(files.stems(id, FAST).instrumental, 30_000_000)
        files.store(files.stems(id, FAST).vocalStem, 25_000_000)

        assertEquals(55_000_000, vocalFree.stemBytes(id))
        vocalFree.mark(id)

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(id)?.pipeline)
        assertEquals(0, files.sizeOf(files.stems(id, FAST).instrumental))
        assertEquals(0, vocalFree.stemBytes(id))
    }

    @Test
    fun `marking a Track whose stems were deleted makes it Ready, and opening it then needs no stems`() = runTest {
        addTrack(TrackPipelineState.StemsDeleted(FAST))
        val engine = FakeAudioEngine()

        vocalFree.mark(id)
        Sessions(tracks, InMemoryTakeRepository(), files, engine, InMemorySettingsRepository()).open(id, backgroundScope)

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(id)?.pipeline)
        assertEquals(files.originalAudio(id), engine.playback.value.loaded)
        assertEquals(emptyList(), jobs.separations)
    }

    @Test
    fun `marking a Track before its download finishes skips its Separation`() = runTest {
        addTrack(TrackPipelineState.Added)
        vocalFree.mark(id)
        startPipeline()

        tracks.updatePipeline(id, TrackPipelineState.Downloaded)

        assertEquals(TrackPipelineState.Ready(model = null), tracks.get(id)?.pipeline)
        assertEquals(emptyList(), jobs.separations)
    }

    @Test
    fun `unmarking a Ready Track separates it with the model picked for it, then it plays the Instrumental`() = runTest {
        addTrack(TrackPipelineState.Ready(model = null), vocalFree = true)

        vocalFree.unmark(id, BEST)

        assertEquals(false, tracks.get(id)?.vocalFree)
        assertEquals(TrackPipelineState.Separating(BEST), tracks.get(id)?.pipeline)
        assertEquals(listOf(FakePipelineJobs.Separation(id, BEST)), jobs.separations)
        assertEquals(BackingAudio.OriginalAudio, tracks.get(id)!!.backingAudio())

        SeparateTrack(tracks, files, FakeSeparator())(id, BEST) // the queued job runs

        assertEquals(TrackPipelineState.Ready(BEST), tracks.get(id)?.pipeline)
        assertEquals(BackingAudio.Instrumental(BEST), tracks.get(id)!!.backingAudio())
    }

    @Test
    fun `stems a cancelled Separation hadn't stopped writing are not deleted once the Track was unmarked again`() = runTest {
        addTrack(TrackPipelineState.Separating(FAST))
        // The user turns the toggle off again while marking still waits for the old job to stop.
        jobs.onCancelSeparation = {
            vocalFree.unmark(id, FAST)
            files.store(files.separationCheckpoint(id, FAST), 5_000_000) // the new Separation's work
        }

        vocalFree.mark(id)

        assertEquals(TrackPipelineState.Separating(FAST), tracks.get(id)?.pipeline)
        assertEquals(5_000_000, files.sizeOf(files.separationCheckpoint(id, FAST)))
    }

    private companion object {
        val FAST = SeparationModelId("fast")
        val BEST = SeparationModelId("best")
    }
}
