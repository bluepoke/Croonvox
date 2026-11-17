// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.croonvox.core.library.Library
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.TrackId
import app.croonvox.core.port.MediaUnavailable
import app.croonvox.core.port.Source
import app.croonvox.core.port.SourceCandidate
import app.croonvox.ui.formatTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Searching the network Sources, previewing a result and adding it as a Track (ADR 1). */
class SearchViewModel(private val sources: List<Source>, private val library: Library) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    private val _selected = MutableStateFlow(setOfNotNull(sources.firstOrNull()?.id))
    /** The Sources to search; the first one alone at the start. */
    val selected: StateFlow<Set<SourceId>> = _selected
    private val _results = MutableStateFlow<List<SourceCandidate>?>(null)
    val results: StateFlow<List<SourceCandidate>?> = _results
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private val _errors = MutableStateFlow<List<String>>(emptyList())
    /** One line per Source that couldn't be searched. */
    val errors: StateFlow<List<String>> = _errors
    private val _preview = MutableStateFlow<SourceCandidate?>(null)
    val preview: StateFlow<SourceCandidate?> = _preview
    val player = PreviewPlayer()

    fun setQuery(value: String) {
        _query.value = value
    }

    fun toggle(source: SourceId) {
        val now = _selected.value
        _selected.value = if (source in now) (now - source).ifEmpty { now } else now + source
    }

    fun search() {
        val query = _query.value.trim()
        if (query.isEmpty() || _searching.value) return
        _searching.value = true
        viewModelScope.launch {
            val chosen = sources.filter { it.id in _selected.value }
            val outcomes = chosen.map { source ->
                async { runCatching { source.search(query) }.onFailure { if (it is CancellationException) throw it } }
            }.awaitAll()
            _results.value = outcomes.flatMap { it.getOrDefault(emptyList()) }
            _errors.value = outcomes.zip(chosen).mapNotNull { (outcome, source) ->
                outcome.exceptionOrNull()?.let { "${source.name}: ${it.message}" }
            }
            _searching.value = false
        }
    }

    private var resolving: Job? = null

    /** Opens the preview of [candidate] and starts streaming its audio. */
    fun preview(candidate: SourceCandidate) {
        resolving?.cancel() // an earlier preview still resolving must not start playing
        player.stop()
        _preview.value = candidate
        resolving = viewModelScope.launch {
            val source = sources.first { it.id == candidate.id.source }
            try {
                val uri = source.resolve(candidate.id.sourceTrackId).audioUri
                if (_preview.value == candidate) player.play(uri)
            } catch (e: MediaUnavailable) {
                _errors.value = listOf("${source.name}: ${e.message}")
                player.stop()
            }
        }
    }

    fun closePreview() {
        resolving?.cancel()
        player.stop()
        _preview.value = null
    }

    /** The app went to the background: a preview doesn't play on there. */
    fun onBackground() = player.pause()

    /** Adds [candidate], marked Vocal-Free if [vocalFree] (ADR 14); an existing Track opens unchanged. */
    fun add(candidate: SourceCandidate, vocalFree: Boolean, then: (TrackId) -> Unit) {
        resolving?.cancel()
        player.stop()
        viewModelScope.launch { then(library.add(candidate, vocalFree).id) }
    }

    override fun onCleared() = player.stop()
}

@Composable
fun SearchRoute(sources: List<Source>, library: Library, onAdded: (TrackId) -> Unit) {
    val viewModel: SearchViewModel = viewModel(factory = viewModelFactory { initializer { SearchViewModel(sources, library) } })
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val errors by viewModel.errors.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val playing by viewModel.player.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    SearchScreen(
        sources = sources,
        query = query,
        selected = selected,
        results = results,
        searching = searching,
        errors = errors,
        onQuery = viewModel::setQuery,
        onToggleSource = viewModel::toggle,
        onSearch = viewModel::search,
        onPreview = viewModel::preview,
    )
    preview?.let { candidate ->
        PreviewSheet(
            candidate = candidate,
            sourceName = sources.nameOf(candidate),
            player = playing,
            onTogglePlay = viewModel.player::toggle,
            onAdd = { viewModel.add(candidate, vocalFree = false, onAdded) },
            onAddVocalFree = { viewModel.add(candidate, vocalFree = true, onAdded) },
            onDismiss = viewModel::closePreview,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    sources: List<Source>,
    query: String,
    selected: Set<SourceId>,
    results: List<SourceCandidate>?,
    searching: Boolean,
    errors: List<String>,
    onQuery: (String) -> Unit,
    onToggleSource: (SourceId) -> Unit,
    onSearch: () -> Unit,
    onPreview: (SourceCandidate) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Search songs") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                label = { Text("Artist and title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (source in sources) {
                    FilterChip(source.id in selected, { onToggleSource(source.id) }, label = { Text(source.name) })
                }
                Button(onClick = onSearch, enabled = query.isNotBlank() && !searching) { Text("Search") }
            }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            for (error in errors) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (results?.isEmpty() == true && errors.isEmpty()) Text("Nothing found. Try other words or another Source.")
            LazyColumn(Modifier.weight(1f)) {
                items(results.orEmpty(), key = { it.id.key }) { candidate ->
                    ListItem(
                        leadingContent = { RemoteImage(candidate.thumbnailUrl, Modifier.width(96.dp).aspectRatio(16f / 9f)) },
                        headlineContent = { Text(candidate.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(candidate.details(sources.nameOf(candidate)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { onPreview(candidate) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** "Uploader · 4:18 · YouTube" */
private fun SourceCandidate.details(sourceName: String?): String =
    listOfNotNull(artist, durationMs?.let(::formatTime), sourceName).joinToString(" · ")

private fun List<Source>.nameOf(candidate: SourceCandidate): String? = firstOrNull { it.id == candidate.id.source }?.name

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(
    candidate: SourceCandidate,
    sourceName: String?,
    player: PreviewPlayer.State,
    onTogglePlay: () -> Unit,
    onAdd: () -> Unit,
    /** The secondary add: the result has no vocals, so Separation is skipped (ADR 14). */
    onAddVocalFree: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteImage(candidate.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Text(candidate.title, style = MaterialTheme.typography.titleMedium)
            Text(
                candidate.details(sourceName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (player) {
                PreviewPlayer.State.Idle, PreviewPlayer.State.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                PreviewPlayer.State.Failed -> Text("The preview can't be played.", color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onTogglePlay,
                    enabled = player == PreviewPlayer.State.Playing || player == PreviewPlayer.State.Paused,
                ) { Text(if (player == PreviewPlayer.State.Playing) "Pause" else "Play") }
                Button(onClick = onAdd) { Text("Add to Library") }
            }
            TextButton(onClick = onAddVocalFree) { Text("Add without vocal removal") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
