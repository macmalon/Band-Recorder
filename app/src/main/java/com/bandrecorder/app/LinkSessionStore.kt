package com.bandrecorder.app

import com.bandrecorder.core.network.LinkRole
import com.bandrecorder.core.network.RemoteDeviceState

internal object LinkSessionStore {
    fun hostSession(
        sessionId: String,
        selfDeviceId: String,
        selfDeviceName: String,
        port: Int
    ): LinkUiModel {
        return LinkUiModel(
            mode = LinkMode.HOST,
            phase = LinkPhase.LOBBY,
            sessionId = sessionId,
            selfDeviceId = selfDeviceId,
            selfDeviceName = selfDeviceName,
            port = port,
            isConnected = true,
            isHosting = true,
            sessionStatus = "Session maître active",
            devices = listOf(
                LinkDeviceState(
                    deviceId = selfDeviceId,
                    deviceName = selfDeviceName,
                    role = LinkRole.MASTER,
                    isSelf = true,
                    isConnected = true,
                    statusText = "Maître prêt"
                )
            )
        )
    }

    fun joinSession(
        sessionId: String?,
        selfDeviceId: String,
        selfDeviceName: String,
        hostAddress: String,
        port: Int
    ): LinkUiModel {
        return LinkUiModel(
            mode = LinkMode.CLIENT,
            phase = LinkPhase.LOBBY,
            sessionId = sessionId,
            selfDeviceId = selfDeviceId,
            selfDeviceName = selfDeviceName,
            hostAddressInput = hostAddress,
            port = port,
            isConnected = true,
            sessionStatus = "Connecté à $hostAddress:$port"
        )
    }

    fun applySnapshot(current: LinkUiModel, snapshotDevices: List<RemoteDeviceState>, sessionStatus: String): LinkUiModel {
        val selfId = current.selfDeviceId
        val mapped = snapshotDevices.map { LinkDeviceState.fromRemote(it, selfId) }
        return current.copy(
            isConnected = true,
            devices = mapped,
            sessionStatus = sessionStatus
        )
    }

    fun upsertDevice(current: LinkUiModel, device: RemoteDeviceState): LinkUiModel {
        val mapped = LinkDeviceState.fromRemote(device, current.selfDeviceId)
        val updated = current.devices
            .filterNot { it.deviceId == mapped.deviceId } +
            mapped
        return current.copy(devices = updated.sortedWith(compareBy<LinkDeviceState> { !it.isSelf }.thenBy { it.deviceName }))
    }

    fun removePeer(current: LinkUiModel, peerId: String, reason: String?): LinkUiModel {
        val updated = current.devices.filterNot { it.deviceId == peerId }
        return current.copy(
            devices = updated,
            sessionStatus = reason ?: current.sessionStatus
        )
    }

    fun setPhase(current: LinkUiModel, phase: LinkPhase, status: String, takeId: String? = current.activeTakeId, scheduledStartEpochMs: Long? = current.scheduledStartEpochMs): LinkUiModel {
        return current.copy(
            phase = phase,
            sessionStatus = status,
            activeTakeId = takeId,
            scheduledStartEpochMs = scheduledStartEpochMs
        )
    }

    fun setError(current: LinkUiModel, message: String): LinkUiModel {
        return current.copy(errorMessage = message, sessionStatus = message)
    }
}
