// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.session

import app.croonvox.core.library.TrackFiles
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.mix.Mixes
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.port.AudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Opens Sessions: playing a Track, singing over it and recording Takes (GLOSSARY: Session, ADR 9).
 * One Track at a time: opening a Session closes the one before.
 */
class Sessions(
    private val tracks: TrackRepository,
    private val takes: TakeRepository,
    private val files: TrackFiles,
    private val engine: AudioEngine,
    private val settings: SettingsRepository,
    private val mixes: Mixes,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newTakeId: () -> TakeId = { TakeId(UUID.randomUUID().toString()) },
) {
    private val mutex = Mutex()
    private var current: Session? = null

    /**
     * Opens a Session on the Track, with its Backing Audio loaded and the Audio Engine in
     * Session mode, and closes the Session opened before, if it is still open. Deleted stems are
     * made again meanwhile (ADR 17). Background work of the Session (following the Track Pipeline
     * and the transport) runs in [scope] until [Session.close].
     * [onProgress] receives 0..1 while the Backing Audio is prepared.
     * @throws NotPlayableYet if the Track's Original Audio isn't stored yet (ADR 10).
     */
    suspend fun open(id: TrackId, scope: CoroutineScope, onProgress: (Float) -> Unit = {}): Session = mutex.withLock {
        val track = tracks.getForOpening(id)
        // Its screen may be torn down only after the new one opened; it must not touch the engine then.
        current?.close()
        val deps = Session.Dependencies(tracks, takes, files, engine, settings, mixes, clock, newTakeId)
        Session.open(track, scope, deps, onProgress).also { current = it }
    }
}

/**
 * The Track being opened, which needs its stems: if they were deleted, it goes back to Separating
 * with the same model and the Track Pipeline queues the job (ADR 17).
 * @throws NotPlayableYet if the Track Pipeline hasn't stored the Original Audio yet.
 */
private suspend fun TrackRepository.getForOpening(id: TrackId): Track {
    val track = checkNotNull(get(id)) { "no Track $id" }
    if (!track.hasOriginalAudio) throw NotPlayableYet(track)
    val deleted = track.pipeline as? TrackPipelineState.StemsDeleted ?: return track
    return updatePipeline(id, TrackPipelineState.Separating(deleted.model))
}

/** The Track has no stored Original Audio yet (see [Track.hasOriginalAudio]). */
class NotPlayableYet(val track: Track) : IllegalStateException("Track ${track.id.key} is not downloaded yet")
