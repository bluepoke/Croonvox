// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.session

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.croonvox.core.library.TrackRepository
import app.croonvox.core.lyrics.Lrc
import app.croonvox.core.lyrics.LyricsOffset
import app.croonvox.core.lyrics.LyricsRepository
import app.croonvox.core.lyrics.MatchLyrics
import app.croonvox.core.lyrics.TrackLyrics
import app.croonvox.core.lyrics.candidate
import app.croonvox.core.model.BackingChoice
import app.croonvox.core.model.InputDevice
import app.croonvox.core.model.OutputRoute
import app.croonvox.core.model.SeparationModelId
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.Track
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.TrackPipelineState
import app.croonvox.core.model.Transpose
import app.croonvox.core.pipeline.TrackPipeline
import app.croonvox.core.pipeline.VocalFreeToggle
import app.croonvox.core.port.CapturedTake
import app.croonvox.core.port.InputLevel
import app.croonvox.core.port.LyricsCandidate
import app.croonvox.core.port.Playback
import app.croonvox.core.session.BackingAudio
import app.croonvox.core.session.Session
import app.croonvox.core.session.SessionState
import app.croonvox.core.session.Sessions
import app.croonvox.separating.ModelChoices
import app.croonvox.ui.formatTime
import app.croonvox.ui.label
import app.croonvox.ui.lyrics.KaraokeView
import app.croonvox.ui.lyrics.PageView
import app.croonvox.ui.lyrics.details
import app.croonvox.ui.lyrics.name
import app.croonvox.ui.models.DownloadNote
import app.croonvox.ui.models.ModelPicker
import app.croonvox.ui.models.confirmLabel
import app.croonvox.ui.theme.CroonvoxButtonShape
import app.croonvox.ui.theme.CroonvoxTheme
import app.croonvox.ui.verticalScrollbar
import kotlin.math.log10
import kotlin.math.roundToInt

