// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.spike.audio

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI state and actions for the spike screen. Engine calls run on one serial background
 * dispatcher because they can block while the audio thread acknowledges Idle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpikeController(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val calibrations = CalibrationStore(context)
    private val engineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var reopenJob: Job? = null

    var stats by mutableStateOf(EngineStats.EMPTY)
        private set
    var route by mutableStateOf(OutputRoute(RouteKind.Other, "Unknown", 0))
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var backingLabel by mutableStateOf("None")
        private set
    var loadProgress by mutableStateOf<Float?>(null)
        private set
    var transpose by mutableIntStateOf(0)
        private set
    var monitoring by mutableStateOf(false)
        private set
    var monitorGain by mutableFloatStateOf(1f)
        private set
    var startSeconds by mutableFloatStateOf(0f)
    var savedCalibrationFrames by mutableStateOf<Long?>(null)
        private set

    private var lastRouteKey: String? = null
    private var lastRouteGeneration = 0
    private var lastState = EngineStats.State.Idle

    fun start() {
        runEngine {
            if (!NativeEngine.open()) post("Could not open audio streams")
        }
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                // Not on engineDispatcher: stats must keep updating while a file decodes.
                val s = withContext(Dispatchers.IO) { NativeEngine.stats() }
                onStats(s)
                delay(100)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        reopenJob?.cancel()
        runEngine { NativeEngine.close() }
    }

    fun dispose() {
        scope.cancel()
    }

    /** Output devices were added/removed: reopen so the streams follow the new route. */
    fun onDevicesChanged() {
        reopenJob?.cancel()
        reopenJob = scope.launch {
            delay(300) // let the system finish switching the route
            withContext(engineDispatcher) {
                if (!NativeEngine.reopen()) post("Reopen after route change failed")
            }
            post("Route changed: streams reopened")
        }
    }

    fun loadTestTone(seconds: Double = 60.0) = runEngine {
        NativeEngine.generateTestBacking(seconds)
        backingLabel = "Test tone (pad + 120 bpm click, ${seconds.toInt()} s)"
    }

    fun loadFile(uri: Uri, name: String) = runEngine {
        loadProgress = 0f
        try {
            AudioFileDecoder.decodeIntoEngine(context, uri) { p -> loadProgress = p }
            backingLabel = name
        } catch (e: Exception) {
            backingLabel = "None"
            post("Decode failed: ${e.message}")
        } finally {
            loadProgress = null
        }
    }

    private fun startFrame(): Long = (startSeconds * stats.sampleRate).toLong()

    fun play() = runEngine { if (!NativeEngine.play(startFrame())) post("Load Backing Audio first") }

    fun record() = runEngine { if (!NativeEngine.record(startFrame())) post("Load Backing Audio first") }

    fun review() = runEngine { if (!NativeEngine.review()) post("Record a Take first") }

    fun stopTransport() = runEngine { NativeEngine.stop() }

    fun calibrate() = runEngine { if (!NativeEngine.startCalibration()) post("Streams not open") }

    fun clearCalibration() = runEngine {
        calibrations.clear(route.kind, stats.sampleRate)
        savedCalibrationFrames = null
        NativeEngine.setLatencyCompensation(null)
    }

    fun changeTranspose(semitones: Int) {
        transpose = semitones.coerceIn(NativeEngine.TRANSPOSE_MIN, NativeEngine.TRANSPOSE_MAX)
        NativeEngine.setTranspose(transpose)
    }

    fun changeMonitoring(enabled: Boolean) {
        monitoring = enabled && route.kind.monitoringAllowed
        NativeEngine.setMonitoring(monitoring)
    }

    fun changeMonitorGain(gain: Float) {
        monitorGain = gain
        NativeEngine.setMonitorGain(gain)
    }

    fun saveAlignmentWav() = runEngine {
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: context.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "alignment-${route.kind.name.lowercase()}-$stamp.wav")
        post(if (NativeEngine.saveAlignmentWav(file.absolutePath)) "Saved ${file.absolutePath}" else "No Take to save")
    }

    fun dismissMessage() {
        message = null
    }

    private fun onStats(s: EngineStats) {
        stats = s
        route = AudioRoutes.current(audioManager, s.outputDeviceId)

        // The engine forces Monitoring off and drops the Take on every reopen; mirror that.
        if (s.routeGeneration != lastRouteGeneration) {
            lastRouteGeneration = s.routeGeneration
            monitoring = false
        }

        // Per-route Latency Calibration: apply the stored value whenever route or rate changes.
        val key = "${route.kind}.${s.sampleRate}"
        if (key != lastRouteKey && s.sampleRate > 0) {
            lastRouteKey = key
            savedCalibrationFrames = calibrations.get(route.kind, s.sampleRate)
            NativeEngine.setLatencyCompensation(savedCalibrationFrames)
            if (!route.kind.monitoringAllowed && monitoring) changeMonitoring(false)
        }

        if (lastState == EngineStats.State.Calibrating && s.state != EngineStats.State.Calibrating) {
            val frames = s.calibrationFrames
            if (frames != null) {
                calibrations.put(route.kind, s.sampleRate, frames)
                savedCalibrationFrames = frames
                NativeEngine.setLatencyCompensation(frames)
                post("Calibrated ${route.kind.label}: %.1f ms (%d/8 clicks)".format(s.framesToMs(frames), s.calibrationClicks))
            } else {
                post("Calibration failed: only ${s.calibrationClicks}/8 clicks detected")
            }
        }
        lastState = s.state
    }

    private fun post(text: String) {
        scope.launch { message = text }
    }

    private fun runEngine(block: suspend () -> Unit) {
        scope.launch(engineDispatcher) { block() }
    }
}
