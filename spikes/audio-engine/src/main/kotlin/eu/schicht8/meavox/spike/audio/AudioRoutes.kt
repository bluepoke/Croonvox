// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.spike.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Output route classes relevant to Monitoring and per-route Latency Calibration (ADR 4). */
enum class RouteKind(val label: String, val monitoringAllowed: Boolean) {
    Speaker("Built-in speaker", false),
    Wired("Wired headphones", true),
    Usb("USB audio", true),
    Bluetooth("Bluetooth", false),
    Other("Other", false),
}

data class OutputRoute(val kind: RouteKind, val name: String, val deviceId: Int)

object AudioRoutes {
    private val bluetoothTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )
    private val wiredTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
    )
    private val usbTypes = setOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )
    private val speakerTypes = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
    )

    fun kindOf(type: Int): RouteKind = when (type) {
        in bluetoothTypes -> RouteKind.Bluetooth
        in wiredTypes -> RouteKind.Wired
        in usbTypes -> RouteKind.Usb
        in speakerTypes -> RouteKind.Speaker
        else -> RouteKind.Other
    }

    /**
     * Resolves the route the output stream actually uses. When the stream reports no device id
     * the route is unknown: it is classified as [RouteKind.Other] (Monitoring blocked, its own
     * calibration slot) rather than guessed, so Bluetooth is never mistaken for wired.
     */
    fun current(audioManager: AudioManager, streamDeviceId: Int): OutputRoute {
        if (streamDeviceId == 0) return OutputRoute(RouteKind.Other, "Unknown (no device id)", 0)
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == streamDeviceId }
        return device?.toRoute() ?: OutputRoute(RouteKind.Other, "Unknown (device $streamDeviceId)", streamDeviceId)
    }

    private fun AudioDeviceInfo.toRoute() =
        OutputRoute(kindOf(type), productName?.toString().orEmpty().ifBlank { kindOf(type).label }, id)
}