/** The one screen per Track: its status and Lyrics, and the Session on it (#34). */
@Composable
fun SessionRoute(
    id: TrackId,
    tracks: TrackRepository,
    sessions: Sessions,
    pipeline: TrackPipeline,
    lyrics: LyricsRepository,
    match: MatchLyrics,
    lyricsOffset: LyricsOffset,
    vocalFreeToggle: VocalFreeToggle,
    choices: ModelChoices,
    onChooseLyrics: () -> Unit,
) {
    val viewModel: SessionViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SessionViewModel(id, tracks, sessions, pipeline, lyrics, match, lyricsOffset, vocalFreeToggle, choices) }
        },
    )
    val context = LocalContext.current
    fun micAllowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // The permission is asked for only when the user first records (spec #1, story 85).
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.onMicrophoneAllowed()
            viewModel.record()
        } else {
            viewModel.onMicrophoneDenied()
        }
    }
    val track by viewModel.track.collectAsStateWithLifecycle()
    val loadProgress by viewModel.loadProgress.collectAsStateWithLifecycle()
    val openError by viewModel.openError.collectAsStateWithLifecycle()
    val backing by viewModel.backing.collectAsStateWithLifecycle()
    // Once allowed, open the microphone right away so the meter shows before recording.
    LaunchedEffect(backing != null) {
        if (backing != null && micAllowed()) viewModel.onMicrophoneAllowed()
    }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    val modelPick by viewModel.modelPick.collectAsStateWithLifecycle()
    modelPick?.let { pick -> ModelPickDialog(pick, track, viewModel::pickModel, viewModel::confirmModelPick) }
    val confirmVocalFree by viewModel.confirmVocalFree.collectAsStateWithLifecycle()
    confirmVocalFree?.let { bytes ->
        AlertDialog(
            onDismissRequest = { viewModel.confirmVocalFree(false) },
            title = { Text("Mark as Vocal-Free?") },
            text = {
                Text(
                    "The instrumental and the vocal stem are deleted, freeing ${Formatter.formatShortFileSize(context, bytes)}. " +
                        "You sing over the original audio from now on. Takes stay in sync.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmVocalFree(true) }, shape = CroonvoxButtonShape) { Text("Delete stems") } },
            dismissButton = { TextButton(onClick = { viewModel.confirmVocalFree(false) }, shape = CroonvoxButtonShape) { Text("Cancel") } },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val gain by viewModel.inputGainDb.collectAsStateWithLifecycle()
    val countdown by viewModel.countdownEnabled.collectAsStateWithLifecycle()
    val reviewBacking by viewModel.reviewBackingDb.collectAsStateWithLifecycle()
    val takes by viewModel.takes.collectAsStateWithLifecycle()
    val trackLyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val offsetMs by viewModel.lyricsOffsetMs.collectAsStateWithLifecycle()
    val karaoke by viewModel.karaoke.collectAsStateWithLifecycle()
    val transpose by viewModel.transpose.collectAsStateWithLifecycle()

    SessionScreen(
        openError = openError,
        snackbar = snackbar,
        content = SessionContent(
            track, loadProgress, state, backing, playback, input, gain, countdown, reviewBacking, takes, trackLyrics, offsetMs, karaoke, transpose,
            modelName = choices::nameOf,
        ),
        actions = SessionActions(
            onTogglePlay = viewModel::togglePlay,
            onSeek = viewModel::seekTo,
            onRecord = { if (micAllowed()) viewModel.record() else permission.launch(Manifest.permission.RECORD_AUDIO) },
            onStop = viewModel::stop,
            onReplay = viewModel::replay,
            onPause = viewModel::pause,
            onKeep = viewModel::keep,
            onDiscard = viewModel::discard,
            onRecordAgain = viewModel::recordAgain,
            onCountdown = viewModel::setCountdownEnabled,
            onGain = viewModel::setInputGain,
            onReviewBalance = viewModel::setReviewBackingDb,
            onPlayTake = viewModel::playTake,
            onRename = viewModel::rename,
            onDelete = viewModel::delete,
            onShowKaraoke = viewModel::showKaraoke,
            onNudgeLyricsOffset = viewModel::nudgeLyricsOffset,
            onResetLyricsOffset = viewModel::resetLyricsOffset,
            onTranspose = viewModel::setTranspose,
            onChooseBacking = viewModel::chooseBacking,
            onRetry = viewModel::retry,
            onConfirmLyrics = viewModel::confirmLyrics,
            onChooseLyrics = onChooseLyrics,
            onVocalFree = viewModel::setVocalFree,
            onReseparate = viewModel::startReseparating,
        ),
    )
}

/**
 * The model picker for re-separating the Track or unmarking it Vocal-Free (ADR 19), with the
 * Default Separation Model pre-selected. Re-separating with the model the Track already has (or is
 * being separated with) does nothing, so it can't be confirmed.
 */
@Composable
private fun ModelPickDialog(pick: ModelPick, track: Track?, onPick: (SeparationModelId) -> Unit, onConfirm: (Boolean) -> Unit) {
    val current = when (val state = track?.pipeline) {
        is TrackPipelineState.Ready -> state.model
        is TrackPipelineState.Separating -> state.model
        else -> null
    }
    val selected = pick.options.firstOrNull { it.id == pick.selected }
    val reseparate = pick.purpose == ModelPick.Purpose.Reseparate
    AlertDialog(
        onDismissRequest = { onConfirm(false) },
        title = { Text(if (reseparate) "Remove the vocals again" else "Remove the vocals") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reseparate) {
                    Text(
                        "The original audio plays until the new instrumental is ready. Takes stay in sync.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ModelPicker(pick.options, pick.selected, onPick, track?.durationMs, current = current.takeIf { reseparate })
                DownloadNote(selected)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(true) },
                enabled = !(reseparate && pick.selected == current),
                shape = CroonvoxButtonShape,
            ) { Text(confirmLabel(if (reseparate) "re-separate" else "remove vocals", selected)) }
        },
        dismissButton = { TextButton(onClick = { onConfirm(false) }, shape = CroonvoxButtonShape) { Text("Cancel") } },
    )
}

data class SessionContent(
    /** Null until the Track is loaded. */
    val track: Track?,
    /** How much of the Backing Audio is prepared while the Session opens, 0..1. */
    val loadProgress: Float,
    val state: SessionState,
    /** What plays now; null until the Session is open. */
    val backing: BackingAudio?,
    val playback: Playback,
    val input: InputLevel,
    val inputGainDb: Float,
    val countdownEnabled: Boolean,
    /** Backing Audio level while a Take plays back, in dB (Review balance). */
    val reviewBackingDb: Float,
    val takes: List<Take>,
    val lyrics: TrackLyrics?,
    val lyricsOffsetMs: Long,
    /** Karaoke View rather than Page View, when the Lyrics are synced. */
    val karaoke: Boolean,
    /** The Transpose control: free playback and the next Take. */
    val transpose: Transpose,
    /** A Separation Model's name in the picker ("Fast", "Best"). */
    val modelName: (SeparationModelId) -> String = { it.value },
)

data class SessionActions(
    val onTogglePlay: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onRecord: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onReplay: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onKeep: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onRecordAgain: () -> Unit = {},
    val onCountdown: (Boolean) -> Unit = {},
    val onGain: (Float) -> Unit = {},
    val onReviewBalance: (Float) -> Unit = {},
    val onPlayTake: (TakeId) -> Unit = {},
    val onRename: (TakeId, String) -> Unit = { _, _ -> },
    val onDelete: (TakeId) -> Unit = {},
    val onShowKaraoke: (Boolean) -> Unit = {},
    val onNudgeLyricsOffset: (Long) -> Unit = {},
    val onResetLyricsOffset: () -> Unit = {},
    val onTranspose: (Transpose) -> Unit = {},
    val onChooseBacking: (BackingChoice) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onConfirmLyrics: () -> Unit = {},
    val onChooseLyrics: () -> Unit = {},
    val onVocalFree: (Boolean) -> Unit = {},
    val onReseparate: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    openError: String?,
    snackbar: SnackbarHostState,
    content: SessionContent,
    actions: SessionActions,
) {
    Scaffold(
        topBar = { TopAppBar(title = { TrackTitle(content.track) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Lyrics on top, a few lines tall; the Track's status and the Session's controls take the rest
        // below. Each part scrolls on its own.
        Column(Modifier.fillMaxSize().padding(padding)) {
            LyricsPane(content, actions, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
            HorizontalDivider()
            val controlsScroll = rememberScrollState()
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .verticalScrollbar(controlsScroll, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    .verticalScroll(controlsScroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SessionControls(openError, content, actions)
            }
        }
    }
}

/** Karaoke View for synced Lyrics (switchable to Page View), Page View otherwise. */
@Composable
private fun LyricsPane(content: SessionContent, actions: SessionActions, modifier: Modifier) {
    val synced = content.lyrics.candidate()?.synced
    val lines = remember(synced) { synced?.let(Lrc::parse).orEmpty() }
    if (lines.isEmpty()) return PageView(content.lyrics, modifier)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Choice("Karaoke", content.karaoke, index = 0, count = 2) { actions.onShowKaraoke(true) }
            Choice("Page", !content.karaoke, index = 1, count = 2) { actions.onShowKaraoke(false) }
        }
        if (content.karaoke) {
            KaraokeView(
                lines, content.playback, content.lyricsOffsetMs, actions.onNudgeLyricsOffset, actions.onResetLyricsOffset,
                Modifier.fillMaxWidth(),
            )
        } else {
            PageView(content.lyrics, Modifier.fillMaxWidth())
        }
    }
}

/** One segment of a switch (Karaoke | Page, the Backing Audio): 6 dp corners, the chosen one in muted gold, no check mark. */
@Composable
private fun SingleChoiceSegmentedButtonRowScope.Choice(
    label: String,
    selected: Boolean,
    index: Int,
    count: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SegmentedButton(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        shape = SegmentedButtonDefaults.itemShape(index, count, baseShape = MaterialTheme.shapes.extraSmall),
        icon = {},
    ) { Text(label) }
}

/** The Track's title, with its artist below if known. */
@Composable
private fun TrackTitle(track: Track?) {
    Column {
        Text(track?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        track?.artist?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The lower half, in the order of #34: status, Lyrics, Backing Audio, the Session, Vocal-Free. */
@Composable
private fun SessionControls(openError: String?, content: SessionContent, actions: SessionActions) {
    val track = content.track ?: return LinearProgressIndicator(Modifier.fillMaxWidth())
    PipelineStatus(track.pipeline, actions.onRetry)
    VocalRemoval(track, content.modelName, actions.onReseparate)
    LyricsStatus(content.lyrics, actions.onConfirmLyrics, actions.onChooseLyrics)
    when {
        openError != null -> Text("Can't play this Track: $openError", color = MaterialTheme.colorScheme.error)
        // Nothing to play until the Original Audio is stored; the Session opens by itself then (ADR 10).
        !track.hasOriginalAudio -> Unit
        content.backing == null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Preparing audio… ${(content.loadProgress * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { content.loadProgress }, modifier = Modifier.fillMaxWidth())
        }
        else -> {
            // A Vocal-Free Track has no Instrumental: it always plays its Original Audio (ADR 14).
            if (!track.vocalFree) BackingChoiceRow(track, content.backing, actions.onChooseBacking)
            Position(content.state, content.playback, actions.onSeek)
            Transport(content.state, content.playback, content.reviewBackingDb, actions)
            TransposeControl(content.transpose, content.state, actions.onTranspose)
            Microphone(content.input, content.inputGainDb, actions.onGain)
            SettingRow("Countdown before recording (${Session.COUNTDOWN_SECONDS} s)", content.countdownEnabled, actions.onCountdown)
            Takes(content.takes, content.state, actions)
        }
    }
    VocalFreeToggle(track.vocalFree, actions.onVocalFree)
}

/**
 * Instrumental | Original Audio, kept for the Track. The Instrumental can't be chosen until vocal
 * removal is done; the Original Audio plays meanwhile.
 */
@Composable
private fun BackingChoiceRow(track: Track, playing: BackingAudio, onChoose: (BackingChoice) -> Unit) {
    val available = track.instrumentalModel != null
    val shown = if (available) track.backingChoice else BackingChoice.OriginalAudio
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Sing over", style = rowLabel())
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Choice("Instrumental", shown == BackingChoice.Instrumental, index = 0, count = 2, enabled = available) {
                onChoose(BackingChoice.Instrumental)
            }
            Choice("Original Audio", shown == BackingChoice.OriginalAudio, index = 1, count = 2) {
                onChoose(BackingChoice.OriginalAudio)
            }
        }
        val playingShown = (playing is BackingAudio.Instrumental) == (shown == BackingChoice.Instrumental)
        when {
            !available -> Text("The Instrumental can be chosen once vocal removal is done.", style = MaterialTheme.typography.bodySmall, color = muted)
            // Never under a Take being recorded, reviewed or played back (ADR 20).
            !playingShown -> Text("Switches once this Take is done.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

/** Where the Track is in the Track Pipeline (ADR 10); what plays is the Backing Audio choice's business. */
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
        }
        is TrackPipelineState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${pipeline.label()}: ${pipeline.reason}", color = CroonvoxTheme.colors.record, style = MaterialTheme.typography.bodyMedium)
            NeutralOutlinedButton("Retry", onRetry, Modifier.height(44.dp))
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
        is TrackPipelineState.Ready -> Unit
        // Opening the Track brings deleted stems back, so this shows only for a moment (ADR 17).
        TrackPipelineState.Added, TrackPipelineState.Downloaded, is TrackPipelineState.StemsDeleted ->
            Text(pipeline.label(), style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

/**
 * Which Separation Model removes the Track's vocals, and the way to pick another (ADR 19). Not for a
 * Vocal-Free Track, which isn't separated (ADR 14), nor before the Original Audio is stored.
 */
@Composable
private fun VocalRemoval(track: Track, modelName: (SeparationModelId) -> String, onReseparate: () -> Unit) {
    if (track.vocalFree || !track.hasOriginalAudio) return
    val model = when (val state = track.pipeline) {
        is TrackPipelineState.Ready -> state.model
        is TrackPipelineState.Separating -> state.model
        is TrackPipelineState.StemsDeleted -> state.model
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Vocal removal", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            model?.let { Text(modelName(it), style = rowLabel()) }
        }
        NeutralOutlinedButton("Re-separate", onReseparate, Modifier.height(44.dp))
    }
}

/** Which Lyrics the Track has, and how to confirm or change them (ADR 6). */
@Composable
private fun LyricsStatus(lyrics: TrackLyrics?, onConfirm: () -> Unit, onChoose: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Lyrics", style = MaterialTheme.typography.labelLarge, color = muted)
        when (lyrics) {
            null, TrackLyrics.Searching -> {
                // Waits for a network; the user needn't.
                Text("Searching…", style = small, color = muted)
                NeutralOutlinedButton("Search", onChoose, Modifier.height(44.dp))
            }
            is TrackLyrics.Suggested -> {
                Text("Suggested: ${lyrics.lyrics.name()}", style = rowLabel())
                Text(lyrics.lyrics.details(), style = small, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Gold text, not a gold surface: Keep is the screen's one gold surface.
                    OutlinedButton(onClick = onConfirm, shape = CroonvoxButtonShape, modifier = Modifier.height(44.dp)) { Text("Confirm") }
                    NeutralOutlinedButton("Choose another", onChoose, Modifier.height(44.dp))
                }
            }
            is TrackLyrics.Confirmed -> {
                Text(lyrics.lyrics.name(), style = rowLabel())
                Text(lyrics.lyrics.details(), style = small, color = muted)
                NeutralOutlinedButton("Change", onChoose, Modifier.height(44.dp))
            }
            is TrackLyrics.NotFound -> {
                Text("No Lyrics found for \"${lyrics.query.title}\".", style = small, color = muted)
                NeutralOutlinedButton("Search", onChoose, Modifier.height(44.dp))
            }
            is TrackLyrics.Failed -> {
                Text("Lyrics search failed: ${lyrics.reason}", style = small, color = CroonvoxTheme.colors.record)
                NeutralOutlinedButton("Search", onChoose, Modifier.height(44.dp))
            }
        }
    }
}

/** The Vocal-Free toggle (GLOSSARY: Vocal-Free, ADR 14). */
@Composable
private fun VocalFreeToggle(vocalFree: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("No vocals (Vocal-Free)", style = rowLabel())
            Text(
                "For audio without a lead vocal, e.g. a karaoke version: vocal removal is skipped and you sing over the original audio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = vocalFree, onCheckedChange = onChange)
    }
}

@Composable
private fun Position(state: SessionState, playback: Playback, onSeek: (Long) -> Unit) {
    // While dragging, show the thumb where the finger is; seek once on release.
    var dragMs by remember { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs.coerceAtLeast(1).toFloat()
    // The Start Position is chosen while idle; a Take's own position runs otherwise (dimmed).
    val idle = state == SessionState.Idle
    val colors = MaterialTheme.colorScheme
    Column(Modifier.alpha(if (idle) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = dragMs ?: playback.positionMs.toFloat().coerceIn(0f, duration),
            onValueChange = { dragMs = it },
            onValueChangeFinished = {
                dragMs?.let { onSeek(it.toLong()) }
                dragMs = null
            },
            valueRange = 0f..duration,
            enabled = idle,
            // Dimmed as a whole rather than greyed out: it stays gold, as the position keeps running.
            colors = SliderDefaults.colors(
                inactiveTrackColor = colors.secondaryContainer,
                disabledThumbColor = colors.primary,
                disabledActiveTrackColor = colors.primary,
                disabledInactiveTrackColor = colors.secondaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(dragMs?.toLong() ?: playback.positionMs), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(formatTime(playback.durationMs), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun Transport(state: SessionState, playback: Playback, reviewBackingDb: Float, actions: SessionActions) {
    when (state) {
        SessionState.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeutralOutlinedButton(
                if (playback.playing) "Pause" else "Play",
                actions.onTogglePlay,
                Modifier.height(TRANSPORT_HEIGHT),
                style = transportLabel(),
                horizontalPadding = 20.dp,
            )
            RecordButton("Record from ${formatTime(playback.positionMs)}", actions.onRecord, Modifier.weight(1f))
        }
        is SessionState.CountingDown -> Row(
            Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "${state.secondsLeft}",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(40.dp),
            )
            Text("Recording from ${formatTime(state.startPositionMs)}…", style = rowLabel(), modifier = Modifier.weight(1f))
            TextButton(onClick = actions.onStop, shape = CroonvoxButtonShape) { Text("Cancel") }
        }
        is SessionState.Recording -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val record = CroonvoxTheme.colors.record
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(record, CircleShape)
                Text(
                    "Recording · ${formatTime(playback.positionMs - state.startPositionMs)}",
                    style = CroonvoxTheme.type.recordingTime,
                    color = record,
                )
            }
            StopButton(actions.onStop)
        }
        is SessionState.Review -> Review(state.take, state.transpose, playback, reviewBackingDb, actions)
        is SessionState.PlayingTake -> Panel(spacing = 12.dp) {
            Text("Playing ${state.take.name}", style = MaterialTheme.typography.titleMedium)
            NeutralOutlinedButton("Stop", actions.onStop, Modifier.height(44.dp))
            ReviewBalance(reviewBackingDb, actions.onReviewBalance)
        }
    }
}

/** The Transpose control (ADR 5). A Take being recorded or played back keeps its own Transpose. */
@Composable
private fun TransposeControl(transpose: Transpose, state: SessionState, onChange: (Transpose) -> Unit) {
    // The Transpose the Backing Audio is bound to while a Take records or plays back.
    val takeTranspose = when (state) {
        is SessionState.Recording -> state.transpose
        is SessionState.Review -> state.transpose
        is SessionState.PlayingTake -> state.take.transpose
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Transpose", Modifier.weight(1f), style = rowLabel())
            StepButton("−", "Transpose down", enabled = transpose.semitones > Transpose.MIN) {
                onChange(Transpose(transpose.semitones - 1))
            }
            Text(
                transpose.label(),
                Modifier.width(40.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            StepButton("+", "Transpose up", enabled = transpose.semitones < Transpose.MAX) {
                onChange(Transpose(transpose.semitones + 1))
            }
        }
        if (takeTranspose != null && takeTranspose != transpose) {
            Text(
                "This Take stays at ${takeTranspose.label()}; the new setting applies to the next Take.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A square 44 dp − / + button. */
@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = CroonvoxButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(44.dp).semantics { contentDescription = description },
    ) { Text(symbol, fontSize = 20.sp) }
}

/** The wide Record button: red, with the Start Position in its label (ADR 9). */
@Composable
private fun RecordButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CroonvoxTheme.colors
    Button(
        onClick = onClick,
        shape = CroonvoxButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.record, contentColor = colors.onRecord),
        modifier = modifier.height(TRANSPORT_HEIGHT),
    ) {
        Dot(colors.onRecord, CircleShape)
        Spacer(Modifier.width(8.dp))
        Text(label, style = transportLabel(), fontWeight = FontWeight.Bold)
    }
}

/** Stops the recording: red like Record, with a square. */
@Composable
private fun StopButton(onClick: () -> Unit) {
    val colors = CroonvoxTheme.colors
    Button(
        onClick = onClick,
        shape = CroonvoxButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.record, contentColor = colors.onRecord),
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = Modifier.height(TRANSPORT_HEIGHT),
    ) {
        Dot(colors.onRecord, RoundedCornerShape(2.dp))
        Spacer(Modifier.width(8.dp))
        Text("Stop", style = transportLabel(), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Review(take: CapturedTake, transpose: Transpose, playback: Playback, reviewBackingDb: Float, actions: SessionActions) {
    Panel(spacing = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Review", style = MaterialTheme.typography.titleLarge)
            Text(
                "Take from ${formatTime(take.startPositionMs)}, ${formatTime(take.durationMs)} long, Transpose ${transpose.label()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playback.playingTake) {
                NeutralOutlinedButton("Pause", actions.onPause, Modifier.height(44.dp), horizontalPadding = 14.dp)
            } else {
                NeutralOutlinedButton("Play again", actions.onReplay, Modifier.height(44.dp), horizontalPadding = 14.dp)
            }
            // Keep is the one gold surface on the screen.
            Button(onClick = actions.onKeep, shape = CroonvoxButtonShape, modifier = Modifier.weight(1f).height(44.dp)) {
                Text("Keep", fontWeight = FontWeight.Bold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeutralOutlinedButton("Discard", actions.onDiscard, Modifier.weight(1f).height(44.dp))
            val record = CroonvoxTheme.colors.record
            OutlinedButton(
                onClick = actions.onRecordAgain,
                shape = CroonvoxButtonShape,
                border = BorderStroke(1.dp, record),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = record),
                modifier = Modifier.weight(1f).height(44.dp),
            ) { Text("Record again") }
        }
        ReviewBalance(reviewBackingDb, actions.onReviewBalance)
    }
}

/** How loud the Backing Audio plays under the Take while listening back; the Take is unchanged. */
@Composable
private fun ReviewBalance(backingDb: Float, onChange: (Float) -> Unit) {
    // Dragging changes the balance live; it is remembered for the next Sessions.
    var dragDb by remember { mutableStateOf<Float?>(null) }
    val shown = dragDb ?: backingDb
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Backing while listening back: ${formatDb(shown)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Voice", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = shown,
                onValueChange = {
                    dragDb = it.roundToInt().toFloat()
                    onChange(it.roundToInt().toFloat())
                },
                onValueChangeFinished = { dragDb = null },
                valueRange = Session.MIN_REVIEW_BACKING_DB..0f,
                colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.weight(1f),
            )
            Text("Backing", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Microphone(input: InputLevel, gainDb: Float, onGain: (Float) -> Unit) {
    Panel(spacing = 10.dp) {
        if (!input.enabled) {
            Text(
                "The microphone opens when you record for the first time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        Text("Microphone (${input.device?.label() ?: "unknown"})", style = MaterialTheme.typography.labelLarge)
        LevelMeter(meterLevel(input.peak), input.clipping)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (mark in listOf("−60 dB", "−30", "0 dB")) {
                Text(mark, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (input.clipping) {
            Text(
                "Too loud: the input is clipping. Lower the gain or move back from the microphone.",
                color = CroonvoxTheme.colors.record,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Dragging changes the gain live; it is saved for this kind of microphone.
        var dragDb by remember { mutableStateOf<Float?>(null) }
        val shown = dragDb ?: gainDb
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Input gain: ${formatDb(shown)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = shown,
                onValueChange = {
                    dragDb = it.roundToInt().toFloat()
                    onGain(it.roundToInt().toFloat())
                },
                onValueChangeFinished = { dragDb = null },
                valueRange = Session.MIN_INPUT_GAIN_DB..Session.MAX_INPUT_GAIN_DB,
                colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The input level as [METER_SEGMENTS] segments: lit ones gold (all red while clipping), unlit
 * ones raised grey, the last [CLIP_SEGMENTS] (the clipping zone) unlit in dark red.
 */
@Composable
private fun LevelMeter(level: Float, clipping: Boolean) {
    val lit = (level * METER_SEGMENTS).roundToInt()
    val scheme = MaterialTheme.colorScheme
    val extra = CroonvoxTheme.colors
    Row(
        Modifier.fillMaxWidth().height(12.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f) },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(METER_SEGMENTS) { segment ->
            val color = when {
                segment < lit -> if (clipping) extra.record else scheme.primary
                segment >= METER_SEGMENTS - CLIP_SEGMENTS -> extra.recordSoft
                else -> scheme.surfaceContainerHigh
            }
            Box(Modifier.weight(1f).fillMaxHeight().background(color, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = rowLabel())
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Takes(takes: List<Take>, state: SessionState, actions: SessionActions) {
    var renaming by remember { mutableStateOf<Take?>(null) }
    var deleting by remember { mutableStateOf<Take?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Takes (${takes.size})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (takes.isEmpty()) {
            Text("Kept Takes appear here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        takes.forEach { take ->
            Panel(spacing = 6.dp, verticalPadding = 12.dp) {
                Text(take.name, style = rowLabel())
                Text(
                    "From ${formatTime(take.startPositionMs)} · ${formatTime(take.durationMs)} · ${take.transpose.label()} · ${take.route.label()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.offset(x = (-8).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TakeAction("Play", MaterialTheme.colorScheme.primary, enabled = state == SessionState.Idle) { actions.onPlayTake(take.id) }
                    TakeAction("Rename", MaterialTheme.colorScheme.onSurface) { renaming = take }
                    TakeAction("Delete", MaterialTheme.colorScheme.onSurface) { deleting = take }
                }
            }
        }
    }
    renaming?.let { take ->
        var name by remember(take.id) { mutableStateOf(take.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename Take") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onRename(take.id, name)
                    renaming = null
                }, shape = CroonvoxButtonShape) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }, shape = CroonvoxButtonShape) { Text("Cancel") } },
        )
    }
    deleting?.let { take ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${take.name}?") },
            text = { Text("The recording is removed for good.") },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(take.id)
                    deleting = null
                }, shape = CroonvoxButtonShape) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }, shape = CroonvoxButtonShape) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TakeAction(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = CroonvoxButtonShape,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.height(36.dp),
    ) { Text(label) }
}

/** A card on the raised surface: Review, Microphone, each Take. No shadow; layers come from the surface tones. */
@Composable
private fun Panel(spacing: Dp, verticalPadding: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** An outlined button in ink, not gold: gold is kept for the one primary action. */
@Composable
private fun NeutralOutlinedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    horizontalPadding: Dp = 16.dp,
) {
    OutlinedButton(
        onClick = onClick,
        shape = CroonvoxButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        modifier = modifier,
    ) { Text(label, style = style) }
}

/** The dot of Record and "● Recording", or the square of Stop. */
@Composable
private fun Dot(color: Color, shape: Shape) {
    Box(Modifier.size(12.dp).background(color, shape))
}

/** Labels of the large transport buttons (16 sp, a step above the usual button label). */
@Composable
private fun transportLabel(): TextStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, lineHeight = 22.sp)

/** Row titles: Transpose, the countdown setting, a Take's name. */
@Composable
private fun rowLabel(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)

private val TRANSPORT_HEIGHT = 52.dp
private const val METER_SEGMENTS = 20
private const val CLIP_SEGMENTS = 3

/** Meter position 0..1 for a peak on a dB scale from [METER_FLOOR_DB] to 0 dBFS, as level meters read. */
private fun meterLevel(peak: Float): Float =
    if (peak <= 0f) 0f else ((20f * log10(peak) - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f)

private const val METER_FLOOR_DB = -60f

private fun formatDb(db: Float): String = if (db > 0) "+${db.roundToInt()} dB" else "${db.roundToInt()} dB"

/** Semitones with a sign, e.g. "+2", "−3", "0". */
private fun Transpose.label(): String = when {
    semitones > 0 -> "+$semitones"
    semitones < 0 -> "−${-semitones}"
    else -> "0"
}

private fun InputDevice.label(): String = when (this) {
    InputDevice.BuiltIn -> "built-in"
    InputDevice.Wired -> "headset"
    InputDevice.Usb -> "USB"
    InputDevice.Bluetooth -> "Bluetooth"
    InputDevice.Other -> "other"
}

private fun OutputRoute.label(): String = when (this) {
    OutputRoute.Speaker -> "speaker"
    OutputRoute.Wired -> "wired"
    OutputRoute.Usb -> "USB"
    OutputRoute.Bluetooth -> "Bluetooth"
    OutputRoute.Other -> "other output"
}

@Preview
@Composable
private fun ReviewPreview() {
    val take = CapturedTake(startFrame = 1_440_000, frames = 480_000, sampleRate = 48_000, route = OutputRoute.Wired, reportedLatencyFrames = 4_000)
    val kept = Take(TakeId("1"), TrackId(SourceId("local"), "x"), "Take 1", 0, 960_000, 48_000, OutputRoute.Wired, 4_000, Transpose(2), 0)
    CroonvoxTheme {
        SessionScreen(
            openError = null,
            snackbar = remember { SnackbarHostState() },
            content = SessionContent(
                track = Track(
                    TrackId(SourceId("local"), "x"), "Fly Me to the Moon", "Frank Sinatra", 148_000,
                    TrackPipelineState.Ready(SeparationModelId("fast")), backingChoice = BackingChoice.OriginalAudio,
                ),
                loadProgress = 1f,
                state = SessionState.Review(take, Transpose(-2)),
                backing = BackingAudio.Instrumental(SeparationModelId("fast")),
                playback = Playback(null, playing = true, positionMs = 32_000, durationMs = 180_000, playingTake = true),
                input = InputLevel(enabled = true, device = InputDevice.BuiltIn, peak = 0.6f, clipping = true),
                inputGainDb = 6f,
                countdownEnabled = true,
                reviewBackingDb = -20f,
                takes = listOf(kept),
                lyrics = TrackLyrics.Confirmed(
                    LyricsCandidate("1", "Frank Sinatra", "Fly Me to the Moon", 148_000, "Fly me to the moon\nLet me play among the stars", null),
                ),
                lyricsOffsetMs = 0,
                karaoke = true,
                transpose = Transpose(1),
            ),
            actions = SessionActions(),
        )
    }
}
