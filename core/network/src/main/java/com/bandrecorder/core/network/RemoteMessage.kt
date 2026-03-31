package com.bandrecorder.core.network

enum class LinkRole {
    MASTER,
    CLIENT
}

data class RemoteDeviceState(
    val deviceId: String,
    val deviceName: String,
    val role: LinkRole,
    val isConnected: Boolean = true,
    val isReady: Boolean = false,
    val isBalancing: Boolean = false,
    val isRecording: Boolean = false,
    val latencyMs: Int? = null,
    val peakDb: Float? = null,
    val rmsDb: Float? = null,
    val seconds: Int? = null,
    val lastTakeId: String? = null,
    val statusText: String? = null,
    val errorText: String? = null,
    val lastSeenEpochMs: Long = System.currentTimeMillis()
)

sealed class RemoteMessage {
    abstract val protocolVersion: Int
    abstract val sessionId: String?
    abstract val senderDeviceId: String
    abstract val senderDeviceName: String
    abstract val senderRole: LinkRole
    abstract val sentAtEpochMs: Long

    data class Hello(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String?,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis()
    ) : RemoteMessage()

    data class JoinSession(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis()
    ) : RemoteMessage()

    data class LeaveSession(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis(),
        val reason: String? = null
    ) : RemoteMessage()

    data class StartBalance(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis(),
        val commandId: String,
        val durationSec: Int
    ) : RemoteMessage()

    data class StopBalance(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis(),
        val commandId: String
    ) : RemoteMessage()

    data class ArmRecord(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis(),
        val takeId: String
    ) : RemoteMessage()

    data class StartRecord(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis(),
        val takeId: String,
        val startAtEpochMs: Long
    ) : RemoteMessage()

    data class StopRecord(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis(),
        val takeId: String? = null
    ) : RemoteMessage()

    data class Ping(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String?,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis(),
        val pingId: String
    ) : RemoteMessage()

    data class Pong(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String?,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis(),
        val pingId: String,
        val originalSentAtEpochMs: Long
    ) : RemoteMessage()

    data class DeviceStatus(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis(),
        val device: RemoteDeviceState
    ) : RemoteMessage()

    data class SessionSnapshot(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis(),
        val sessionClockMs: Long,
        val devices: List<RemoteDeviceState>,
        val sessionStatus: String
    ) : RemoteMessage()

    data class Error(
        override val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
        override val sessionId: String?,
        override val senderDeviceId: String,
        override val senderDeviceName: String,
        override val senderRole: LinkRole,
        override val sentAtEpochMs: Long = System.currentTimeMillis(),
        val message: String
    ) : RemoteMessage()

    companion object {
        const val CURRENT_PROTOCOL_VERSION = 1
    }
}
