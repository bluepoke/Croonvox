// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core

import app.croonvox.core.model.InputDevice
import app.croonvox.core.model.OutputRoute
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.model.Transpose
import app.croonvox.core.player.NotPlayableYet
import app.croonvox.core.session.BackingAudio
import app.croonvox.core.session.Session
import app.croonvox.core.session.SessionState
import app.croonvox.core.session.Sessions
import app.croonvox.core.testing.FakeAudioEngine
import app.croonvox.core.testing.InMemorySettingsRepository
import app.croonvox.core.testing.InMemoryTakeRepository
import app.croonvox.core.testing.InMemoryTrackFiles
import app.croonvox.core.testing.InMemoryTrackRepository
import app.croonvox.core.session.MicrophoneUnavailable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {
    private val trackId = TrackId(SourceId("fake"), "v1")
    private val tracks = InMemoryTrackRepository()
    private val takes = InMemoryTakeRepository()
    private val files = InMemoryTrackFiles()
    private val settings = InMemorySettingsRepository()
    private val instrumental = files.stems(trackId, MODEL).instrumental
    private val engine = FakeAudioEngine(mapOf(files.originalAudio(trackId) to SONG_MS, instrumental to SONG_MS))
    private val sessions = Sessions(tracks, takes, files, engine, settings, clock = { NOW })

    private suspend fun addTrack(pipeline: TrackPipelineState) =
        tracks.addIfAbsent(Track(trackId, "Fly Me to the Moon", "Frank Sinatra", SONG_MS, pipeline))

    @Test
    fun `a Session on a Downloaded Track sings over its Original Audio`() = runTest {
        addTrack(TrackPipelineState.Downloaded)

        val session = sessions.open(trackId, backgroundScope)

        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
        assertEquals(files.originalAudio(trackId), engine.playback.value.loaded)
        assertTrue(engine.inSession)
    }

    @Test
    fun `a Session can't start before the Original Audio is stored`() = runTest {
        addTrack(TrackPipelineState.Added)

        assertFailsWith<NotPlayableYet> { sessions.open(trackId, backgroundScope) }
    }

    @Test
    fun `recording from any position captures a Take starting there and plays it back in Review`() = runTest {
        val session = openDownloaded()
        session.seekTo(30_000)

        session.record()
        engine.advance(10_000)
        session.stop()

        val review = assertIs<SessionState.Review>(session.state.value)
        assertEquals(30_000, review.take.startPositionMs)
        assertEquals(10_000, review.take.durationMs)
        // Review plays the Take against the Backing Audio right away, aligned by the engine's estimate.
        assertTrue(engine.playback.value.playingTake)
        assertEquals(engine.reportedLatencyFrames, engine.lastAlignmentFrames)
    }

    @Test
    fun `keeping a Take stores its audio, Start Position and route and lists it for the Track`() = runTest {
        val session = openDownloaded()
        engine.outputRoute = OutputRoute.Usb
        recordTake(session, fromMs = 30_000, lengthMs = 10_000)

        val kept = session.keep()

        assertEquals(SessionState.Idle, session.state.value)
        assertEquals(listOf(kept), session.takes.first())
        assertEquals("Take 1", kept.name)
        assertEquals(30_000, kept.startPositionMs)
        assertEquals(10_000, kept.durationMs)
        assertEquals(OutputRoute.Usb, kept.route)
        assertEquals(NOW, kept.recordedAt)
        assertEquals(kept.frames, engine.writtenTakes[files.take(trackId, kept.id)]?.frames)
        assertNull(engine.heldTake)
    }

    @Test
    fun `a Take in Review can be played again after its playback ended`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 8_000, lengthMs = 2_000)
        engine.advance(2_000)
        runCurrent()
        assertFalse(engine.playback.value.playing)

        session.replay()

        assertIs<SessionState.Review>(session.state.value)
        assertTrue(engine.playback.value.playingTake)
        assertEquals(8_000, engine.playback.value.positionMs)
    }

    @Test
    fun `pausing Review playback keeps the Take in Review`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 0, lengthMs = 4_000)

        session.pause()

        assertIs<SessionState.Review>(session.state.value)
        assertFalse(engine.playback.value.playing)
        assertEquals(4_000, engine.heldTake?.durationMs)
    }

    @Test
    fun `Review plays the Backing Audio 20 dB down by default so the voice can be heard`() = runTest {
        val session = openDownloaded()

        assertEquals(-20f, session.reviewBackingDb.value)
        assertEquals(-20f, engine.reviewBackingGainDb)
    }

    @Test
    fun `the Review balance can be changed and is remembered`() = runTest {
        val first = openDownloaded()
        first.setReviewBackingDb(-27f)
        assertEquals(-27f, engine.reviewBackingGainDb)
        first.close()

        val second = sessions.open(trackId, backgroundScope)

        assertEquals(-27f, second.reviewBackingDb.value)
        assertEquals(-27f, engine.reviewBackingGainDb)
    }

    @Test
    fun `discarding a Take in Review stores nothing`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 0, lengthMs = 5_000)

        session.discard()

        assertEquals(SessionState.Idle, session.state.value)
        assertEquals(emptyList(), session.takes.first())
        assertEquals(emptyMap(), engine.writtenTakes)
        assertNull(engine.heldTake)
    }

    @Test
    fun `record again discards the Take and records a new one from the same Start Position`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 42_000, lengthMs = 5_000)

        session.recordAgain()

        assertEquals(SessionState.Recording(42_000, Transpose.None), session.state.value)
        engine.advance(3_000)
        session.stop()
        val review = assertIs<SessionState.Review>(session.state.value)
        assertEquals(42_000, review.take.startPositionMs)
        assertEquals(3_000, review.take.durationMs)
        assertEquals(emptyList(), session.takes.first())
    }

    @Test
    fun `kept Takes are numbered in order`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 0, lengthMs = 1_000)
        session.keep()
        session.seekTo(0)
        recordTake(session, fromMs = 0, lengthMs = 1_000)

        session.keep()

        assertEquals(listOf("Take 1", "Take 2"), session.takes.first().map { it.name })
    }

    @Test
    fun `a recording that reaches the end of the song goes to Review by itself`() = runTest {
        val session = openDownloaded()
        session.seekTo(SONG_MS - 4_000)
        session.record()

        engine.advance(10_000)
        runCurrent()

        val review = assertIs<SessionState.Review>(session.state.value)
        assertEquals(4_000, review.take.durationMs)
    }

    @Test
    fun `with the countdown on, recording starts after three seconds`() = runTest {
        settings.setCountdownEnabled(true)
        val session = openDownloaded()
        session.seekTo(10_000)

        launch { session.record() }
        runCurrent()
        assertEquals(SessionState.CountingDown(10_000, secondsLeft = 3), session.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(SessionState.CountingDown(10_000, secondsLeft = 2), session.state.value)
        assertFalse(engine.playback.value.playing)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(SessionState.Recording(10_000, Transpose.None), session.state.value)
        assertTrue(engine.playback.value.recording)
    }

    @Test
    fun `stopping during the countdown records nothing`() = runTest {
        val session = openDownloaded()
        session.setCountdownEnabled(true)

        launch { session.record() }
        advanceTimeBy(1_500)
        session.stop()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(SessionState.Idle, session.state.value)
        assertFalse(engine.playback.value.recording)
    }

    @Test
    fun `the countdown setting is remembered`() = runTest {
        val first = openDownloaded()
        first.setCountdownEnabled(true)
        first.close()

        val second = sessions.open(trackId, backgroundScope)

        assertTrue(second.countdownEnabled.value)
    }

    @Test
    fun `recording without a usable microphone fails and leaves the Session idle`() = runTest {
        val session = openDownloaded()
        engine.microphoneAvailable = false

        assertFailsWith<MicrophoneUnavailable> { session.record() }
        assertEquals(SessionState.Idle, session.state.value)
    }

    @Test
    fun `a Session on a Ready Track sings over the Instrumental`() = runTest {
        addTrack(TrackPipelineState.Ready(MODEL))

        val session = sessions.open(trackId, backgroundScope)

        assertEquals(BackingAudio.Instrumental(MODEL), session.backing.value)
        assertEquals(instrumental, engine.playback.value.loaded)
    }

    @Test
    fun `the Session switches to the Instrumental when Separation finishes, keeping its place`() = runTest {
        val session = openDownloaded()
        session.seekTo(20_000)
        session.play()

        tracks.updatePipeline(trackId, TrackPipelineState.Ready(MODEL))
        runCurrent()

        assertEquals(BackingAudio.Instrumental(MODEL), session.backing.value)
        assertEquals(instrumental, engine.playback.value.loaded)
        assertEquals(20_000, engine.playback.value.positionMs)
        assertTrue(engine.playback.value.playing)
    }

    @Test
    fun `a Take is recorded and reviewed over one Backing Audio, so the switch waits until Review ends`() = runTest {
        val session = openDownloaded()
        session.record()
        engine.advance(5_000)

        tracks.updatePipeline(trackId, TrackPipelineState.Ready(MODEL))
        runCurrent()
        assertEquals(BackingAudio.OriginalAudio, session.backing.value)
        session.stop()
        runCurrent()
        assertEquals(BackingAudio.OriginalAudio, session.backing.value)

        session.keep()
        runCurrent()

        assertEquals(BackingAudio.Instrumental(MODEL), session.backing.value)
        assertEquals(instrumental, engine.playback.value.loaded)
    }

    @Test
    fun `record again sings over the Instrumental that became ready during Review`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 15_000, lengthMs = 5_000)
        tracks.updatePipeline(trackId, TrackPipelineState.Ready(MODEL))
        runCurrent()

        session.recordAgain()

        assertEquals(BackingAudio.Instrumental(MODEL), session.backing.value)
        assertEquals(instrumental, engine.playback.value.loaded)
        assertEquals(SessionState.Recording(15_000, Transpose.None), session.state.value)
        assertEquals(15_000, engine.playback.value.positionMs)
    }

    @Test
    fun `the input gain is remembered per microphone and applied to the one in use`() = runTest {
        val session = openDownloaded()
        session.enableMicrophone()
        runCurrent()
        session.setInputGain(6f)
        assertEquals(6f, engine.inputGainDb)

        engine.switchInputDevice(InputDevice.Usb)
        runCurrent()
        assertEquals(0f, session.inputGainDb.value)
        assertEquals(0f, engine.inputGainDb)
        session.setInputGain(-3f)

        engine.switchInputDevice(InputDevice.BuiltIn)
        runCurrent()
        assertEquals(6f, session.inputGainDb.value)
        assertEquals(6f, engine.inputGainDb)
    }

    @Test
    fun `a kept Take can be renamed`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 0, lengthMs = 1_000)
        val take = session.keep()

        session.rename(take.id, "  Best so far ")

        assertEquals(listOf("Best so far"), session.takes.first().map { it.name })
    }

    @Test
    fun `deleting a kept Take removes it and its audio`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 0, lengthMs = 1_000)
        val take = session.keep()

        session.delete(take.id)

        assertEquals(emptyList(), session.takes.first())
        assertEquals(listOf(files.take(trackId, take.id)), files.deletedTakes)
    }

    @Test
    fun `playing a kept Take plays its audio against the Backing Audio from its Start Position`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 12_000, lengthMs = 3_000)
        val take = session.keep()
        session.seekTo(90_000)

        session.play(take.id)

        assertEquals(SessionState.PlayingTake(take), session.state.value)
        assertTrue(engine.playback.value.playingTake)
        assertEquals(12_000, engine.playback.value.positionMs)
        assertEquals(take.reportedLatencyFrames, engine.lastAlignmentFrames)

        engine.advance(3_000)
        runCurrent()
        assertEquals(SessionState.Idle, session.state.value)
    }

    @Test
    fun `changing the Transpose during free playback is heard at once, without restarting`() = runTest {
        val session = openDownloaded()
        assertEquals(Transpose.None, session.transpose.value)
        session.seekTo(20_000)
        session.play()
        engine.advance(1_000)

        session.setTranspose(Transpose(-3))

        assertEquals(Transpose(-3), session.transpose.value)
        assertEquals(Transpose(-3), engine.audibleTranspose)
        assertTrue(engine.playback.value.playing)
        assertEquals(21_000, engine.playback.value.positionMs)
    }

    @Test
    fun `a Take is sung at the Transpose set when recording starts and is kept with it`() = runTest {
        val session = openDownloaded()
        session.setTranspose(Transpose(2))
        session.record()
        engine.advance(1_000)

        session.setTranspose(Transpose(5))

        assertEquals(Transpose(2), engine.audibleTranspose)
        engine.advance(1_000)
        session.stop()
        assertEquals(Transpose(2), assertIs<SessionState.Review>(session.state.value).transpose)
        val kept = session.keep()
        assertEquals(Transpose(2), kept.transpose)
        assertEquals(listOf(Transpose(2)), session.takes.first().map { it.transpose })
        // The control keeps its new value for the next Take.
        assertEquals(Transpose(5), session.transpose.value)
    }

    @Test
    fun `Review plays the Backing Audio at the Take's Transpose, whatever the control is set to`() = runTest {
        val session = openDownloaded()
        session.setTranspose(Transpose(-4))
        recordTake(session, fromMs = 5_000, lengthMs = 3_000)
        assertEquals(Transpose(-4), engine.audibleTranspose)

        session.setTranspose(Transpose(1))
        assertEquals(Transpose(-4), engine.audibleTranspose)
        engine.advance(3_000)
        runCurrent()
        session.replay()

        assertTrue(engine.playback.value.playingTake)
        assertEquals(Transpose(-4), engine.audibleTranspose)
        session.discard()
        session.play()
        assertEquals(Transpose(1), engine.audibleTranspose)
    }

    @Test
    fun `a kept Take plays back at its own Transpose and keeps it when the control changes`() = runTest {
        val session = openDownloaded()
        session.setTranspose(Transpose(3))
        recordTake(session, fromMs = 0, lengthMs = 2_000)
        val take = session.keep()
        session.setTranspose(Transpose(-6))

        session.play(take.id)

        assertEquals(Transpose(-6), session.transpose.value)
        assertEquals(Transpose(3), engine.audibleTranspose)
        assertEquals(listOf(Transpose(3)), session.takes.first().map { it.transpose })
    }

    @Test
    fun `leaving a Session drops its Transpose, so the next one starts untransposed`() = runTest {
        val first = openDownloaded()
        first.setTranspose(Transpose(4))
        first.close()
        assertEquals(Transpose.None, engine.audibleTranspose)

        val second = sessions.open(trackId, backgroundScope)
        second.play()

        assertEquals(Transpose.None, second.transpose.value)
        assertEquals(Transpose.None, engine.audibleTranspose)
    }

    @Test
    fun `record again sings the new Take at the Transpose the control is set to now`() = runTest {
        val session = openDownloaded()
        recordTake(session, fromMs = 15_000, lengthMs = 5_000)
        session.setTranspose(Transpose(-2))

        session.recordAgain()

        assertEquals(SessionState.Recording(15_000, Transpose(-2)), session.state.value)
        assertEquals(Transpose(-2), engine.audibleTranspose)
    }

    private suspend fun recordTake(session: Session, fromMs: Long, lengthMs: Long) {
        session.seekTo(fromMs)
        session.record()
        engine.advance(lengthMs)
        session.stop()
    }

    private suspend fun TestScope.openDownloaded(): Session {
        addTrack(TrackPipelineState.Downloaded)
        return sessions.open(trackId, backgroundScope)
    }

    private companion object {
        const val SONG_MS = 180_000L
        const val NOW = 1_700_000_000_000L
        val MODEL = SeparationModelId("fast")
    }
}
