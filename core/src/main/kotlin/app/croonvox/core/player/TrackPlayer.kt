// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.player

import app.croonvox.core.library.TrackFiles
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.port.AudioEngine
import app.croonvox.core.port.LoadId
import app.croonvox.core.port.Playback
import kotlinx.coroutines.flow.StateFlow

/** Plays a Track's Original Audio through the Audio Engine (Track detail, before Sessions exist). */
class TrackPlayer(
    private val tracks: TrackRepository,
    private val files: TrackFiles,
    private val engine: AudioEngine,
) {
    val playback: StateFlow<Playback> get() = engine.playback

    /**
     * Loads the Track's Original Audio, paused at the start, and returns a handle to control it.
     * [onProgress] receives 0..1 while the audio is prepared.
     * @throws NotPlayableYet if the Track Pipeline hasn't stored the Original Audio yet.
     */
    suspend fun open(id: TrackId, onProgress: (Float) -> Unit = {}): OpenTrack {
        val track = checkNotNull(tracks.get(id)) { "no Track $id" }
        if (!track.hasOriginalAudio) throw NotPlayableYet(track)
        val load = engine.load(files.originalAudio(id), onProgress)
        return OpenTrack(track, load, engine)
    }
}

/** A Track whose Original Audio is loaded for playback. */
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

/** The Track has no stored Original Audio yet (see [Track.hasOriginalAudio]). */
class NotPlayableYet(val track: Track) : IllegalStateException("Track ${track.id.key} is not downloaded yet")
