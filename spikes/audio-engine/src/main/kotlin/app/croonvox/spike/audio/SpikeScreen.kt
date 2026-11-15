// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.audio

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun SpikeScreen(controller: SpikeController, micGranted: Boolean, onRequestMic: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Audio Engine spike", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Ticket #2 · throwaway · measures ADR 4 / ADR 5 on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!micGranted) {
                Section("Microphone") {
                    Text("Microphone access is needed for full-duplex streams, Takes and calibration.")
                    Button(onClick = onRequestMic) { Text("Grant microphone access") }
                }
                return@Column
            }

            controller.message?.let { msg ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = controller::dismissMessage) { Text("OK") }
                    }
                }
            }

            StreamSection(controller)
            LatencySection(controller)
            BackingSection(controller)
            TransportSection(controller)
            TransposeSection(controller)
            MonitoringSection(controller)
            TakeSection(controller)
        }
    }
}

@Composable
private fun StreamSection(c: SpikeController) {
    val s = c.stats
    Section("Route & stream") {
        Stat("Route", "${c.route.kind.label} — ${c.route.name}")
        Stat("State", if (s.streamOpen) s.state.name else "closed")
        Stat("API", s.outputApi.name)
        Stat("Sample rate", "${s.sampleRate} Hz")
        Stat("Burst / buffer", "${s.framesPerBurst} / ${s.bufferSize} frames (%.1f ms)".format(s.framesToMs(s.bufferSize.toLong())))
        Stat("Exclusive out / in", "${yesNo(s.outputExclusive)} / ${yesNo(s.inputExclusive)}")
        Stat("Low latency out / in", "${yesNo(s.outputLowLatency)} / ${yesNo(s.inputLowLatency)}")
        Stat("XRuns out / in (total)", "${s.xRuns} / ${s.inputXRuns}", warn = s.xRuns > 0 || s.inputXRuns > 0)
        Stat("Input shortfalls while recording", s.inputShortfalls.toString(), warn = s.inputShortfalls > 0)
        Stat("Callback CPU (peak, decaying)", "%.0f %%".format(s.cpuLoad * 100), warn = s.cpuLoad > 0.7)
        Stat("Route changes", s.routeGeneration.toString())
    }
}

@Composable
private fun LatencySection(c: SpikeController) {
    val s = c.stats
    Section("Latency") {
        Stat("Reported output", s.outputLatencyMs?.let { "%.1f ms".format(it) } ?: "n/a")
        Stat("Reported input", s.inputLatencyMs?.let { "%.1f ms".format(it) } ?: "n/a")
        val reportedSum = s.outputLatencyMs?.let { o -> s.inputLatencyMs?.let { i -> o + i } }
        Stat("Reported round trip", reportedSum?.let { "%.1f ms".format(it) } ?: "n/a")
        Stat(
            "Measured round trip (last run)",
            s.calibrationFrames?.let { "%.1f ms (%d frames, %d/8 clicks)".format(s.framesToMs(it), it, s.calibrationClicks) } ?: "—",
        )
        Stat(
            "Stored for ${c.route.kind.label}",
            c.savedCalibrationFrames?.let { "%.1f ms".format(s.framesToMs(it)) } ?: "not calibrated (using estimate)",
        )
        Stat("Applied to Takes", "%.1f ms (%d frames)".format(s.framesToMs(s.latencyCompFrames), s.latencyCompFrames))
        Stat("Transpose pre-roll", "%.1f ms (compensated)".format(s.framesToMs(s.stretchLatencyFrames.toLong())))
        Text(
            "Calibration plays 8 clicks and listens for them. Use the speaker, hold an earbud to the mic, " +
                "or use a loopback adapter. Keep the room quiet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = c::calibrate, enabled = s.streamOpen && s.state == EngineStats.State.Idle) {
                Text(if (s.state == EngineStats.State.Calibrating) "Calibrating…" else "Calibrate")
            }
            OutlinedButton(onClick = c::clearCalibration, enabled = c.savedCalibrationFrames != null) { Text("Clear") }
        }
    }
}

