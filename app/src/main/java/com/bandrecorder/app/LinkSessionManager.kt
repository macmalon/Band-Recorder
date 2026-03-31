package com.bandrecorder.app

import com.bandrecorder.core.network.HotspotSocketTransport
import com.bandrecorder.core.network.LinkRole
import com.bandrecorder.core.network.LinkTransport
import com.bandrecorder.core.network.LinkTransportEvent
import com.bandrecorder.core.network.RemoteDeviceState
import com.bandrecorder.core.network.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

internal object LinkSessionManager {
    private const val DEFAULT_PORT = 18777
    private const val RECORDING_START_DELAY_MS = 2500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport: LinkTransport = HotspotSocketTransport()
    private val _uiState = MutableStateFlow(LinkUiModel(port = DEFAULT_PORT))
    private val _actions = MutableSharedFlow<LinkAction>(extraBufferCapacity = 32)
    private val pendingPings = linkedMapOf<String, Long>()
    private val peerDeviceIds = linkedMapOf<String, String>()

    val uiState: StateFlow<LinkUiModel> = _uiState.asStateFlow()
    val actions: SharedFlow<LinkAction> = _actions.asSharedFlow()

    private var selfDeviceId: String = UUID.randomUUID().toString()
    private var selfDeviceName: String = defaultDeviceName()

    init {
        scope.launch {
            transport.events.collect { handleTransportEvent(it) }
        }
    }

    fun updateHostAddressInput(value: String) {
        _uiState.value = _uiState.value.copy(hostAddressInput = value.trim())
    }

    fun configureIdentity(deviceName: String) {
        selfDeviceName = deviceName.ifBlank { defaultDeviceName() }
        if (_uiState.value.selfDeviceName != selfDeviceName) {
            _uiState.value = _uiState.value.copy(selfDeviceName = selfDeviceName, selfDeviceId = selfDeviceId)
        }
    }

    fun createSession(port: Int = _uiState.value.port) {
        val sessionId = "link_${System.currentTimeMillis()}"
        peerDeviceIds.clear()
        pendingPings.clear()
        _uiState.value = LinkSessionStore.hostSession(
            sessionId = sessionId,
            selfDeviceId = selfDeviceId,
            selfDeviceName = selfDeviceName,
            port = port
        )
        scope.launch {
            transport.host(port)
        }
    }

    fun joinSession(host: String = _uiState.value.hostAddressInput, port: Int = _uiState.value.port) {
        peerDeviceIds.clear()
        pendingPings.clear()
        _uiState.value = LinkSessionStore.joinSession(
            sessionId = null,
            selfDeviceId = selfDeviceId,
            selfDeviceName = selfDeviceName,
            hostAddress = host,
            port = port
        )
        scope.launch {
            transport.connect(host, port)
        }
    }

    fun disconnect(reason: String = "Session fermée") {
        val current = _uiState.value
        peerDeviceIds.clear()
        pendingPings.clear()
        scope.launch {
            current.sessionId?.let { sessionId ->
                transport.broadcast(
                    RemoteMessage.LeaveSession(
                        sessionId = sessionId,
                        senderDeviceId = selfDeviceId,
                        senderDeviceName = selfDeviceName,
                        senderRole = current.mode.toRole(),
                        reason = reason
                    )
                )
            }
            transport.stop()
        }
        _uiState.value = LinkUiModel(
            hostAddressInput = current.hostAddressInput,
            port = current.port,
            selfDeviceId = selfDeviceId,
            selfDeviceName = selfDeviceName
        )
    }

    fun startRemoteBalance(durationSec: Int) {
        val current = _uiState.value
        val sessionId = current.sessionId ?: return
        if (current.mode != LinkMode.HOST) return
        val commandId = "bal_${System.currentTimeMillis()}"
        _uiState.value = LinkSessionStore.setPhase(current, LinkPhase.BALANCING, "Balance distante en cours")
        val message = RemoteMessage.StartBalance(
            sessionId = sessionId,
            senderDeviceId = selfDeviceId,
            senderDeviceName = selfDeviceName,
            senderRole = LinkRole.MASTER,
            commandId = commandId,
            durationSec = durationSec
        )
        scope.launch {
            transport.broadcast(message)
            _actions.emit(LinkAction.StartBalance(sessionId, commandId, durationSec))
        }
    }

