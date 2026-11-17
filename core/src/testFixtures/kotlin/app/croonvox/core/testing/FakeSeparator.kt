// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.model.AppDirectory
import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.port.SeparationProgress
import app.croonvox.core.port.Separator
import app.croonvox.core.port.Stems

/** Records Separation requests, reports [progress], and fails on demand. Writes no files. */
class FakeSeparator : Separator {
    data class Request(
        val originalAudio: AudioFile,
        val model: SeparationModelId,
        val into: Stems,
        val checkpoint: AppDirectory,
    )

    val requests = mutableListOf<Request>()
    var failWith: Throwable? = null

    /** Reported in order by each Separation, before it fails or finishes. */
    var progress = listOf(
        SeparationProgress(SeparationProgress.Stage.Separating, 0f),
        SeparationProgress(SeparationProgress.Stage.Separating, 1f),
    )

    /** Runs after each reported [progress], e.g. to observe the Track meanwhile. */
    var afterEachProgress: suspend (SeparationProgress) -> Unit = {}

    override suspend fun separate(
        originalAudio: AudioFile,
        model: SeparationModelId,
        into: Stems,
        checkpoint: AppDirectory,
        onProgress: (SeparationProgress) -> Unit,
    ) {
        requests += Request(originalAudio, model, into, checkpoint)
        for (p in progress) {
            onProgress(p)
            afterEachProgress(p)
        }
        failWith?.let { throw it }
    }
}
