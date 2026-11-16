// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.separation.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.croonvox.spike.separation.Candidate
import app.croonvox.spike.separation.Catalogue
import app.croonvox.spike.separation.LicenseStatus
import app.croonvox.spike.separation.RunRecord
import app.croonvox.spike.separation.model.ExecutionProvider
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun BenchmarkScreen(c: BenchmarkController) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Separation spike", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Ticket #3 · throwaway · measures ADR 3's candidate Separation Models on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(c.device, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)

            c.message?.let { msg ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = c::dismissMessage) { Text("OK") }
                    }
                }
            }

            BusySection(c)
            SongSection(c)
            ModelsSection(c)
            OptionsSection(c)
            Button(onClick = c::runSelected, enabled = c.busy == null, modifier = Modifier.fillMaxWidth()) {
                Text("Run selected models")
            }
            ResultsSection(c)
            Section("Files") {
                Text("Models (adb push here): ${c.modelsDir.absolutePath}/<id>/", style = MaterialTheme.typography.bodySmall)
                Text("Stems and results: ${c.outputDir.absolutePath}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BusySection(c: BenchmarkController) {
    val busy = c.busy ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(busy.label, style = MaterialTheme.typography.bodyMedium)
            val p = busy.progress
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = c::cancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun SongSection(c: BenchmarkController) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(c::pickSong) }
    Section("Song") {
        val song = c.song
        if (song == null) {
            Text("Pick a song. Use a long one (6+ min) at least once to check memory stays bounded.")
        } else {
            Stat("File", song.name)
            Stat("Duration", "%d:%02d (%d frames)".format(song.seconds.toInt() / 60, song.seconds.toInt() % 60, song.audio.frames))
            Stat("Sample rate", "${song.audio.sampleRate} Hz")
            Stat("Decoded in", "${song.decodeMs} ms")
            OutlinedButton(onClick = c::toggleOriginal) { Text(if (c.playing == song.uri.toString()) "Stop" else "Play original") }
        }
        Button(onClick = { picker.launch(arrayOf("audio/*", "video/*")) }, enabled = c.busy == null) { Text("Pick song") }
    }
}

@Composable
private fun ModelsSection(c: BenchmarkController) {
    var importFor by remember { mutableStateOf<Candidate?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val candidate = importFor
        if (uri != null && candidate != null) c.import(candidate, uri)
        importFor = null
    }
    Section("Models") {
        for (candidate in Catalogue.candidates) {
            val installedBytes = c.installed[candidate.id]
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = c.selected[candidate.id] == true,
                            onCheckedChange = { c.selected[candidate.id] = it },
                            enabled = installedBytes != null,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(candidate.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${candidate.tier} · license: ${candidate.licenseStatus.label()} · " +
                                    (installedBytes?.let { "installed, ${mb(it)}" }
                                        ?: candidate.download?.let { "download ${mb(it.bytes)}" }
                                        ?: "convert and import"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text(candidate.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (installedBytes == null && candidate.download != null) {
                            OutlinedButton(onClick = { c.download(candidate) }, enabled = c.busy == null) { Text("Download") }
                        }
                        if (installedBytes == null && candidate.files.size == 1) {
                            OutlinedButton(
                                onClick = {
                                    importFor = candidate
                                    importer.launch(arrayOf("*/*"))
                                },
                                enabled = c.busy == null,
                            ) { Text("Import ${candidate.files.single()}") }
                        }
                        if (installedBytes != null) {
                            TextButton(onClick = { c.delete(candidate) }, enabled = c.busy == null) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionsSection(c: BenchmarkController) {
    val o = c.options
    Section("Runtime") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (ep in ExecutionProvider.entries) {
                FilterChip(
                    selected = o.runtime.provider == ep,
                    onClick = { c.options = o.copy(runtime = o.runtime.copy(provider = ep)) },
                    label = { Text(ep.name) },
                )
            }
        }
        Text("Threads: ${o.runtime.threads}")
        Slider(
            value = o.runtime.threads.toFloat(),
            onValueChange = { c.options = o.copy(runtime = o.runtime.copy(threads = it.roundToInt())) },
            valueRange = 1f..8f,
            steps = 6,
        )
        Text("Segment overlap: ${(o.overlap * 100).roundToInt()} %")
        Slider(
            value = o.overlap,
            onValueChange = { c.options = o.copy(overlap = (it * 20).roundToInt() / 20f) },
            valueRange = 0f..0.5f,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ORT CPU memory arena (off: lower peak memory)", modifier = Modifier.weight(1f))
            Switch(checked = o.runtime.cpuArena, onCheckedChange = { c.options = o.copy(runtime = o.runtime.copy(cpuArena = it)) })
        }
    }
}

@Composable
private fun ResultsSection(c: BenchmarkController) {
    if (c.runs.isEmpty()) return
    Section("Results") {
        OutlinedButton(onClick = c::exportMarkdown) { Text("Copy results as Markdown") }
        for (run in c.runs) RunCard(run, c)
    }
}

@Composable
private fun RunCard(run: RunRecord, c: BenchmarkController) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val o = run.options.runtime
            Text("${run.candidate.name} · ${o.provider} · ${o.threads} threads · arena ${if (o.cpuArena) "on" else "off"} · overlap ${(run.options.overlap * 100).roundToInt()} %", fontWeight = FontWeight.SemiBold)
            val r = run.result
            if (r == null) {
                Text("Failed: ${run.error}", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Stat("Separation wall time", "%.1f s".format(r.separationMs / 1000.0))
            Stat("Real-time factor", "%.2f (total incl. resampling/load/write %.2f)".format(r.realTimeFactor, r.realTimeFactorTotal))
            Stat("Peak memory (RSS)", "${r.peakRssBytes shr 20} MB (+${r.peakDeltaBytes shr 20} MB over ${r.baselineRssBytes shr 20} MB)")
            Stat("Model load", "${r.loadMs} ms")
            Stat("Segments", "${r.segments} · mean %.0f ms · max %d ms".format(r.segmentMsMean, r.segmentMsMax))
            Stat("Resample in / write out", "${r.resampleInMs} ms / ${r.writeMs} ms")
            val lengthOk = r.instrumentalFrames == r.inputFrames && r.vocalStemFrames == r.inputFrames
            Stat("Frames in / instrumental / vocal", "${r.inputFrames} / ${r.instrumentalFrames} / ${r.vocalStemFrames}", warn = !lengthOk)
            Stat("Lag instrumental / vocal (frames)", "${r.instrumentalLag ?: "n/a"} / ${r.vocalStemLag ?: "n/a"}", warn = r.instrumentalLag != 0 || r.vocalStemLag != 0)
            Stat("Thermal status before → after", "${run.thermalBefore} → ${run.thermalAfter}", warn = run.thermalAfter >= 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayButton("Instrumental", r.instrumental, c)
                PlayButton("Vocal Stem", r.vocalStem, c)
            }
        }
    }
}

@Composable
private fun PlayButton(label: String, file: File, c: BenchmarkController) {
    OutlinedButton(onClick = { c.togglePlayback(file) }) { Text(if (c.playing == file.absolutePath) "Stop" else "Play $label") }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Stat(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun LicenseStatus.label() = when (this) {
    LicenseStatus.Clear -> "clear"
    LicenseStatus.NeedsConfirmation -> "to confirm"
    LicenseStatus.None -> "none (all rights reserved)"
}

private fun mb(bytes: Long) = "%.1f MB".format(Locale.US, bytes / 1e6)
