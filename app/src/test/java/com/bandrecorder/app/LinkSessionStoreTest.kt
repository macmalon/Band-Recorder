package com.bandrecorder.app

import com.bandrecorder.core.network.LinkRole
import com.bandrecorder.core.network.RemoteDeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSessionStoreTest {
    @Test
    fun `host session seeds self device`() {
        val state = LinkSessionStore.hostSession(
            sessionId = "session-a",
            selfDeviceId = "self-1",
            selfDeviceName = "Master",
            port = 18777
        )

        assertEquals(LinkMode.HOST, state.mode)
        assertEquals(1, state.devices.size)
        assertTrue(state.devices.first().isSelf)
        assertEquals(LinkRole.MASTER, state.devices.first().role)
    }

    @Test
    fun `upsert device replaces existing device state`() {
        val initial = LinkSessionStore.hostSession(
            sessionId = "session-a",
            selfDeviceId = "self-1",
            selfDeviceName = "Master",
            port = 18777
        )
        val withClient = LinkSessionStore.upsertDevice(
            initial,
            RemoteDeviceState(
                deviceId = "client-1",
                deviceName = "Client",
                role = LinkRole.CLIENT,
                isReady = false
            )
        )
        val updated = LinkSessionStore.upsertDevice(
            withClient,
            RemoteDeviceState(
                deviceId = "client-1",
                deviceName = "Client",
                role = LinkRole.CLIENT,
                isReady = true,
                isRecording = true,
                statusText = "REC"
            )
        )

        val client = updated.devices.first { it.deviceId == "client-1" }
        assertTrue(client.isReady)
        assertTrue(client.isRecording)
        assertEquals("REC", client.statusText)
    }
}
