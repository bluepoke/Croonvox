// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.storage

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.croonvox.core.library.TrackStorage
import app.croonvox.core.library.TrackUsage
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.Take
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState

@Composable
fun StorageRoute(storage: TrackStorage, modelNames: Map<SeparationModelId, String>) {
    val viewModel: StorageViewModel = viewModel(factory = viewModelFactory { initializer { StorageViewModel(storage) } })
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    StorageScreen(
        usage = usage,
        modelNames = modelNames,
        snackbar = snackbar,
        actions = StorageActions(viewModel::deleteStems, viewModel::deleteTake, viewModel::deleteTrack),
    )
}

data class StorageActions(
    val onDeleteStems: (TrackId, SeparationModelId) -> Unit = { _, _ -> },
    val onDeleteTake: (Take) -> Unit = {},
    val onDeleteTrack: (TrackId) -> Unit = {},
)

/** What is about to be deleted, waiting for the user to confirm. */
private sealed interface Deletion {
    data class Stems(val track: TrackUsage, val model: SeparationModelId) : Deletion
    data class OneTake(val take: Take) : Deletion
    data class WholeTrack(val track: TrackUsage) : Deletion
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    usage: List<TrackUsage>?,
    modelNames: Map<SeparationModelId, String>,
    snackbar: SnackbarHostState,
    actions: StorageActions,
) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    var pending by remember { mutableStateOf<Deletion?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Storage") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (usage == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("${size(usage.sumOf { it.totalBytes })} in ${usage.size} songs", style = MaterialTheme.typography.titleMedium)
            }
            items(usage, key = { it.track.id.key }) { track ->
                TrackUsageCard(track, modelNames, ::size, onDelete = { pending = it })
            }
        }
    }

    pending?.let { deletion ->
        val (title, text) = when (deletion) {
            is Deletion.Stems -> "Delete the stems of ${deletion.track.track.title}?" to
                "Frees ${size(deletion.track.stemBytes[deletion.model] ?: 0)}. Vocals are removed again from the Original Audio " +
                "when you next open the song, which takes a few minutes. Takes stay in sync."
            is Deletion.OneTake -> "Delete ${deletion.take.name}?" to "The recording is removed for good."
            is Deletion.WholeTrack -> "Delete ${deletion.track.track.title}?" to
                "Frees ${size(deletion.track.totalBytes)}. The song, its ${deletion.track.takes.size} Takes and its Lyrics are " +
                "removed for good."
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    when (deletion) {
                        is Deletion.Stems -> actions.onDeleteStems(deletion.track.track.id, deletion.model)
                        is Deletion.OneTake -> actions.onDeleteTake(deletion.take)
                        is Deletion.WholeTrack -> actions.onDeleteTrack(deletion.track.track.id)
                    }
                    pending = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrackUsageCard(
    usage: TrackUsage,
    modelNames: Map<SeparationModelId, String>,
    size: (Long) -> String,
    onDelete: (Deletion) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(usage.track.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(size(usage.totalBytes), style = MaterialTheme.typography.titleSmall)
        }
        UsageRow("Original Audio", size(usage.originalAudioBytes))
        usage.stemBytes.forEach { (model, bytes) ->
            // Stems still being written by Separation can't be deleted.
            val separating = (usage.track.pipeline as? TrackPipelineState.Separating)?.model == model
            UsageRow(
                "Stems (${modelNames[model] ?: model.value})${if (separating) ", in progress" else ""}",
                size(bytes),
                onDelete = if (separating) null else ({ onDelete(Deletion.Stems(usage, model)) }),
            )
        }
        if (usage.takes.isNotEmpty()) UsageRow("Takes (${usage.takes.size})", size(usage.takes.sumOf { it.bytes }))
        usage.takes.forEach { take ->
            UsageRow("   ${take.take.name}", size(take.bytes), onDelete = { onDelete(Deletion.OneTake(take.take)) })
        }
        OutlinedButton(onClick = { onDelete(Deletion.WholeTrack(usage)) }) { Text("Delete song") }
    }
}

@Composable
private fun UsageRow(label: String, size: String, onDelete: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(size, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
    }
}
