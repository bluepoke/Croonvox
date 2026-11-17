// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.track

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import android.text.format.Formatter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.lyrics.LyricsRepository
import app.croonvox.core.lyrics.MatchLyrics
import app.croonvox.core.lyrics.TrackLyrics
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.pipeline.TrackPipeline
import app.croonvox.core.pipeline.VocalFreeToggle
import app.croonvox.core.player.TrackPlayer
import app.croonvox.core.port.Playback
import app.croonvox.ui.formatTime
import app.croonvox.ui.label
import app.croonvox.ui.lyrics.details
import app.croonvox.ui.lyrics.name

@Composable
fun TrackDetailRoute(
    id: TrackId,
    tracks: TrackRepository,
    player: TrackPlayer,
    pipeline: TrackPipeline,
    lyrics: LyricsRepository,
    match: MatchLyrics,
    vocalFreeToggle: VocalFreeToggle,
    /** The Track was just added from a share, without the chance to mark it Vocal-Free first. */
    offerVocalFree: Boolean,
    onSing: () -> Unit,
    onChooseLyrics: () -> Unit,
) {
    val viewModel: TrackDetailViewModel = viewModel(
        factory = viewModelFactory { initializer { TrackDetailViewModel(id, tracks, player, pipeline, lyrics, match, vocalFreeToggle) } },
    )
    val confirmVocalFree by viewModel.confirmVocalFree.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val trackLyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val track by viewModel.track.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val loadProgress by viewModel.loadProgress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    // A Session opened from here takes over the Audio Engine; get the audio back on return.
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }
    // A share adds at once; offer Vocal-Free afterwards, while it still saves Separation (ADR 14).
    var offered by rememberSaveable { mutableStateOf(false) }
    val offerable = offerVocalFree && !offered && track?.let { !it.vocalFree && it.pipeline !is TrackPipelineState.Ready } == true
    LaunchedEffect(offerable) {
        if (!offerable) return@LaunchedEffect
        offered = true
        val result = snackbar.showSnackbar("No vocals in this song?", actionLabel = "Mark Vocal-Free", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.setVocalFree(true)
    }
    confirmVocalFree?.let { bytes ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { viewModel.confirmVocalFree(false) },
            title = { Text("Mark as Vocal-Free?") },
            text = {
                Text(
                    "The instrumental and the vocal stem are deleted, freeing ${Formatter.formatShortFileSize(context, bytes)}. " +
                        "You sing over the original audio from now on. Takes stay in sync.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmVocalFree(true) }) { Text("Delete stems") } },
            dismissButton = { TextButton(onClick = { viewModel.confirmVocalFree(false) }) { Text("Cancel") } },
        )
    }
    TrackDetailScreen(
        title = track?.title ?: "",
        subtitle = track?.artist.orEmpty(),
        pipeline = track?.pipeline,
        vocalFree = track?.vocalFree,
        onVocalFree = viewModel::setVocalFree,
        snackbar = snackbar,
        playback = playback,
        ready = ready,
        loadProgress = loadProgress,
        error = error,
        canSing = track?.hasOriginalAudio == true,
        onTogglePlay = viewModel::togglePlay,
        onSeek = viewModel::seekTo,
        onRetry = viewModel::retry,
        lyrics = trackLyrics,
        onConfirmLyrics = viewModel::confirmLyrics,
        onChooseLyrics = onChooseLyrics,
        onSing = {
            viewModel.pause()
            onSing()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(
    title: String,
    subtitle: String,
    pipeline: TrackPipelineState?,
    /** Null until the Track is loaded. */
    vocalFree: Boolean?,
    onVocalFree: (Boolean) -> Unit,
    snackbar: SnackbarHostState,
    playback: Playback,
    ready: Boolean,
    loadProgress: Float,
    error: String?,
    canSing: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    lyrics: TrackLyrics?,
    onConfirmLyrics: () -> Unit,
    onChooseLyrics: () -> Unit,
    onSing: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(title, maxLines = 1) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (canSing) ExtendedFloatingActionButton(onClick = onSing) { Text("Sing") }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (pipeline != null) PipelineStatus(pipeline, onRetry)
            if (vocalFree != null) VocalFreeToggle(vocalFree, onVocalFree)
            LyricsStatus(lyrics, onConfirmLyrics, onChooseLyrics)
            when {
                error != null -> Text("Can't play this Track: $error", color = MaterialTheme.colorScheme.error)
                !canSing -> Unit // nothing to play until the Original Audio is downloaded
                !ready -> {
                    Text("Preparing audio… ${(loadProgress * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { loadProgress }, modifier = Modifier.fillMaxWidth())
                }
                else -> PlaybackControls(playback, onTogglePlay, onSeek)
            }
        }
    }
}

@Composable
private fun PlaybackControls(playback: Playback, onTogglePlay: () -> Unit, onSeek: (Long) -> Unit) {
    // While dragging, show the thumb where the finger is; seek once on release.
    var dragMs by remember { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs.coerceAtLeast(1).toFloat()
    Slider(
        value = dragMs ?: playback.positionMs.toFloat().coerceIn(0f, duration),
        onValueChange = { dragMs = it },
        onValueChangeFinished = {
            dragMs?.let { onSeek(it.toLong()) }
            dragMs = null
        },
        valueRange = 0f..duration,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(dragMs?.toLong() ?: playback.positionMs), style = MaterialTheme.typography.bodySmall)
        Text(formatTime(playback.durationMs), style = MaterialTheme.typography.bodySmall)
    }
    if (playback.bufferedMs < playback.durationMs) {
        // Still decoding in the background; playback beyond this point waits for it.
        val buffered = playback.bufferedMs.toFloat() / duration
        LinearProgressIndicator(progress = { buffered }, modifier = Modifier.fillMaxWidth())
        Text(
            "Preparing the rest of the song… ${(buffered * 100).toInt()} %",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onTogglePlay) { Text(if (playback.playing) "Pause" else "Play") }
}

/** Where the Track is in the Track Pipeline, and what plays meanwhile (ADR 10). */
@Composable
private fun PipelineStatus(pipeline: TrackPipelineState, onRetry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (pipeline) {
        is TrackPipelineState.Separating -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pipeline.label(), style = MaterialTheme.typography.bodyMedium)
            val progress = pipeline.progress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            }
            Text("Playing the original audio until the instrumental is ready.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        is TrackPipelineState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${pipeline.label()}: ${pipeline.reason}", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
        is TrackPipelineState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pipeline.label(), style = MaterialTheme.typography.bodyMedium)
            pipeline.retryReason?.let { Text("Last try: $it", style = MaterialTheme.typography.bodySmall, color = muted) }
            val progress = pipeline.progress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        is TrackPipelineState.Ready -> Text(
            if (pipeline.model == null) "Playing the original audio" else "Playing the instrumental",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        // Opening the Track brings deleted stems back, so this shows only for a moment (ADR 17).
        TrackPipelineState.Added, TrackPipelineState.Downloaded, is TrackPipelineState.StemsDeleted -> Text(pipeline.label(), style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

/** The Vocal-Free toggle (GLOSSARY: Vocal-Free, ADR 14). */
@Composable
private fun VocalFreeToggle(vocalFree: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("No vocals (Vocal-Free)", style = MaterialTheme.typography.bodyMedium)
            Text(
                "For audio without a lead vocal, e.g. a karaoke version: vocal removal is skipped and you sing over the original audio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = vocalFree, onCheckedChange = onChange)
    }
}

/** Which Lyrics the Track has, and how to confirm or change them (ADR 6). */
@Composable
private fun LyricsStatus(lyrics: TrackLyrics?, onConfirm: () -> Unit, onChoose: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Lyrics", style = MaterialTheme.typography.titleSmall)
        when (lyrics) {
            null, TrackLyrics.Searching -> {
                // Waits for a network; the user needn't.
                Text("Searching…", style = MaterialTheme.typography.bodySmall, color = muted)
                OutlinedButton(onClick = onChoose) { Text("Search") }
            }
            is TrackLyrics.Suggested -> {
                Text("Suggested: ${lyrics.lyrics.name()}", style = MaterialTheme.typography.bodyMedium)
                Text(lyrics.lyrics.details(), style = MaterialTheme.typography.bodySmall, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConfirm) { Text("Confirm") }
                    OutlinedButton(onClick = onChoose) { Text("Choose another") }
                }
            }
            is TrackLyrics.Confirmed -> {
                Text(lyrics.lyrics.name(), style = MaterialTheme.typography.bodyMedium)
                Text(lyrics.lyrics.details(), style = MaterialTheme.typography.bodySmall, color = muted)
                OutlinedButton(onClick = onChoose) { Text("Change") }
            }
            is TrackLyrics.NotFound -> {
                Text("No Lyrics found for \"${lyrics.query.title}\".", style = MaterialTheme.typography.bodySmall, color = muted)
                OutlinedButton(onClick = onChoose) { Text("Search") }
            }
            is TrackLyrics.Failed -> {
                Text("Lyrics search failed: ${lyrics.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onChoose) { Text("Search") }
            }
        }
    }
}