    fun stopRemoteBalance() {
        val current = _uiState.value
        val sessionId = current.sessionId ?: return
        val commandId = "bal_stop_${System.currentTimeMillis()}"
        val message = RemoteMessage.StopBalance(
            sessionId = sessionId,
            senderDeviceId = selfDeviceId,
            senderDeviceName = selfDeviceName,
            senderRole = current.mode.toRole(),
            commandId = commandId
        )
        scope.launch {
            transport.broadcast(message)
            _actions.emit(LinkAction.StopBalance(sessionId, commandId))
        }
        _uiState.value = LinkSessionStore.setPhase(current, LinkPhase.LOBBY, "Balance interrompue")
    }

    fun armRemoteRecording() {
        val current = _uiState.value
        val sessionId = current.sessionId ?: return
        if (current.mode != LinkMode.HOST) return
        val takeId = "take_${System.currentTimeMillis()}"
        val message = RemoteMessage.ArmRecord(
            sessionId = sessionId,
            senderDeviceId = selfDeviceId,
            senderDeviceName = selfDeviceName,
            senderRole = LinkRole.MASTER,
            takeId = takeId
        )
        _uiState.value = LinkSessionStore.setPhase(current, LinkPhase.ARMED, "Prise armée", takeId = takeId)
        scope.launch {
            transport.broadcast(message)
            _actions.emit(LinkAction.ArmRecord(sessionId, takeId))
        }
    }

    fun startArmedRecording() {
        val current = _uiState.value
        val sessionId = current.sessionId ?: return
        val takeId = current.activeTakeId ?: return
        val startAt = System.currentTimeMillis() + RECORDING_START_DELAY_MS
        val message = RemoteMessage.StartRecord(
            sessionId = sessionId,
            senderDeviceId = selfDeviceId,
            senderDeviceName = selfDeviceName,
            senderRole = current.mode.toRole(),
            takeId = takeId,
            startAtEpochMs = startAt
        )
        _uiState.value = LinkSessionStore.setPhase(
            current,
            LinkPhase.RECORDING,
            "Démarrage synchronisé prévu",
            takeId = takeId,
            scheduledStartEpochMs = startAt
        )
        scope.launch {
            transport.broadcast(message)
            _actions.emit(LinkAction.StartRecord(sessionId, takeId, startAt))
        }
    }

    fun stopRemoteRecording() {
        val current = _uiState.value
        val sessionId = current.sessionId ?: return
        val message = RemoteMessage.StopRecord(
            sessionId = sessionId,
            senderDeviceId = selfDeviceId,
            senderDeviceName = selfDeviceName,
            senderRole = current.mode.toRole(),
            takeId = current.activeTakeId
        )
        scope.launch {
            transport.broadcast(message)
            _actions.emit(LinkAction.StopRecord(sessionId, current.activeTakeId))
        }
        _uiState.value = LinkSessionStore.setPhase(current, LinkPhase.SUMMARY, "Enregistrement arrêté")
    }

    fun updateLocalDeviceStatus(
        isReady: Boolean = false,
        isBalancing: Boolean = false,
        isRecording: Boolean = false,
        latencyMs: Int? = currentSelf().latencyMs,
        peakDb: Float? = currentSelf().peakDb,
        rmsDb: Float? = currentSelf().rmsDb,
        seconds: Int? = currentSelf().seconds,
        lastTakeId: String? = currentSelf().lastTakeId,
        statusText: String? = currentSelf().statusText,
        errorText: String? = null
    ) {
        val current = _uiState.value
        val sessionId = current.sessionId ?: return
        val updated = RemoteDeviceState(
            deviceId = selfDeviceId,
            deviceName = selfDeviceName,
            role = current.mode.toRole(),
            isConnected = true,
            isReady = isReady,
            isBalancing = isBalancing,
            isRecording = isRecording,
            latencyMs = latencyMs,
            peakDb = peakDb,
            rmsDb = rmsDb,
            seconds = seconds,
            lastTakeId = lastTakeId,
            statusText = statusText,
            errorText = errorText
        )
        _uiState.value = LinkSessionStore.upsertDevice(current, updated)
        scope.launch {
            if (current.mode == LinkMode.HOST) {
                broadcastSnapshot()
            } else {
                transport.broadcast(
                    RemoteMessage.DeviceStatus(
                        sessionId = sessionId,
                        senderDeviceId = selfDeviceId,
                        senderDeviceName = selfDeviceName,
                        senderRole = LinkRole.CLIENT,
                        device = updated
                    )
                )
            }
        }
    }

    fun reportSummary(summary: String) {
        val current = _uiState.value
        _uiState.value = current.copy(lastSummary = summary)
    }

