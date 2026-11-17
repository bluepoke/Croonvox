// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separation

import app.croonvox.core.model.AppDirectory
import app.croonvox.core.model.AudioFile
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.port.SeparationFailed
import app.croonvox.core.port.SeparationProgress
import app.croonvox.core.port.Separator
import app.croonvox.core.port.Stems
import app.croonvox.separation.audio.FlacCodec
import app.croonvox.separation.audio.PcmDecoder
import app.croonvox.separation.audio.UndecodableAudio
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/**
 * [Separator] that runs the [catalogue]'s models on the device (ADR 3, ADR 15): the model is
 * downloaded on first use, then the Separation runs checkpointed per segment. One Separation runs
 * at a time: a second waits, because each needs most of the CPU and up to about a GB of memory.
 */
class ModelSeparator(
    private val catalogue: List<SeparationModel>,
    private val models: ModelStore,
    private val decoder: PcmDecoder,
    private val flac: FlacCodec,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Separator {
    private val oneAtATime = Mutex()

    override suspend fun separate(
        originalAudio: AudioFile,
        model: SeparationModelId,
        into: Stems,
        checkpoint: AppDirectory,
        onProgress: (SeparationProgress) -> Unit,
    ) = oneAtATime.withLock {
        val entry = catalogue.firstOrNull { it.id == model }
            ?: throw SeparationFailed("unknown Separation Model '${model.value}'", retryable = false)
        val modelDir = try {
            runInterruptible(Dispatchers.IO) {
                models.install(entry) { onProgress(SeparationProgress(SeparationProgress.Stage.DownloadingModel, it)) }
            }
        } catch (e: ModelCorrupt) {
            throw SeparationFailed(e.message ?: "model download corrupt", retryable = false, e)
        } catch (e: IOException) {
            throw SeparationFailed("could not download ${entry.name}: ${e.message}", retryable = true, e)
        }
        try {
            runInterruptible(dispatcher) {
                CheckpointedSeparation(
                    original = File(originalAudio.path),
                    model = entry,
                    modelDir = modelDir,
                    instrumental = File(into.instrumental.path),
                    vocalStem = File(into.vocalStem.path),
                    checkpoint = File(checkpoint.path),
                    decoder = decoder,
                    flac = flac,
                    onProgress = { onProgress(SeparationProgress(SeparationProgress.Stage.Separating, it)) },
                ).run()
            }
        } catch (e: UndecodableAudio) {
            throw SeparationFailed(e.message ?: "the audio can't be decoded", retryable = false, e)
        } catch (e: IOException) {
            throw SeparationFailed(e.message ?: "could not store the stems", retryable = false, e)
        }
    }
}
