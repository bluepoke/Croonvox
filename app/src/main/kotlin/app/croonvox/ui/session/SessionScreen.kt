// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.session

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.croonvox.core.lyrics.Lrc
import app.croonvox.core.lyrics.LyricsOffset
import app.croonvox.core.lyrics.LyricsRepository
import app.croonvox.core.lyrics.TrackLyrics
import app.croonvox.core.lyrics.candidate
import app.croonvox.core.model.InputDevice
import app.croonvox.core.model.OutputRoute
import app.croonvox.core.model.SourceId
import app.croonvox.core.model.Take
import app.croonvox.core.model.TakeId
import app.croonvox.core.model.TrackId
import app.croonvox.core.model.Transpose
import app.croonvox.core.port.CapturedTake
import app.croonvox.core.port.InputLevel
import app.croonvox.core.port.LyricsCandidate
import app.croonvox.core.port.Playback
import app.croonvox.core.session.BackingAudio
import app.croonvox.core.session.Session
import app.croonvox.core.session.SessionState
import app.croonvox.core.session.Sessions
import app.croonvox.ui.formatTime
import app.croonvox.ui.lyrics.KaraokeView
import app.croonvox.ui.lyrics.PageView
import app.croonvox.ui.theme.CroonvoxTheme
import kotlin.math.log10
import kotlin.math.roundToInt

@Composable
fun SessionRoute(id: TrackId, sessions: Sessions, lyrics: LyricsRepository, lyricsOffset: LyricsOffset) {
    val viewModel: SessionViewModel = viewModel(
        factory = viewModelFactory { initializer { SessionViewModel(id, sessions, lyrics, lyricsOffset) } },
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
    val title by viewModel.title.collectAsStateWithLifecycle()
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
        title = title,
        openError = openError,
        snackbar = snackbar,
        content = SessionContent(state, backing, playback, input, gain, countdown, reviewBacking, takes, trackLyrics, offsetMs, karaoke, transpose),
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
        ),
    )
}