    private suspend fun handleTransportEvent(event: LinkTransportEvent) {
        when (event) {
            is LinkTransportEvent.Connected -> {
                val current = _uiState.value
                if (current.mode == LinkMode.CLIENT) {
                    transport.broadcast(
                        RemoteMessage.Hello(
                            sessionId = current.sessionId,
                            senderDeviceId = selfDeviceId,
                            senderDeviceName = selfDeviceName,
                            senderRole = LinkRole.CLIENT
                        )
                    )
                } else {
                    val pingId = UUID.randomUUID().toString()
                    pendingPings[pingId] = System.currentTimeMillis()
                    transport.sendTo(
                        event.peer.peerId,
                        RemoteMessage.Ping(
                            sessionId = current.sessionId,
                            senderDeviceId = selfDeviceId,
                            senderDeviceName = selfDeviceName,
                            senderRole = LinkRole.MASTER,
                            pingId = pingId
                        )
                    )
                }
            }
            is LinkTransportEvent.Disconnected -> {
                val deviceId = peerDeviceIds.remove(event.peerId) ?: event.peerId
                _uiState.value = LinkSessionStore.removePeer(_uiState.value, deviceId, event.reason)
            }
            is LinkTransportEvent.Failure -> {
                _uiState.value = LinkSessionStore.setError(_uiState.value, event.reason)
            }
            is LinkTransportEvent.Hosting -> {
                _uiState.value = _uiState.value.copy(isHosting = true, port = event.port)
            }
            is LinkTransportEvent.Message -> {
                handleRemoteMessage(event.peerId, event.payload)
            }
        }
    }

    private suspend fun handleRemoteMessage(peerId: String, message: RemoteMessage) {
        when (message) {
            is RemoteMessage.Hello -> {
                if (_uiState.value.mode == LinkMode.HOST) {
                    peerDeviceIds[peerId] = message.senderDeviceId
                    val sessionId = _uiState.value.sessionId ?: return
                    transport.sendTo(
                        peerId,
                        RemoteMessage.JoinSession(
                            sessionId = sessionId,
                            senderDeviceId = selfDeviceId,
                            senderDeviceName = selfDeviceName,
                            senderRole = LinkRole.MASTER
                        )
                    )
                    val remote = RemoteDeviceState(
                        deviceId = message.senderDeviceId,
                        deviceName = message.senderDeviceName,
                        role = LinkRole.CLIENT,
                        isConnected = true,
                        statusText = "Client connecté"
                    )
                    _uiState.value = LinkSessionStore.upsertDevice(_uiState.value, remote)
                    broadcastSnapshot()
                }
            }
            is RemoteMessage.JoinSession -> {
                if (_uiState.value.mode == LinkMode.CLIENT) {
                    _uiState.value = LinkSessionStore.joinSession(
                        sessionId = message.sessionId,
                        selfDeviceId = selfDeviceId,
                        selfDeviceName = selfDeviceName,
                        hostAddress = _uiState.value.hostAddressInput,
                        port = _uiState.value.port
                    )
                    updateLocalDeviceStatus(statusText = "Client prêt")
                }
            }
            is RemoteMessage.LeaveSession -> {
                _uiState.value = LinkSessionStore.removePeer(_uiState.value, message.senderDeviceId, message.reason)
            }
            is RemoteMessage.StartBalance -> {
                _uiState.value = LinkSessionStore.setPhase(_uiState.value, LinkPhase.BALANCING, "Balance lancée par ${message.senderDeviceName}")
                _actions.emit(LinkAction.StartBalance(message.sessionId, message.commandId, message.durationSec))
            }
            is RemoteMessage.StopBalance -> {
                _uiState.value = LinkSessionStore.setPhase(_uiState.value, LinkPhase.LOBBY, "Balance arrêtée")
                _actions.emit(LinkAction.StopBalance(message.sessionId, message.commandId))
            }
            is RemoteMessage.ArmRecord -> {
                _uiState.value = LinkSessionStore.setPhase(_uiState.value, LinkPhase.ARMED, "Prise armée", takeId = message.takeId)
                _actions.emit(LinkAction.ArmRecord(message.sessionId, message.takeId))
            }
            is RemoteMessage.StartRecord -> {
                _uiState.value = LinkSessionStore.setPhase(
                    _uiState.value,
                    LinkPhase.RECORDING,
                    "Enregistrement synchronisé",
                    takeId = message.takeId,
                    scheduledStartEpochMs = message.startAtEpochMs
                )
                _actions.emit(LinkAction.StartRecord(message.sessionId, message.takeId, message.startAtEpochMs))
            }
            is RemoteMessage.StopRecord -> {
                _uiState.value = LinkSessionStore.setPhase(_uiState.value, LinkPhase.SUMMARY, "Prise terminée")
                _actions.emit(LinkAction.StopRecord(message.sessionId, message.takeId))
            }
            is RemoteMessage.Ping -> {
                transport.sendTo(
                    peerId,
                    RemoteMessage.Pong(
                        sessionId = message.sessionId,
                        senderDeviceId = selfDeviceId,
                        senderDeviceName = selfDeviceName,
                        senderRole = _uiState.value.mode.toRole(),
                        pingId = message.pingId,
                        originalSentAtEpochMs = message.sentAtEpochMs
                    )
                )
            }
            is RemoteMessage.Pong -> {
                val startedAt = pendingPings.remove(message.pingId) ?: return
                val latency = ((System.currentTimeMillis() - startedAt) / 2L).toInt()
                if (_uiState.value.mode == LinkMode.HOST) {
                    val remote = _uiState.value.devices.firstOrNull { it.deviceId == peerId || it.deviceId == message.senderDeviceId }
                    val updated = RemoteDeviceState(
                        deviceId = remote?.deviceId ?: message.senderDeviceId,
                        deviceName = remote?.deviceName ?: message.senderDeviceName,
                        role = remote?.role ?: LinkRole.CLIENT,
                        isConnected = true,
                        isReady = remote?.isReady ?: false,
                        isBalancing = remote?.isBalancing ?: false,
                        isRecording = remote?.isRecording ?: false,
                        latencyMs = latency,
                        peakDb = remote?.peakDb,
                        rmsDb = remote?.rmsDb,
                        seconds = remote?.seconds,
                        lastTakeId = remote?.lastTakeId,
                        statusText = remote?.statusText ?: "Latence mesurée",
                        errorText = remote?.errorText
                    )
                    _uiState.value = LinkSessionStore.upsertDevice(_uiState.value, updated)
                    broadcastSnapshot()
                } else {
                    updateLocalDeviceStatus(latencyMs = latency, statusText = "Latence ~${latency} ms")
                }
            }
            is RemoteMessage.DeviceStatus -> {
                if (_uiState.value.mode == LinkMode.HOST) {
                    peerDeviceIds[peerId] = message.senderDeviceId
                    _uiState.value = LinkSessionStore.upsertDevice(_uiState.value, message.device)
                    broadcastSnapshot()
                }
            }
            is RemoteMessage.SessionSnapshot -> {
                if (_uiState.value.mode == LinkMode.CLIENT) {
                    _uiState.value = LinkSessionStore.applySnapshot(_uiState.value, message.devices, message.sessionStatus)
                }
            }
            is RemoteMessage.Error -> {
                _uiState.value = LinkSessionStore.setError(_uiState.value, message.message)
            }
        }
    }

