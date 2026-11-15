// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.croonvox.core.library.Library
import app.croonvox.core.model.Track
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(library: Library) : ViewModel() {
    val state: StateFlow<LibraryUiState> = library.tracks()
        .map { LibraryUiState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState.Loading)
}

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Loaded(val tracks: List<Track>) : LibraryUiState
}
