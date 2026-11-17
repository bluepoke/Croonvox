// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croonvox.core.library.TrackStorage
import app.croonvox.core.library.TrackUsage
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.Take
import app.croonvox.core.model.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The storage screen (ADR 8): usage per Track, measured again after each deletion. */
class StorageViewModel(private val storage: TrackStorage) : ViewModel() {
    private val _usage = MutableStateFlow<List<TrackUsage>?>(null)

    /** Every Track's usage, largest first; null while measuring for the first time. */
    val usage: StateFlow<List<TrackUsage>?> = _usage

    private val _message = MutableStateFlow<String?>(null)

    /** A one-off message for the user (shown once, then [messageShown]). */
    val message: StateFlow<String?> = _message

    init {
        runAndMeasure {}
    }

    fun deleteStems(track: TrackId, model: SeparationModelId) = runAndMeasure { storage.deleteStems(track, model) }

    fun deleteTake(take: Take) = runAndMeasure { storage.deleteTake(take) }

    fun deleteTrack(track: TrackId) = runAndMeasure { storage.deleteTrack(track) }

    fun messageShown() {
        _message.value = null
    }

    /** Runs [block], shows its error if it fails, then measures the usage again. */
    private fun runAndMeasure(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: e::class.simpleName
            }
            _usage.value = storage.usage()
        }
    }
}
