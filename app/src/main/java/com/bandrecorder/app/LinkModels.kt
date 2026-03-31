package com.bandrecorder.app

import com.bandrecorder.core.network.LinkRole
import com.bandrecorder.core.network.RemoteDeviceState

enum class LinkMode {
    IDLE,
    HOST,
    CLIENT
}

enum class LinkPhase {
    IDLE,
    LOBBY,
    BALANCING,
    ARMED,
    RECORDING,
    SUMMARY
}

data class LinkDeviceState(
    val deviceId: String,
    val deviceName: String,
    val role: LinkRole,
    val isSelf: Boolean = false,
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
) {
    companion object {
        fun fromRemote(device: RemoteDeviceState, selfDeviceId: String?): LinkDeviceState {
            return LinkDeviceState(
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                role = device.role,
                isSelf = device.deviceId == selfDeviceId,
                isConnected = device.isConnected,
                isReady = device.isReady,
                isBalancing = device.isBalancing,
                isRecording = device.isRecording,
                latencyMs = device.latencyMs,
                peakDb = device.peakDb,
                rmsDb = device.rmsDb,
                seconds = device.seconds,
                lastTakeId = device.lastTakeId,
                statusText = device.statusText,
                errorText = device.errorText,
                lastSeenEpochMs = device.lastSeenEpochMs
            )
        }
    }
}

data class LinkUiModel(
    val mode: LinkMode = LinkMode.IDLE,
    val phase: LinkPhase = LinkPhase.IDLE,
    val sessionId: String? = null,
    val selfDeviceId: String? = null,
    val selfDeviceName: String = "",
    val hostAddressInput: String = "192.168.43.1",
    val port: Int = 18777,
    val isConnected: Boolean = false,
    val isHosting: Boolean = false,
    val sessionStatus: String = "Mode Link inactif",
    val errorMessage: String? = null,
    val activeTakeId: String? = null,
    val scheduledStartEpochMs: Long? = null,
    val lastSummary: String? = null,
    val devices: List<LinkDeviceState> = emptyList()
) {
    val canCreateSession: Boolean get() = mode == LinkMode.IDLE
    val canJoinSession: Boolean get() = mode == LinkMode.IDLE
    val canLaunchBalance: Boolean get() = mode == LinkMode.HOST && isConnected
    val canArmRecording: Boolean get() = mode == LinkMode.HOST && isConnected
    val canStartRecording: Boolean get() = mode == LinkMode.HOST && phase == LinkPhase.ARMED
    val canStopRecording: Boolean get() = isConnected && phase == LinkPhase.RECORDING
}

sealed class LinkAction {
    data class StartBalance(val sessionId: String, val commandId: String, val durationSec: Int) : LinkAction()
    data class StopBalance(val sessionId: String, val commandId: String) : LinkAction()
    data class ArmRecord(val sessionId: String, val takeId: String) : LinkAction()
    data class StartRecord(val sessionId: String, val takeId: String, val startAtEpochMs: Long) : LinkAction()
    data class StopRecord(val sessionId: String, val takeId: String?) : LinkAction()
}

data class SessionTakeMetadata(
    val sessionId: String,
    val takeId: String,
    val deviceId: String,
    val deviceName: String,
    val role: LinkRole,
    val storageLocation: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val estimatedLatencyMs: Int?,
    val scheduledStartEpochMs: Long?,
    val actualStartEpochMs: Long,
    val stoppedAtEpochMs: Long,
    val outputPath: String?
) {
    fun toJsonString(): String = buildString {
        appendLine("{")
        appendLine("  \"sessionId\": \"${jsonEscape(sessionId)}\",")
        appendLine("  \"takeId\": \"${jsonEscape(takeId)}\",")
        appendLine("  \"deviceId\": \"${jsonEscape(deviceId)}\",")
        appendLine("  \"deviceName\": \"${jsonEscape(deviceName)}\",")
        appendLine("  \"role\": \"${role.name}\",")
        appendLine("  \"storageLocation\": \"${jsonEscape(storageLocation)}\",")
        appendLine("  \"sampleRateHz\": $sampleRateHz,")
        appendLine("  \"channelCount\": $channelCount,")
        appendLine("  \"estimatedLatencyMs\": ${estimatedLatencyMs ?: "null"},")
        appendLine("  \"scheduledStartEpochMs\": ${scheduledStartEpochMs ?: "null"},")
        appendLine("  \"actualStartEpochMs\": $actualStartEpochMs,")
        appendLine("  \"stoppedAtEpochMs\": $stoppedAtEpochMs,")
        appendLine("  \"outputPath\": ${outputPath?.let { "\"${jsonEscape(it)}\"" } ?: "null"}")
        append('}')
    }

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}
