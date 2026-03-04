package com.bandrecorder.core.network

sealed class RemoteMessage {
    data object StartRecord : RemoteMessage()
    data object StopRecord : RemoteMessage()
    data class AddMarker(val label: String) : RemoteMessage()
    data class StatusUpdate(val peakDb: Float, val rmsDb: Float, val seconds: Int) : RemoteMessage()
}
