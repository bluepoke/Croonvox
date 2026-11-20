// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.schicht8.meavox.core.library.Library
import eu.schicht8.meavox.core.library.LibraryEntry
import eu.schicht8.meavox.core.library.RenameTrack
import eu.schicht8.meavox.core.model.TrackId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(library: Library, private val renameTrack: RenameTrack) : ViewModel() {
    val state: StateFlow<LibraryUiState> = library.entries()
        .map { LibraryUiState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState.Loading)

    /** The dialog keeps Rename disabled for a blank title, so [RenameTrack] never refuses it here. */
    fun rename(track: TrackId, title: String, artist: String?) {
        viewModelScope.launch { renameTrack(track, title, artist) }
    }
}

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Loaded(val entries: List<LibraryEntry>) : LibraryUiState
}
