// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.session

import app.croonvox.core.model.InputDevice
import app.croonvox.core.model.SeparationModelId

/** User settings that outlive a Session. Implemented by the data layer; faked in tests. */
interface SettingsRepository {
    /** Whether recording starts after a countdown (ADR 9). Off until the user turns it on. */
    suspend fun countdownEnabled(): Boolean

    suspend fun setCountdownEnabled(enabled: Boolean)

    /** Level of the Backing Audio while a Take plays back, in dB; null until the user sets it. */
    suspend fun reviewBackingDb(): Float?

    suspend fun setReviewBackingDb(gainDb: Float)

    /** Input gain for one kind of microphone in dB (ADR 9); 0 until set. */
    suspend fun inputGainDb(device: InputDevice): Float

    suspend fun setInputGainDb(device: InputDevice, gainDb: Float)

    /** The Default Separation Model the user chose (ADR 19); null until chosen. See [app.croonvox.core.pipeline.DefaultSeparationModel]. */
    suspend fun defaultSeparationModel(): SeparationModelId?

    suspend fun setDefaultSeparationModel(model: SeparationModelId)
}
