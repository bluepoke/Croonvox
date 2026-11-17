// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.session

import app.croonvox.core.library.TrackFiles
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.TrackId
import app.croonvox.core.player.NotPlayableYet
import app.croonvox.core.player.getForOpening
import app.croonvox.core.port.AudioEngine
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/** Opens Sessions: singing over a Track and recording Takes (GLOSSARY: Session, ADR 9). */
class Sessions(
    private val tracks: TrackRepository,
    private val takes: TakeRepository,
    private val files: TrackFiles,
    private val engine: AudioEngine,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newTakeId: () -> TakeId = { TakeId(UUID.randomUUID().toString()) },
) {
    /**
     * Opens a Session on the Track, with its Backing Audio loaded and the Audio Engine in
     * Session mode, and records it as the Track's last Session. Deleted stems are made again
     * meanwhile (ADR 17). Background work of the Session (following the Track Pipeline and the
     * transport) runs in [scope] until [Session.close].
     * @throws NotPlayableYet if the Track's Original Audio isn't stored yet (ADR 10).
     */
    suspend fun open(id: TrackId, scope: CoroutineScope): Session {
        val track = tracks.getForOpening(id)
        val session = Session.open(track, scope, Session.Dependencies(tracks, takes, files, engine, settings, clock, newTakeId))
        tracks.setLastSessionAt(id, clock())
        return session
    }
}
