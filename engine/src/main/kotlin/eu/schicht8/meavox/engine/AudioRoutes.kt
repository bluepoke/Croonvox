// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.engine

import android.media.AudioDeviceInfo
import android.media.AudioManager
import eu.schicht8.meavox.core.model.InputDevice
import eu.schicht8.meavox.core.model.OutputRoute

/** Maps the device IDs Oboe reports to the domain's route kinds. An unknown ID (0) is Other. */
internal class AudioRoutes(private val audioManager: AudioManager) {
    fun output(deviceId: Int): OutputRoute = when (typeOf(deviceId, AudioManager.GET_DEVICES_OUTPUTS)) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputRoute.Speaker
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> OutputRoute.Wired
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputRoute.Usb
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> OutputRoute.Bluetooth
        else -> OutputRoute.Other
    }

    fun input(deviceId: Int): InputDevice = when (typeOf(deviceId, AudioManager.GET_DEVICES_INPUTS)) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputDevice.BuiltIn
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> InputDevice.Wired
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> InputDevice.Usb
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> InputDevice.Bluetooth
        else -> InputDevice.Other
    }

    private fun typeOf(deviceId: Int, flags: Int): Int? =
        if (deviceId == 0) null else audioManager.getDevices(flags).firstOrNull { it.id == deviceId }?.type
}
