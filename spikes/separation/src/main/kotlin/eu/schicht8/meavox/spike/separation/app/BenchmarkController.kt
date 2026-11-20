// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.separation.app

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.schicht8.meavox.spike.separation.BenchmarkOptions
import eu.schicht8.meavox.spike.separation.Candidate
import eu.schicht8.meavox.spike.separation.Catalogue
import eu.schicht8.meavox.spike.separation.FindingsMarkdown
import eu.schicht8.meavox.spike.separation.ModelInstaller
import eu.schicht8.meavox.spike.separation.RunRecord
import eu.schicht8.meavox.spike.separation.SeparationBenchmark
import eu.schicht8.meavox.spike.separation.Stage
import eu.schicht8.meavox.spike.separation.audio.RawStereoFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A decoded song, kept as a temp file at its own sample rate. */
data class Song(val name: String, val uri: Uri, val audio: RawStereoFile, val decodeMs: Long) {
    val seconds: Double get() = audio.frames.toDouble() / audio.sampleRate
}

/** What the screen shows while something runs. */
data class Busy(val label: String, val progress: Float?)

/**
 * State and actions of the benchmark screen. Downloads, decoding and runs happen one at a time
 * on a single background thread; [cancel] stops the current one at its next progress report.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BenchmarkController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val installer = ModelInstaller(File(context.getExternalFilesDir(null), "models"))
    private val workDir = File(context.cacheDir, "separation-work")
    private val outDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "separation")
    private val power = context.getSystemService(PowerManager::class.java)
    private val memory = SeparationBenchmark.procStatm(Os.sysconf(OsConstants._SC_PAGESIZE))

    @Volatile private var cancelled = false
    private var player: MediaPlayer? = null

    var song by mutableStateOf<Song?>(null)
        private set
    var busy by mutableStateOf<Busy?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var options by mutableStateOf(BenchmarkOptions())
    val selected = mutableStateMapOf<String, Boolean>()
    val installed = mutableStateMapOf<String, Long>() // id → bytes on disk
    val runs = mutableStateListOf<RunRecord>()
    /** What plays: a stem's path or the song's URI. */
    var playing by mutableStateOf<String?>(null)
        private set

    val device: String = buildString {
        append("${Build.MANUFACTURER} ${Build.MODEL}")
        if (Build.VERSION.SDK_INT >= 31) append(" · ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        append(" · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        append(" · %.1f GB RAM · %d cores".format(Locale.US, mem.totalMem / 1e9, Runtime.getRuntime().availableProcessors()))
    }

    val modelsDir: File get() = File(context.getExternalFilesDir(null), "models")
    val outputDir: File get() = outDir

    init {
        refreshInstalled()
    }

    fun dispose() {
        stopPlayback()
        scope.cancel()
    }

    fun refreshInstalled() {
        installed.clear()
        for (c in Catalogue.candidates) if (installer.isInstalled(c)) installed[c.id] = c.installedBytes(installer.dirOf(c))
        for (c in Catalogue.candidates) if (c.id !in selected) selected[c.id] = c.id in installed
    }

    fun cancel() {
        cancelled = true
    }

    fun dismissMessage() {
        message = null
    }

    fun pickSong(uri: Uri) = work("Decoding") {
        val name = displayName(uri)
        withContext(Dispatchers.Main) {
            song?.audio?.close()
            song = null
        }
        workDir.mkdirs()
        val t0 = System.nanoTime()
        val audio = SongDecoder.decode(context, uri, File(workDir, "original.f32"), { p -> show("Decoding $name", p) }, { cancelled })
        val decoded = Song(name, uri, audio, (System.nanoTime() - t0) / 1_000_000)
        withContext(Dispatchers.Main) { song = decoded }
    }

    fun download(candidate: Candidate) = work("Downloading ${candidate.name}") {
        installer.download(candidate, { done, total -> show("Downloading ${candidate.name} (${done shr 20} / ${total shr 20} MB)", done.toFloat() / total) }, { cancelled })
        withContext(Dispatchers.Main) { refreshInstalled() }
    }

    fun import(candidate: Candidate, uri: Uri) = work("Importing ${candidate.name}") {
        val input = context.contentResolver.openInputStream(uri) ?: error("cannot open $uri")
        installer.import(candidate, candidate.files.single(), input)
        withContext(Dispatchers.Main) { refreshInstalled() }
    }

    fun delete(candidate: Candidate) {
        if (busy != null) return post("Busy: ${busy?.label}")
        installer.delete(candidate)
        refreshInstalled()
    }

    /** Runs every selected, installed candidate on the current song, one after the other. */
    fun runSelected() {
        val current = song ?: return post("Pick a song first")
        val queue = Catalogue.candidates.filter { selected[it.id] == true && it.id in installed }
        if (queue.isEmpty()) return post("Select at least one installed model")
        stopPlayback()
        val opts = options
        work("Running") {
            for (candidate in queue) {
                if (cancelled) break
                val thermalBefore = power.currentThermalStatus
                val record = try {
                    val result = SeparationBenchmark(workDir, memory).run(
                        current.audio, candidate, installer.dirOf(candidate), opts, outDir, fileBase(current, candidate),
                    ) { stage, p ->
                        if (cancelled) throw InterruptedException("cancelled")
                        show("${candidate.name}: ${stage.label()}", if (stage == Stage.Separating) p else null)
                    }
                    RunRecord(candidate, opts, result, null, thermalBefore, power.currentThermalStatus)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Throwable) { // OutOfMemoryError included: that is a finding
                    RunRecord(candidate, opts, null, "${e::class.simpleName}: ${e.message}", thermalBefore, power.currentThermalStatus)
                }
                withContext(Dispatchers.Main) { runs.add(0, record) }
            }
        }
    }

    fun togglePlayback(file: File) = toggle(file.absolutePath) { setDataSource(file.absolutePath) }

    fun toggleOriginal() {
        val current = song ?: return
        toggle(current.uri.toString()) { setDataSource(context, current.uri) }
    }

    private fun toggle(key: String, source: MediaPlayer.() -> Unit) {
        if (playing == key) return stopPlayback()
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                source()
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            playing = key
        } catch (e: Exception) {
            stopPlayback()
            post("Playback failed: ${e.message}")
        }
    }

    fun stopPlayback() {
        player?.release()
        player = null
        playing = null
    }

    /** Copies the runs as findings rows to the clipboard and saves them next to the stems. */
    fun exportMarkdown() {
        val current = song
        val text = buildString {
            if (current != null) appendLine(FindingsMarkdown.device(device, current.name, current.seconds, current.audio.sampleRate, current.decodeMs))
            else appendLine("**Device:** $device")
            appendLine()
            appendLine(FindingsMarkdown.HEADER)
            for (run in runs.reversed()) appendLine(FindingsMarkdown.row(run, current?.name ?: ""))
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Separation findings", text))
        val file = File(outDir.apply { mkdirs() }, "results-${stamp()}.md").apply { writeText(text) }
        post("Copied to clipboard and saved to ${file.absolutePath}")
    }

    private fun work(label: String, block: suspend () -> Unit) {
        if (busy != null) return post("Busy: ${busy?.label}")
        cancelled = false
        busy = Busy(label, null)
        scope.launch {
            try {
                withContext(worker) { block() }
            } catch (_: InterruptedException) {
                post("Cancelled")
            } catch (e: Throwable) {
                post("$label failed: ${e::class.simpleName}: ${e.message}")
            } finally {
                busy = null
            }
        }
    }

    private fun show(label: String, progress: Float?) {
        scope.launch { if (busy != null) busy = Busy(label, progress) }
    }

    private fun post(text: String) {
        scope.launch { message = text }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "song"

    private fun fileBase(song: Song, candidate: Candidate): String {
        val safe = song.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
        return "$safe-${candidate.id}-${stamp()}"
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun Stage.label() = when (this) {
        Stage.Resampling -> "resampling to 44.1 kHz"
        Stage.LoadingModel -> "loading model"
        Stage.Separating -> "separating"
        Stage.Writing -> "writing stems"
    }
}
