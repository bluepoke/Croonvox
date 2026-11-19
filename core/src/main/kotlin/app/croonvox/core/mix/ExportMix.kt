// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.mix

import app.croonvox.core.library.TrackFiles
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.port.ExportFormat
import app.croonvox.core.port.ExportStore
import app.croonvox.core.port.ExportedFile
import app.croonvox.core.port.MixRenderer
import app.croonvox.core.session.TakeRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Export of a Mix (GLOSSARY: Export, ADR 8): the Offline Render of its Take's span, written to
 * `Music/Croonvox/`. The render uses the same DSP as Review and the Mix Preview.
 */
class ExportMix(
    private val tracks: TrackRepository,
    private val takes: TakeRepository,
    private val mixes: MixRepository,
    private val files: TrackFiles,
    private val renderer: MixRenderer,
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
        val writer = store.create("${parts.track.title} (${parts.mix.name})", format, render.sampleRate, render.frames)
        try {
            var written = 0L
            onProgress(0f)
            renderer.render(render) { interleaved, frames ->
                writer.write(interleaved, frames)
                written += frames
                if (render.frames > 0) onProgress((written.toFloat() / render.frames).coerceAtMost(1f))
            }
            return writer.commit().also { onProgress(1f) }
        } catch (e: Throwable) {
            withContext(NonCancellable) { writer.abort() }
            throw e
        }
    }
}
