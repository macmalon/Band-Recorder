package com.bandrecorder.app

import android.media.AudioDeviceInfo

object MicrophonePolicy {
    fun typeLabel(typeCode: Int): String = when (typeCode) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony Input"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Remote Submix"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Mic/Device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset Mic"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory Mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset Mic"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset Mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO Mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP Input"
        else -> "Type $typeCode"
    }

    fun isRecommended(typeCode: Int): Boolean = when (typeCode) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> true
        else -> false
    }

    fun warningForType(typeCode: Int): String? = when (typeCode) {
        AudioDeviceInfo.TYPE_TELEPHONY ->
            "Telephony path: not intended for music recording quality."
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX ->
            "Remote submix: virtual/system stream, not a physical microphone."
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
            "Bluetooth A2DP input may be routed/processed unpredictably."
        else -> null
    }

    fun chooseAutoMicrophone(options: List<MicrophoneOption>): MicrophoneOption? {
        val builtInRecommended = options.firstOrNull {
            it.typeCode == AudioDeviceInfo.TYPE_BUILTIN_MIC && it.recommended
        }
        if (builtInRecommended != null) return builtInRecommended

        val anyRecommended = options.firstOrNull { it.recommended }
        if (anyRecommended != null) return anyRecommended

        return options.firstOrNull()
    }
}