data class SessionContent(
    val state: SessionState,
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
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    title: String,
    openError: String?,
    snackbar: SnackbarHostState,
    content: SessionContent,
    actions: SessionActions,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(title, maxLines = 1) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Lyrics on top (Page View), the Session's controls below; each half scrolls on its own.
        Column(Modifier.fillMaxSize().padding(padding)) {
            LyricsPane(content, actions, Modifier.weight(1f).fillMaxWidth().padding(16.dp))
            HorizontalDivider()
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(content.karaoke, { actions.onShowKaraoke(true) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Karaoke") }
            SegmentedButton(!content.karaoke, { actions.onShowKaraoke(false) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Page") }
        }
        if (content.karaoke) {
            KaraokeView(
                lines, content.playback, content.lyricsOffsetMs, actions.onNudgeLyricsOffset, actions.onResetLyricsOffset,
                Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            PageView(content.lyrics, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun SessionControls(openError: String?, content: SessionContent, actions: SessionActions) {
    when {
        openError != null -> Text("Can't start a Session: $openError", color = MaterialTheme.colorScheme.error)
        content.backing == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> {
            BackingLine(content.backing)
            Position(content.state, content.playback, actions.onSeek)
            Transport(content.state, content.playback, content.reviewBackingDb, actions)
            TransposeControl(content.transpose, content.state, actions.onTranspose)
            HorizontalDivider()
            Microphone(content.input, content.inputGainDb, actions.onGain)
            SettingRow("Countdown before recording (${Session.COUNTDOWN_SECONDS} s)", content.countdownEnabled, actions.onCountdown)
            HorizontalDivider()
            Takes(content.takes, content.state, actions)
        }
    }
}

@Composable
private fun BackingLine(backing: BackingAudio) {
    val text = when (backing) {
        BackingAudio.OriginalAudio -> "Singing over the Original Audio. The Instrumental replaces it once vocal removal is done."
        is BackingAudio.Instrumental -> "Singing over the Instrumental."
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Position(state: SessionState, playback: Playback, onSeek: (Long) -> Unit) {
    // While dragging, show the thumb where the finger is; seek once on release.
    var dragMs by remember { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs.coerceAtLeast(1).toFloat()
    Column {
        Slider(
            value = dragMs ?: playback.positionMs.toFloat().coerceIn(0f, duration),
            onValueChange = { dragMs = it },
            onValueChangeFinished = {
                dragMs?.let { onSeek(it.toLong()) }
                dragMs = null
            },
            valueRange = 0f..duration,
            // The Start Position is chosen while idle; a Take's own position runs otherwise.
            enabled = state == SessionState.Idle,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(dragMs?.toLong() ?: playback.positionMs), style = MaterialTheme.typography.bodySmall)
            Text(formatTime(playback.durationMs), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Transport(state: SessionState, playback: Playback, reviewBackingDb: Float, actions: SessionActions) {
    when (state) {
        SessionState.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = actions.onTogglePlay) { Text(if (playback.playing) "Pause" else "Play") }
            RecordButton("Record from ${formatTime(playback.positionMs)}", actions.onRecord)
        }
        is SessionState.CountingDown -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${state.secondsLeft}", fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Recording from ${formatTime(state.startPositionMs)}…")
                TextButton(onClick = actions.onStop) { Text("Cancel") }
            }
        }
        is SessionState.Recording -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "● Recording · ${formatTime(playback.positionMs - state.startPositionMs)}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = actions.onStop) { Text("Stop") }
        }
        is SessionState.Review -> Review(state.take, state.transpose, playback, reviewBackingDb, actions)
        is SessionState.PlayingTake -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Playing ${state.take.name}", style = MaterialTheme.typography.titleMedium)
            Button(onClick = actions.onStop) { Text("Stop") }
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
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Transpose", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { onChange(Transpose(transpose.semitones - 1)) },
                enabled = transpose.semitones > Transpose.MIN,
            ) { Text("−") }
            Text(transpose.label(), Modifier.width(48.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            OutlinedButton(
                onClick = { onChange(Transpose(transpose.semitones + 1)) },
                enabled = transpose.semitones < Transpose.MAX,
            ) { Text("+") }
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

@Composable
private fun RecordButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) { Text("● $label") }
}

@Composable
private fun Review(take: CapturedTake, transpose: Transpose, playback: Playback, reviewBackingDb: Float, actions: SessionActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Review", style = MaterialTheme.typography.titleMedium)
        Text(
            "Take from ${formatTime(take.startPositionMs)}, ${formatTime(take.durationMs)} long, Transpose ${transpose.label()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playback.playingTake) {
                OutlinedButton(onClick = actions.onPause) { Text("Pause") }
            } else {
                OutlinedButton(onClick = actions.onReplay) { Text("Play again") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onKeep) { Text("Keep") }
            OutlinedButton(onClick = actions.onDiscard) { Text("Discard") }
            OutlinedButton(onClick = actions.onRecordAgain) { Text("Record again") }
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
    Column {
        Text("Backing while listening back: ${formatDb(shown)}", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Voice", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = shown,
                onValueChange = {
                    dragDb = it.roundToInt().toFloat()
                    onChange(it.roundToInt().toFloat())
                },
                onValueChangeFinished = { dragDb = null },
                valueRange = Session.MIN_REVIEW_BACKING_DB..0f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("Backing", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Microphone(input: InputLevel, gainDb: Float, onGain: (Float) -> Unit) {
    if (!input.enabled) {
        Text(
            "The microphone opens when you record for the first time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Microphone (${input.device?.label() ?: "unknown"})", style = MaterialTheme.typography.titleSmall)
        LinearProgressIndicator(
            progress = { meterLevel(input.peak) },
            color = if (input.clipping) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        if (input.clipping) {
            Text(
                "Too loud: the input is clipping. Lower the gain or move back from the microphone.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Dragging changes the gain live; it is saved for this kind of microphone.
        var dragDb by remember { mutableStateOf<Float?>(null) }
        val shown = dragDb ?: gainDb
        Text("Input gain: ${formatDb(shown)}", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = shown,
            onValueChange = {
                dragDb = it.roundToInt().toFloat()
                onGain(it.roundToInt().toFloat())
            },
            onValueChangeFinished = { dragDb = null },
            valueRange = Session.MIN_INPUT_GAIN_DB..Session.MAX_INPUT_GAIN_DB,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Takes(takes: List<Take>, state: SessionState, actions: SessionActions) {
    var renaming by remember { mutableStateOf<Take?>(null) }
    var deleting by remember { mutableStateOf<Take?>(null) }
    Text("Takes (${takes.size})", style = MaterialTheme.typography.titleMedium)
    if (takes.isEmpty()) {
        Text("Kept Takes appear here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    takes.forEach { take ->
        ListItem(
            headlineContent = { Text(take.name) },
            supportingContent = {
                Text("From ${formatTime(take.startPositionMs)} · ${formatTime(take.durationMs)} · ${take.transpose.label()} · ${take.route.label()}")
            },
            trailingContent = {
                Row {
                    TextButton(onClick = { actions.onPlayTake(take.id) }, enabled = state == SessionState.Idle) { Text("Play") }
                    TextButton(onClick = { renaming = take }) { Text("Rename") }
                    TextButton(onClick = { deleting = take }) { Text("Delete") }
                }
            },
        )
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
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
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
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

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
            title = "Fly Me to the Moon",
            openError = null,
            snackbar = remember { SnackbarHostState() },
            content = SessionContent(
                state = SessionState.Review(take, Transpose(-2)),
                backing = BackingAudio.OriginalAudio,
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
