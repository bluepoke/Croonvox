// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private lateinit var controller: SpikeController
    private var micGranted by mutableStateOf(false)
    private var started = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micGranted = granted
            if (granted) startEngine()
        }

    private var deviceCallback: AudioDeviceCallback? = null

    /** A fresh callback per registration: registering reports all current devices once, which is ignored. */
    private fun newDeviceCallback() = object : AudioDeviceCallback() {
        private var initialDelivered = false

        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!initialDelivered) {
                initialDelivered = true
                return
            }
            if (addedDevices.any { it.isSink }) controller.onDevicesChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.isSink }) controller.onDevicesChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = SpikeController(applicationContext)
        micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = when {
                android.os.Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
                android.os.Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                SpikeScreen(
                    controller = controller,
                    micGranted = micGranted,
                    onRequestMic = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (micGranted) startEngine()
    }

    override fun onStop() {
        if (started) {
            deviceCallback?.let { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(it) }
            deviceCallback = null
            controller.stop()
            started = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        controller.dispose()
        super.onDestroy()
    }

    private fun startEngine() {
        if (started) return
        started = true
        controller.start()
        val callback = newDeviceCallback().also { deviceCallback = it }
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }
}
