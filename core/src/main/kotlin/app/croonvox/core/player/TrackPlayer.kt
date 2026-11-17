// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.player

import app.croonvox.core.library.TrackFiles
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.port.AudioEngine
import app.croonvox.core.port.LoadId
import app.croonvox.core.port.Playback
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays a Track through the Audio Engine on Track detail: its Instrumental once the Track is
 * Ready, its Original Audio before (ADR 10).
 */
class TrackPlayer(
    private val tracks: TrackRepository,
    private val files: TrackFiles,
    private val engine: AudioEngine,
) {
    val playback: StateFlow<Playback> get() = engine.playback

    /**
     * Loads the Track's Instrumental (or Original Audio), paused at the start, and returns a handle to control it.
     * Deleted stems are made again meanwhile (ADR 17).
     * [onProgress] receives 0..1 while the audio is prepared.
     * @throws NotPlayableYet if the Track Pipeline hasn't stored the Original Audio yet.
     */
    suspend fun open(id: TrackId, onProgress: (Float) -> Unit = {}): OpenTrack {
        val track = tracks.getForOpening(id)
        val load = engine.load(audioOf(track), onProgress)
        return OpenTrack(track, load, engine)
    }

    /**
     * What Track detail plays for [track] as it is: the Instrumental once Ready with stems, the
     * Original Audio before, and always for a Vocal-Free Track (ADR 14).
     */
    fun audioOf(track: Track): AudioFile = when (val pipeline = track.pipeline) {
        is TrackPipelineState.Ready -> pipeline.model?.let { files.stems(track.id, it).instrumental } ?: files.originalAudio(track.id)
        else -> files.originalAudio(track.id)
    }
}

/** A Track whose audio is loaded for playback, as the Track was when it was opened. */
class OpenTrack internal constructor(
    val track: Track,
    private val load: LoadId,
    private val engine: AudioEngine,
) {
    fun play() = engine.play()

    fun pause() = engine.pause()

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    /** Stops and releases this Track's audio; does nothing if another Track was opened since. */
    fun close() = engine.unload(load)
}

/**
 * The Track being opened (Track detail or a Session), which needs its stems: if they were deleted,
 * it goes back to Separating with the same model and the Track Pipeline queues the job (ADR 17).
 * @throws NotPlayableYet if the Track Pipeline hasn't stored the Original Audio yet.
 */
internal suspend fun TrackRepository.getForOpening(id: TrackId): Track {
    val track = checkNotNull(get(id)) { "no Track $id" }
    if (!track.hasOriginalAudio) throw NotPlayableYet(track)
    val deleted = track.pipeline as? TrackPipelineState.StemsDeleted ?: return track
    return updatePipeline(id, TrackPipelineState.Separating(deleted.model))
}

/** The Track has no stored Original Audio yet (see [Track.hasOriginalAudio]). */
class NotPlayableYet(val track: Track) : IllegalStateException("Track ${track.id.key} is not downloaded yet")
