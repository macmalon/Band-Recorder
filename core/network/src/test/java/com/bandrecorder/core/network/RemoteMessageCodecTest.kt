package com.bandrecorder.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMessageCodecTest {
    @Test
    fun `encodes and decodes session snapshot`() {
        val message = RemoteMessage.SessionSnapshot(
            sessionId = "session-1",
            senderDeviceId = "master-1",
            senderDeviceName = "Master Phone",
            senderRole = LinkRole.MASTER,
            sessionClockMs = 123456789L,
            devices = listOf(
                RemoteDeviceState(
                    deviceId = "master-1",
                    deviceName = "Master Phone",
                    role = LinkRole.MASTER,
                    isConnected = true,
                    isReady = true,
                    latencyMs = 12,
                    statusText = "Ready"
                ),
                RemoteDeviceState(
                    deviceId = "client-2",
                    deviceName = "Client Phone",
                    role = LinkRole.CLIENT,
                    isConnected = true,
                    isBalancing = true,
                    peakDb = -3.5f,
                    rmsDb = -18.2f,
                    seconds = 22,
                    lastTakeId = "take-1"
                )
            ),
            sessionStatus = "Lobby"
        )

        val decoded = RemoteMessageCodec.decode(RemoteMessageCodec.encode(message)) as RemoteMessage.SessionSnapshot

        assertEquals(message.sessionId, decoded.sessionId)
        assertEquals(2, decoded.devices.size)
        assertEquals("client-2", decoded.devices[1].deviceId)
        assertEquals(-18.2f, decoded.devices[1].rmsDb)
        assertEquals("take-1", decoded.devices[1].lastTakeId)
    }

    @Test
    fun `encodes and decodes device status with text`() {
        val message = RemoteMessage.DeviceStatus(
            sessionId = "session-2",
            senderDeviceId = "client-1",
            senderDeviceName = "Pixel Studio",
            senderRole = LinkRole.CLIENT,
            device = RemoteDeviceState(
                deviceId = "client-1",
                deviceName = "Pixel Studio",
                role = LinkRole.CLIENT,
                isReady = true,
                isRecording = true,
                statusText = "REC 12s",
                errorText = null
            )
        )

        val decoded = RemoteMessageCodec.decode(RemoteMessageCodec.encode(message)) as RemoteMessage.DeviceStatus

        assertEquals("client-1", decoded.device.deviceId)
        assertTrue(decoded.device.isRecording)
        assertEquals("REC 12s", decoded.device.statusText)
    }
}