@Composable
private fun BackingSection(c: SpikeController) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) c.loadFile(uri, displayName(context, uri))
    }
    Section("Backing Audio") {
        Stat("Loaded", c.backingLabel)
        Stat("Length", "%.1f s".format(c.stats.framesToSeconds(c.stats.backingFrames)))
        c.loadProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { c.loadTestTone() }, enabled = idle(c)) { Text("Test tone") }
            FilledTonalButton(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = idle(c)) { Text("Load file…") }
        }
    }
}

@Composable
private fun TransportSection(c: SpikeController) {
    val s = c.stats
    val duration = s.framesToSeconds(s.backingFrames).toFloat()
    Section("Transport") {
        Stat("Position", "%.2f / %.1f s".format(s.framesToSeconds(s.positionFrames), duration))
        Text("Start Position: %.1f s".format(c.startSeconds), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = c.startSeconds.coerceIn(0f, maxOf(duration, 0f)),
            onValueChange = { c.startSeconds = it },
            valueRange = 0f..maxOf(duration, 1f),
            enabled = idle(c) && s.backingFrames > 0,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = c::play, enabled = idle(c) && s.backingFrames > 0) { Text("Play") }
            Button(onClick = c::record, enabled = idle(c) && s.backingFrames > 0) { Text("Record") }
            Button(onClick = c::review, enabled = idle(c) && s.takeFrames > 0) { Text("Review") }
            OutlinedButton(onClick = c::stopTransport, enabled = !idle(c)) { Text("Stop") }
        }
        Text("Input level", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { s.inputPeak.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = if (s.inputPeak >= 0.98f) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TransposeSection(c: SpikeController) {
    // Takes are bound to their Transpose (ADR 5): changes only apply to Play and the next Take.
    val locked = c.stats.state == EngineStats.State.Recording || c.stats.state == EngineStats.State.Reviewing
    Section("Transpose") {
        Text("%+d semitones".format(c.transpose), style = MaterialTheme.typography.titleMedium)
        Slider(
            value = c.transpose.toFloat(),
            onValueChange = { c.changeTranspose(it.roundToInt()) },
            valueRange = NativeEngine.TRANSPOSE_MIN.toFloat()..NativeEngine.TRANSPOSE_MAX.toFloat(),
            steps = NativeEngine.TRANSPOSE_MAX - NativeEngine.TRANSPOSE_MIN - 1,
            enabled = !locked,
        )
        Text(
            "Applies live during Play. A Take keeps the Transpose it was recorded at; Review uses it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MonitoringSection(c: SpikeController) {
    val allowed = c.route.kind.monitoringAllowed
    Section("Monitoring (dry)") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hear my voice", modifier = Modifier.weight(1f))
            Switch(checked = c.monitoring, onCheckedChange = c::changeMonitoring, enabled = allowed)
        }
        if (!allowed) {
            Text(
                when (c.route.kind) {
                    RouteKind.Bluetooth -> "Unavailable over Bluetooth: its latency (150–300 ms) makes live monitoring an echo."
                    RouteKind.Speaker -> "Unavailable on the speaker: it would feed back. Plug in wired or USB headphones."
                    else -> "Only available on wired or USB headphones."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text("Gain: %.2f×".format(c.monitorGain), style = MaterialTheme.typography.bodyMedium)
        Slider(value = c.monitorGain, onValueChange = c::changeMonitorGain, valueRange = 0f..2f, enabled = allowed)
    }
}

@Composable
private fun TakeSection(c: SpikeController) {
    val s = c.stats
    Section("Take") {
        if (s.takeFrames <= 0) {
            Text("No Take yet.", style = MaterialTheme.typography.bodyMedium)
            return@Section
        }
        Stat("Length", "%.1f s".format(s.framesToSeconds(s.takeFrames)))
        Stat("Start Position", "%.1f s".format(s.framesToSeconds(s.takeStartFrame)))
        Stat("Transpose", "%+d".format(s.takeTranspose))
        Text(
            "Saves a stereo WAV to the app's Music folder: left = Backing Audio, right = compensated Take. " +
                "Pull it with adb and inspect alignment in an editor. A route change discards the Take.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = c::saveAlignmentWav, enabled = idle(c)) { Text("Save alignment WAV") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Stat(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun idle(c: SpikeController) = c.stats.streamOpen && c.stats.state == EngineStats.State.Idle && c.loadProgress == null

private fun yesNo(b: Boolean) = if (b) "yes" else "no"

private fun displayName(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "file"
