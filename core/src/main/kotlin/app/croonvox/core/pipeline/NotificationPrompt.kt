// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.core.pipeline

import app.croonvox.core.library.TrackRepository
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.session.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * When to ask for the permission that shows Separation's progress notification (#27, ADR 10):
 * once, when the first Track starts Separating, not at app start. After the user was asked ([markAsked]),
 * whatever they answered, it is never due again.
 */
class NotificationPrompt(private val tracks: TrackRepository, private val settings: SettingsRepository) {
    private val askedNow = MutableStateFlow(false)

    /** Whether to ask now: a Track is Separating and the user was never asked. */
    fun due(): Flow<Boolean> = flow {
        if (settings.notificationPermissionAsked()) return@flow emit(false)
        val separating = tracks.observeAll().map { all -> all.any { it.pipeline is TrackPipelineState.Separating } }
        emitAll(combine(separating, askedNow) { anySeparating, asked -> anySeparating && !asked })
    }.distinctUntilChanged()

    /** Records that the user answered the system prompt or declined the explanation, across restarts. */
    suspend fun markAsked() {
        settings.setNotificationPermissionAsked()
        askedNow.value = true
    }
}
