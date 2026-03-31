package com.bandrecorder.core.network

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object RemoteMessageCodec {
    fun encode(message: RemoteMessage): String {
        val values = linkedMapOf<String, String>()
        values["type"] = typeOf(message)
        values["protocolVersion"] = message.protocolVersion.toString()
        values["sessionId"] = message.sessionId.orEmpty()
        values["senderDeviceId"] = message.senderDeviceId
        values["senderDeviceName"] = message.senderDeviceName
        values["senderRole"] = message.senderRole.name
        values["sentAtEpochMs"] = message.sentAtEpochMs.toString()

        when (message) {
            is RemoteMessage.ArmRecord -> {
                values["takeId"] = message.takeId
            }
            is RemoteMessage.DeviceStatus -> {
                putDevice(values, "device", message.device)
            }
            is RemoteMessage.Error -> {
                values["message"] = message.message
            }
            is RemoteMessage.Hello -> Unit
            is RemoteMessage.JoinSession -> Unit
            is RemoteMessage.LeaveSession -> {
                values["reason"] = message.reason.orEmpty()
            }
            is RemoteMessage.Ping -> {
                values["pingId"] = message.pingId
            }
            is RemoteMessage.Pong -> {
                values["pingId"] = message.pingId
                values["originalSentAtEpochMs"] = message.originalSentAtEpochMs.toString()
            }
            is RemoteMessage.SessionSnapshot -> {
                values["sessionClockMs"] = message.sessionClockMs.toString()
                values["sessionStatus"] = message.sessionStatus
                values["deviceCount"] = message.devices.size.toString()
                message.devices.forEachIndexed { index, device ->
                    putDevice(values, "devices.$index", device)
                }
            }
            is RemoteMessage.StartBalance -> {
                values["commandId"] = message.commandId
                values["durationSec"] = message.durationSec.toString()
            }
            is RemoteMessage.StartRecord -> {
                values["takeId"] = message.takeId
                values["startAtEpochMs"] = message.startAtEpochMs.toString()
            }
            is RemoteMessage.StopBalance -> {
                values["commandId"] = message.commandId
            }
            is RemoteMessage.StopRecord -> {
                values["takeId"] = message.takeId.orEmpty()
            }
        }

        return values.entries.joinToString("\n") { (key, value) ->
            "${escape(key)}=${escape(value)}"
        }
    }

    fun decode(payload: String): RemoteMessage {
        val map = payload.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "Malformed payload line: $line" }
                unescape(line.substring(0, separator)) to unescape(line.substring(separator + 1))
            }
            .toMap()

        val type = required(map, "type")
        val protocolVersion = required(map, "protocolVersion").toInt()
        val sessionId = map["sessionId"].takeUnless { it.isNullOrBlank() }
        val senderDeviceId = required(map, "senderDeviceId")
        val senderDeviceName = required(map, "senderDeviceName")
        val senderRole = LinkRole.valueOf(required(map, "senderRole"))
        val sentAtEpochMs = required(map, "sentAtEpochMs").toLong()

        return when (type) {
            "Hello" -> RemoteMessage.Hello(
                protocolVersion = protocolVersion,
                sessionId = sessionId,
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderRole = senderRole,
                sentAtEpochMs = sentAtEpochMs
            )
            "JoinSession" -> RemoteMessage.JoinSession(
                protocolVersion = protocolVersion,
                sessionId = required(map, "sessionId"),
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderRole = senderRole,
                sentAtEpochMs = sentAtEpochMs
            )
            "LeaveSession" -> RemoteMessage.LeaveSession(
                protocolVersion = protocolVersion,
                sessionId = required(map, "sessionId"),
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderRole = senderRole,
                sentAtEpochMs = sentAtEpochMs,
                reason = map["reason"].takeUnless { it.isNullOrBlank() }
            )
            "StartBalance" -> RemoteMessage.StartBalance(
                protocolVersion = protocolVersion,
                sessionId = required(map, "sessionId"),
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderRole = senderRole,
                sentAtEpochMs = sentAtEpochMs,
                commandId = required(map, "commandId"),
                durationSec = required(map, "durationSec").toInt()
            )
            "StopBalance" -> RemoteMessage.StopBalance(
                protocolVersion = protocolVersion,
                sessionId = required(map, "sessionId"),
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderRole = senderRole,
                sentAtEpochMs = sentAtEpochMs,
                commandId = required(map, "commandId")
            )
            "ArmRecord" -> RemoteMessage.ArmRecord(
                protocolVersion = protocolVersion,
                sessionId = required(map, "sessionId"),
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderRole = senderRole,
                sentAtEpochMs = sentAtEpochMs,
                takeId = required(map, "takeId")
            )
            "StartRecord" -> RemoteMessage.StartRecord(
                protocolVersion = protocolVersion,
                sessionId = required(map, "sessionId"),
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderRole = senderRole,
                sentAtEpochMs = sentAtEpochMs,
                takeId = required(map, "takeId"),
                startAtEpochMs = required(map, "startAtEpochMs").toLong()
            )
            "StopRecord" -> RemoteMessage.StopRecord(
                protocolVersion = protocolVersion,
                sessionId = required(map, "sessionId"),
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderRole = senderRole,
                sentAtEpochMs = sentAtEpochMs,
                takeId = map["takeId"].takeUnless { it.isNullOrBlank() }
            )
            "Ping" -> RemoteMessage.Ping(
                protocolVersion = protocolVersion,
                sessionId = sessionId,
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderRole = senderRole,
                sentAtEpochMs = sentAtEpochMs,
                pingId = required(map, "pingId")
            )
            "Pong" -> RemoteMessage.Pong(
                protocolVersion = protocolVersion,
                sessionId = sessionId,
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderRole = senderRole,
                sentAtEpochMs = sentAtEpochMs,
                pingId = required(map, "pingId"),
                originalSentAtEpochMs = required(map, "originalSentAtEpochMs").toLong()
            )
            "DeviceStatus" -> RemoteMessage.DeviceStatus(
                protocolVersion = protocolVersion,
                sessionId = required(map, "sessionId"),
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderRole = senderRole,
                sentAtEpochMs = sentAtEpochMs,
                device = parseDevice(map, "device")
            )
            "SessionSnapshot" -> {
                val deviceCount = required(map, "deviceCount").toInt()
                RemoteMessage.SessionSnapshot(
                    protocolVersion = protocolVersion,
                    sessionId = required(map, "sessionId"),
                    senderDeviceId = senderDeviceId,
                    senderDeviceName = senderDeviceName,
                    senderRole = senderRole,
                    sentAtEpochMs = sentAtEpochMs,
                    sessionClockMs = required(map, "sessionClockMs").toLong(),
                    devices = List(deviceCount) { index -> parseDevice(map, "devices.$index") },
                    sessionStatus = required(map, "sessionStatus")
                )
            }
            "Error" -> RemoteMessage.Error(
                protocolVersion = protocolVersion,
                sessionId = sessionId,
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderRole = senderRole,
                sentAtEpochMs = sentAtEpochMs,
                message = required(map, "message")
            )
            else -> error("Unsupported remote message type: $type")
        }
    }

    private fun typeOf(message: RemoteMessage): String = when (message) {
        is RemoteMessage.ArmRecord -> "ArmRecord"
        is RemoteMessage.DeviceStatus -> "DeviceStatus"
        is RemoteMessage.Error -> "Error"
        is RemoteMessage.Hello -> "Hello"
        is RemoteMessage.JoinSession -> "JoinSession"
        is RemoteMessage.LeaveSession -> "LeaveSession"
        is RemoteMessage.Ping -> "Ping"
        is RemoteMessage.Pong -> "Pong"
        is RemoteMessage.SessionSnapshot -> "SessionSnapshot"
        is RemoteMessage.StartBalance -> "StartBalance"
        is RemoteMessage.StartRecord -> "StartRecord"
        is RemoteMessage.StopBalance -> "StopBalance"
        is RemoteMessage.StopRecord -> "StopRecord"
    }

    private fun putDevice(target: MutableMap<String, String>, prefix: String, device: RemoteDeviceState) {
        target["$prefix.deviceId"] = device.deviceId
        target["$prefix.deviceName"] = device.deviceName
        target["$prefix.role"] = device.role.name
        target["$prefix.isConnected"] = device.isConnected.toString()
        target["$prefix.isReady"] = device.isReady.toString()
        target["$prefix.isBalancing"] = device.isBalancing.toString()
        target["$prefix.isRecording"] = device.isRecording.toString()
        target["$prefix.latencyMs"] = device.latencyMs?.toString().orEmpty()
        target["$prefix.peakDb"] = device.peakDb?.toString().orEmpty()
        target["$prefix.rmsDb"] = device.rmsDb?.toString().orEmpty()
        target["$prefix.seconds"] = device.seconds?.toString().orEmpty()
        target["$prefix.lastTakeId"] = device.lastTakeId.orEmpty()
        target["$prefix.statusText"] = device.statusText.orEmpty()
        target["$prefix.errorText"] = device.errorText.orEmpty()
        target["$prefix.lastSeenEpochMs"] = device.lastSeenEpochMs.toString()
    }

    private fun parseDevice(map: Map<String, String>, prefix: String): RemoteDeviceState {
        return RemoteDeviceState(
            deviceId = required(map, "$prefix.deviceId"),
            deviceName = required(map, "$prefix.deviceName"),
            role = LinkRole.valueOf(required(map, "$prefix.role")),
            isConnected = map["$prefix.isConnected"]?.toBooleanStrictOrNull() ?: true,
            isReady = map["$prefix.isReady"]?.toBooleanStrictOrNull() ?: false,
            isBalancing = map["$prefix.isBalancing"]?.toBooleanStrictOrNull() ?: false,
            isRecording = map["$prefix.isRecording"]?.toBooleanStrictOrNull() ?: false,
            latencyMs = map["$prefix.latencyMs"]?.toIntOrNull(),
            peakDb = map["$prefix.peakDb"]?.toFloatOrNull(),
            rmsDb = map["$prefix.rmsDb"]?.toFloatOrNull(),
            seconds = map["$prefix.seconds"]?.toIntOrNull(),
            lastTakeId = map["$prefix.lastTakeId"].takeUnless { it.isNullOrBlank() },
            statusText = map["$prefix.statusText"].takeUnless { it.isNullOrBlank() },
            errorText = map["$prefix.errorText"].takeUnless { it.isNullOrBlank() },
            lastSeenEpochMs = map["$prefix.lastSeenEpochMs"]?.toLongOrNull() ?: System.currentTimeMillis()
        )
    }

    private fun required(map: Map<String, String>, key: String): String =
        map[key] ?: error("Missing required field: $key")

    private fun escape(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun unescape(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
