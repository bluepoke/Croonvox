// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.mix

import eu.schicht8.meavox.core.export.export
import eu.schicht8.meavox.core.library.TrackFiles
import eu.schicht8.meavox.core.library.TrackRepository
import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.ExportStore
import eu.schicht8.meavox.core.port.ExportTags
import eu.schicht8.meavox.core.port.ExportedFile
import eu.schicht8.meavox.core.port.OfflineRenderer
import eu.schicht8.meavox.core.session.TakeRepository

/**
 * Export of a Mix (GLOSSARY: Export, ADR 8): the Offline Render of its Take's span, written to
 * `Music/MeaVox/`. The render uses the same DSP as Review and the Mix Preview.
 */
class ExportMix(
    private val tracks: TrackRepository,
    private val takes: TakeRepository,
    private val mixes: MixRepository,
    private val files: TrackFiles,
    private val renderer: OfflineRenderer,
    private val store: ExportStore,
) {
    /**
     * Renders and stores the Mix in [format]; [onProgress] receives 0..1 as it goes. Cancelling
     * removes the unfinished file.
     * @throws MixUnavailable if the Mix can't be rendered now (e.g. its Instrumental is being made again).
     */
    suspend fun export(id: MixId, format: ExportFormat, onProgress: (Float) -> Unit = {}): ExportedFile {
        val parts = resolveMix(id, mixes, tracks, takes, files)
        val render = parts.render(files)
        val tags = ExportTags("${parts.track.title} (${parts.mix.name})")
        return store.export(tags, format, render.sampleRate, render.frames, onProgress = onProgress) { sink ->
            renderer.render(render, sink)
        }
    }
}