    private suspend fun broadcastSnapshot() {
        val current = _uiState.value
        val sessionId = current.sessionId ?: return
        if (current.mode != LinkMode.HOST) return
        transport.broadcast(
            RemoteMessage.SessionSnapshot(
                sessionId = sessionId,
                senderDeviceId = selfDeviceId,
                senderDeviceName = selfDeviceName,
                senderRole = LinkRole.MASTER,
                sessionClockMs = System.currentTimeMillis(),
                devices = current.devices.map {
                    RemoteDeviceState(
                        deviceId = it.deviceId,
                        deviceName = it.deviceName,
                        role = it.role,
                        isConnected = it.isConnected,
                        isReady = it.isReady,
                        isBalancing = it.isBalancing,
                        isRecording = it.isRecording,
                        latencyMs = it.latencyMs,
                        peakDb = it.peakDb,
                        rmsDb = it.rmsDb,
                        seconds = it.seconds,
                        lastTakeId = it.lastTakeId,
                        statusText = it.statusText,
                        errorText = it.errorText,
                        lastSeenEpochMs = it.lastSeenEpochMs
                    )
                },
                sessionStatus = current.sessionStatus
            )
        )
    }

    private fun currentSelf(): LinkDeviceState {
        return _uiState.value.devices.firstOrNull { it.deviceId == selfDeviceId }
            ?: LinkDeviceState(
                deviceId = selfDeviceId,
                deviceName = selfDeviceName,
                role = _uiState.value.mode.toRole(),
                isSelf = true
            )
    }

    private fun LinkMode.toRole(): LinkRole = when (this) {
        LinkMode.HOST -> LinkRole.MASTER
        LinkMode.CLIENT -> LinkRole.CLIENT
        LinkMode.IDLE -> LinkRole.CLIENT
    }

    private fun defaultDeviceName(): String {
        val language = Locale.getDefault().language.ifBlank { "device" }
        return "BandRecorder-${language.take(2)}-${selfDeviceId.take(4)}"
    }
}
