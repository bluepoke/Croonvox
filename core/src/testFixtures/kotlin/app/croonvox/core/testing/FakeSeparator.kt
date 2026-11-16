// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.port.Separator
import app.croonvox.core.port.Stems

/** Records Separation requests, reports progress 0 → 1, and fails on demand. Writes no files. */
class FakeSeparator : Separator {
    data class Request(val originalAudio: AudioFile, val model: SeparationModelId, val into: Stems)

    val requests = mutableListOf<Request>()
    var failWith: Exception? = null

    override suspend fun separate(
        originalAudio: AudioFile,
        model: SeparationModelId,
        into: Stems,
        onProgress: (Float) -> Unit,
    ) {
        requests += Request(originalAudio, model, into)
        failWith?.let { throw it }
        onProgress(0f)
        onProgress(1f)
    }
}
