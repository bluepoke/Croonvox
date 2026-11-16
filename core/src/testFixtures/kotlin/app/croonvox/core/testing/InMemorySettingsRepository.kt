// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.testing

import app.croonvox.core.model.InputDevice
import app.croonvox.core.session.SettingsRepository

class InMemorySettingsRepository : SettingsRepository {
    private var countdown = false
    private var reviewBackingDb: Float? = null

    override suspend fun reviewBackingDb(): Float? = reviewBackingDb

    override suspend fun setReviewBackingDb(gainDb: Float) {
        reviewBackingDb = gainDb
    }
    private val gains = mutableMapOf<InputDevice, Float>()

    override suspend fun countdownEnabled(): Boolean = countdown

    override suspend fun setCountdownEnabled(enabled: Boolean) {
        countdown = enabled
    }

    override suspend fun inputGainDb(device: InputDevice): Float = gains[device] ?: 0f

    override suspend fun setInputGainDb(device: InputDevice, gainDb: Float) {
        gains[device] = gainDb
    }
}
