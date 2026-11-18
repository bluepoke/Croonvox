// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.separating

import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.pipeline.DefaultSeparationModel
import app.croonvox.separation.ModelStore
import app.croonvox.separation.SeparationModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A Separation Model as the model picker shows it (ADR 15, ADR 19). */
data class ModelOption(
    val id: SeparationModelId,
    val name: String,
    /** Separation time per second of audio on the weakest measured device: the expected duration. */
    val realTimeFactor: Float,
    val keepsBackingVocals: Boolean,
    /** Bytes to download before it can be used; null once it is installed. */
    val downloadBytes: Long?,
)

/** What the model pickers offer: the catalogue's models, with what each needs downloaded, and the Default Separation Model. */
class ModelChoices(
    private val catalogue: List<SeparationModel>,
    private val store: ModelStore,
    val default: DefaultSeparationModel,
) {
    /** Fastest first. Reads the disk to see which models are installed. */
    suspend fun options(): List<ModelOption> = withContext(Dispatchers.IO) {
        catalogue.map { model ->
            ModelOption(
                id = model.id,
                name = model.name,
                realTimeFactor = model.realTimeFactor,
                keepsBackingVocals = model.keepsBackingVocals,
                downloadBytes = model.download?.bytes?.takeUnless { store.isInstalled(model) },
            )
        }
    }

    fun nameOf(id: SeparationModelId): String = catalogue.firstOrNull { it.id == id }?.name ?: id.value
}
