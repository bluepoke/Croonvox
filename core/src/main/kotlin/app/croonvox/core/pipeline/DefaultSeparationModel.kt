// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.pipeline

import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.session.SettingsRepository

/**
 * The Default Separation Model (GLOSSARY, ADR 19): chosen in Settings and pre-selected wherever a
 * model is picked. It is [fallback] (Fast) until the user chooses one, and again if the chosen one
 * is no longer in the [catalogue]. Changing it never re-separates existing Tracks.
 */
class DefaultSeparationModel(
    private val settings: SettingsRepository,
    private val catalogue: List<SeparationModelId>,
    private val fallback: SeparationModelId,
) {
    suspend fun get(): SeparationModelId = settings.defaultSeparationModel()?.takeIf { it in catalogue } ?: fallback

    suspend fun set(model: SeparationModelId) {
        require(model in catalogue) { "${model.value} is not in the catalogue" }
        settings.setDefaultSeparationModel(model)
    }
}
