// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.mix.MixSettings
import app.croonvox.core.mix.Mixes
import app.croonvox.core.model.BackingChoice
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Take
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.model.Transpose
import app.croonvox.core.session.BackingAudio
import app.croonvox.core.session.Session
import app.croonvox.core.session.SessionState
import app.croonvox.core.session.Sessions
import app.croonvox.core.testing.FakeAudioEngine
import app.croonvox.core.testing.InMemoryMixRepository
import app.croonvox.core.testing.InMemorySettingsRepository
import app.croonvox.core.testing.InMemoryTakeRepository
import app.croonvox.core.testing.InMemoryTrackFiles
import app.croonvox.core.testing.InMemoryTrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixTest {
    private val trackId = TrackId(SourceId("fake"), "v1")
    private val tracks = InMemoryTrackRepository()
    private val takes = InMemoryTakeRepository()
    private val mixRepository = InMemoryMixRepository()
    private val files = InMemoryTrackFiles()
    private val settings = InMemorySettingsRepository()
    private val instrumental = files.stems(trackId, FAST).instrumental
    private val engine = FakeAudioEngine(mapOf(files.originalAudio(trackId) to SONG_MS, instrumental to SONG_MS))
    private var nextMix = 0
    private val mixes = Mixes(tracks, takes, mixRepository, files, settings, clock = { NOW }, newMixId = { app.croonvox.core.mix.MixId("m${++nextMix}") })
    private val sessions = Sessions(tracks, takes, files, engine, settings, mixes, clock = { NOW })

    private suspend fun addTrack(pipeline: TrackPipelineState, vocalFree: Boolean = false, choice: BackingChoice = BackingChoice.Instrumental) =
        tracks.addIfAbsent(Track(trackId, "Fly Me to the Moon", "Frank Sinatra", SONG_MS, pipeline, vocalFree = vocalFree, backingChoice = choice))

    @Test
    fun `keeping a Take creates a default Mix over the Backing Audio it was sung over, at the Review Balance`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST))
        val session = sessions.open(trackId, backgroundScope)
        session.setReviewBackingDb(-14f)

        val take = keepTake(session, fromMs = 30_000, lengthMs = 10_000)

        val mix = mixes.observe(trackId).first().single()
        assertEquals(take.id, mix.take)
        assertEquals("Mix 1", mix.name)
        assertEquals(BackingAudio.Instrumental(FAST), mix.backing)
        assertEquals(MixSettings(backingDb = -14f, vocalDb = 0f, vocalNudgeMs = 0), mix.settings)
    }

    @Test
    fun `editing a Mix's levels and Vocal Nudge stores them and leaves the Take untouched`() = runTest {
        val session = openReady()
        val take = keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()

        mixes.updateSettings(mix.id, MixSettings(backingDb = -9f, vocalDb = 3f, vocalNudgeMs = -35))

        assertEquals(MixSettings(backingDb = -9f, vocalDb = 3f, vocalNudgeMs = -35), mixes.observe(trackId).first().single().settings)
        assertEquals(take, takes.get(take.id))
        assertEquals(take.frames, engine.writtenTakes[files.take(trackId, take.id)]?.frames)
    }

    @Test
    fun `a Track can have several Mixes, of the same or different Takes`() = runTest {
        val session = openReady()
        val first = keepTake(session, fromMs = 0, lengthMs = 5_000)
        val second = keepTake(session, fromMs = 60_000, lengthMs = 5_000)

        val another = mixes.create(first.id)

        val all = mixes.observe(trackId).first()
        assertEquals(listOf("Mix 1", "Mix 2", "Mix 3"), all.map { it.name })
        assertEquals(listOf(first.id, second.id, first.id), all.map { it.take })
        assertEquals(another, all.last())
        // A new Mix starts like a default one: over what the Track plays now, at the Review Balance.
        assertEquals(BackingAudio.Instrumental(FAST), another.backing)
        assertEquals(MixSettings(backingDb = Session.DEFAULT_REVIEW_BACKING_DB), another.settings)
    }

    @Test
    fun `deleting a Mix leaves its Take and the other Mixes`() = runTest {
        val session = openReady()
        val take = keepTake(session, fromMs = 0, lengthMs = 5_000)
        val kept = mixes.create(take.id)
        val deleted = mixes.observe(trackId).first().first()

        mixes.delete(deleted.id)

        assertEquals(listOf(kept), mixes.observe(trackId).first())
        assertEquals(take, takes.get(take.id))
    }

    @Test
    fun `deleting a Take deletes its Mixes`() = runTest {
        val session = openReady()
        val gone = keepTake(session, fromMs = 0, lengthMs = 5_000)
        val kept = keepTake(session, fromMs = 10_000, lengthMs = 5_000)

        session.delete(gone.id)

        assertEquals(listOf(kept.id), mixes.observe(trackId).first().map { it.take })
    }

    @Test
    fun `a Mix can switch to the Track's Instrumental once it has one, and back`() = runTest {
        addTrack(TrackPipelineState.Ready(FAST), choice = BackingChoice.OriginalAudio)
        val session = sessions.open(trackId, backgroundScope)
        val mix = mixes.create(keepTake(session, fromMs = 0, lengthMs = 5_000).id)
        assertEquals(BackingAudio.OriginalAudio, mix.backing)

        assertTrue(mixes.chooseBacking(mix.id, BackingChoice.Instrumental))
        assertEquals(BackingAudio.Instrumental(FAST), mixes.observe(trackId).first().last().backing)

        assertTrue(mixes.chooseBacking(mix.id, BackingChoice.OriginalAudio))
        assertEquals(BackingAudio.OriginalAudio, mixes.observe(trackId).first().last().backing)
    }

    @Test
    fun `a Mix can't switch to an Instrumental the Track doesn't have`() = runTest {
        addTrack(TrackPipelineState.Separating(FAST))
        val session = sessions.open(trackId, backgroundScope)
        val mix = mixes.create(keepTake(session, fromMs = 0, lengthMs = 5_000).id)

        assertFalse(mixes.chooseBacking(mix.id, BackingChoice.Instrumental))
        assertEquals(BackingAudio.OriginalAudio, mixes.observe(trackId).first().last().backing)
    }

    @Test
    fun `previewing a Mix plays its Take over its Backing Audio with the Mix's levels and Nudge, at the Take's Transpose`() = runTest {
        val session = openReady()
        session.setTranspose(Transpose(-3))
        val take = keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        session.setTranspose(Transpose.None)
        val mix = mixes.observe(trackId).first().single()
        mixes.updateSettings(mix.id, MixSettings(backingDb = -9f, vocalDb = 4f, vocalNudgeMs = 25))

        session.previewMix(mix.id)

        assertIs<SessionState.PreviewingMix>(session.state.value)
        assertTrue(engine.playback.value.playingTake)
        assertEquals(30_000, engine.playback.value.positionMs)
        assertEquals(instrumental, engine.playback.value.loaded)
        assertEquals(Transpose(-3), engine.audibleTranspose)
        assertEquals(-9f, engine.reviewBackingGainDb)
        assertEquals(4f, engine.takeGainDb)
        // 25 ms later than the engine's estimate alone (4 800 frames): 4 800 − 1 200.
        assertEquals(take.reportedLatencyFrames - 1_200, engine.lastAlignmentFrames)
    }

    @Test
    fun `Mix Settings changed during the preview are heard at once`() = runTest {
        val session = openReady()
        val take = keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()
        session.previewMix(mix.id)

        session.setPreviewSettings(MixSettings(backingDb = -6f, vocalDb = -2f, vocalNudgeMs = -50))

        assertEquals(-6f, engine.reviewBackingGainDb)
        assertEquals(-2f, engine.takeGainDb)
        assertEquals(take.reportedLatencyFrames + 2_400, engine.lastAlignmentFrames)
    }

    @Test
    fun `after the preview the Session plays its own Backing Audio again, at the Review Balance`() = runTest {
        val session = openReady()
        session.setReviewBackingDb(-18f)
        val take = keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()
        mixes.chooseBacking(mix.id, BackingChoice.OriginalAudio)
        mixes.updateSettings(mix.id, MixSettings(backingDb = -5f, vocalDb = 6f))

        session.previewMix(mix.id)
        assertEquals(files.originalAudio(trackId), engine.playback.value.loaded)
        engine.advance(take.durationMs)
        runCurrent()

        assertEquals(SessionState.Idle, session.state.value)
        assertEquals(BackingAudio.Instrumental(FAST), session.backing.value)
        assertEquals(instrumental, engine.playback.value.loaded)
        assertEquals(30_000, engine.playback.value.positionMs)
        assertEquals(-18f, engine.reviewBackingGainDb)
        assertEquals(0f, engine.takeGainDb)
    }

    @Test
    fun `stopping the preview returns to free playback`() = runTest {
        val session = openReady()
        keepTake(session, fromMs = 30_000, lengthMs = 10_000)
        session.previewMix(mixes.observe(trackId).first().single().id)

        session.stop()

        assertEquals(SessionState.Idle, session.state.value)
        assertFalse(engine.playback.value.playing)
        assertNull(engine.heldTake)
    }

    @Test
    fun `a Session opened after leaving one during a preview plays Takes as recorded again`() = runTest {
        val first = openReady()
        keepTake(first, fromMs = 30_000, lengthMs = 10_000)
        val mix = mixes.observe(trackId).first().single()
        mixes.updateSettings(mix.id, MixSettings(backingDb = -5f, vocalDb = 9f))
        first.previewMix(mix.id)
        first.close()

        sessions.open(trackId, backgroundScope)

        assertEquals(0f, engine.takeGainDb)
        assertEquals(Session.DEFAULT_REVIEW_BACKING_DB, engine.reviewBackingGainDb)
    }

    private suspend fun keepTake(session: Session, fromMs: Long, lengthMs: Long): Take {
        session.seekTo(fromMs)
        session.record()
        engine.advance(lengthMs)
        session.stop()
        return session.keep()
    }

    private suspend fun TestScope.openReady(): Session {
        addTrack(TrackPipelineState.Ready(FAST))
        return sessions.open(trackId, backgroundScope)
    }

    private companion object {
        const val SONG_MS = 180_000L
        const val NOW = 1_700_000_000_000L
        val FAST = SeparationModelId("fast")
    }
}
