// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.core.export

import eu.schicht8.meavox.core.port.ExportFormat
import eu.schicht8.meavox.core.port.ExportStore
import eu.schicht8.meavox.core.port.ExportTags
import eu.schicht8.meavox.core.port.ExportedFile
import eu.schicht8.meavox.core.port.PcmSink
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Writes one Export (ADR 8): [render] writes its audio into the new file, which is committed once it
 * returns and removed if it fails or is cancelled. [onProgress] receives 0..1 against [frames], or
 * against [estimatedFrames] when the length isn't known up front (then never 1 before the end).
 */
internal suspend fun ExportStore.export(
    tags: ExportTags,
    format: ExportFormat,
    sampleRate: Int,
    frames: Long?,
    estimatedFrames: Long? = frames,
    onProgress: (Float) -> Unit,
    render: suspend (PcmSink) -> Unit,
): ExportedFile {
    val writer = create(tags, format, sampleRate, frames)
    try {
        var written = 0L
        onProgress(0f)
        render { interleaved, n ->
            writer.write(interleaved, n)
            written += n
            val total = estimatedFrames ?: return@render
            if (total > 0) onProgress((written.toFloat() / total).coerceAtMost(if (frames == null) MAX_ESTIMATED else 1f))
        }
        return writer.commit().also { onProgress(1f) }
    } catch (e: Throwable) {
        withContext(NonCancellable) { writer.abort() }
        throw e
    }
}

/** Progress shown at most while the real length is still unknown. */
private const val MAX_ESTIMATED = 0.99f
