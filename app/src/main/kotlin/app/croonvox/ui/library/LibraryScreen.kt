// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.croonvox.core.library.Library
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.importing.Importer
import app.croonvox.ui.subtitle
import app.croonvox.ui.theme.CroonvoxTheme

@Composable
fun LibraryRoute(library: Library, importer: Importer, onOpen: (TrackId) -> Unit) {
    val viewModel: LibraryViewModel = viewModel(factory = viewModelFactory { initializer { LibraryViewModel(library) } })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importing by importer.importing.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importer.import(uri)
    }
    LibraryScreen(
        state = state,
        importing = importing > 0,
        onImport = { picker.launch(arrayOf("audio/*", "video/*")) },
        onOpen = onOpen,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    importing: Boolean = false,
    onImport: () -> Unit = {},
    onOpen: (TrackId) -> Unit = {},
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Library") }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onImport) { Text("Import file") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (state) {
                LibraryUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is LibraryUiState.Loaded ->
                    if (state.tracks.isEmpty()) EmptyLibrary() else TrackList(state.tracks, onOpen)
            }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No songs yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Import an audio or video file, or share one to Croonvox from another app.",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TrackList(tracks: List<Track>, onOpen: (TrackId) -> Unit) {
    // Bottom padding keeps the last row clear of the floating button.
    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
        items(tracks, key = { it.id.key }) { track ->
            ListItem(
                modifier = Modifier.clickable { onOpen(track.id) },
                headlineContent = { Text(track.title) },
                supportingContent = { Text(track.subtitle()) },
            )
        }
    }
}

@Preview
@Composable
private fun EmptyLibraryPreview() {
    CroonvoxTheme { LibraryScreen(LibraryUiState.Loaded(emptyList())) }
}
