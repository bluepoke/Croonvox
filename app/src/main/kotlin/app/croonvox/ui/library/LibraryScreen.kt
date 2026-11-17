// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.library

import android.text.format.DateUtils
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.croonvox.core.library.LibraryEntry
import app.croonvox.core.model.TrackId
import app.croonvox.importing.Importer
import app.croonvox.ui.subtitle
import app.croonvox.ui.theme.CroonvoxTheme

@Composable
fun LibraryRoute(
    library: Library,
    importer: Importer,
    onOpen: (TrackId) -> Unit,
    onStorage: () -> Unit,
    onSearch: (() -> Unit)? = null,
) {
    val viewModel: LibraryViewModel = viewModel(factory = viewModelFactory { initializer { LibraryViewModel(library) } })
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importing by importer.importing.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importer.import(uri)
    }
    // The secondary import: the file has no vocals, so Separation is skipped (ADR 14).
    val vocalFreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importer.import(uri, vocalFree = true)
    }
    LibraryScreen(
        state = state,
        importing = importing > 0,
        onImport = { picker.launch(arrayOf("audio/*", "video/*")) },
        onImportVocalFree = { vocalFreePicker.launch(arrayOf("audio/*", "video/*")) },
        onOpen = onOpen,
        onStorage = onStorage,
        onSearch = onSearch,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    importing: Boolean = false,
    onImport: () -> Unit = {},
    onImportVocalFree: () -> Unit = {},
    onOpen: (TrackId) -> Unit = {},
    onStorage: () -> Unit = {},
    /** Opens the search; null when the app has no network Sources (local flavor). */
    onSearch: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    if (onSearch != null) TextButton(onClick = onSearch) { Text("Search songs") }
                    TextButton(onClick = onStorage) { Text("Storage") }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Import file without vocals") },
                                onClick = {
                                    menu = false
                                    onImportVocalFree()
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onImport) { Text("Import file") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (state) {
                LibraryUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is LibraryUiState.Loaded ->
                    if (state.entries.isEmpty()) EmptyLibrary() else TrackList(state.entries, onOpen)
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
private fun TrackList(entries: List<LibraryEntry>, onOpen: (TrackId) -> Unit) {
    // Bottom padding keeps the last row clear of the floating button.
    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
        items(entries, key = { it.track.id.key }) { entry ->
            ListItem(
                modifier = Modifier.clickable { onOpen(entry.track.id) },
                headlineContent = { Text(entry.track.title) },
                supportingContent = {
                    Column {
                        Text(entry.track.subtitle())
                        Text(
                            "${takeCount(entry.takeCount)} · ${lastSung(entry.track.lastSessionAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}

private fun takeCount(count: Int): String = when (count) {
    0 -> "No Takes"
    1 -> "1 Take"
    else -> "$count Takes"
}

private fun lastSung(at: Long?): String =
    if (at == null) "Not sung yet" else "Sung ${DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"

@Preview
@Composable
private fun EmptyLibraryPreview() {
    CroonvoxTheme { LibraryScreen(LibraryUiState.Loaded(emptyList())) }
}
