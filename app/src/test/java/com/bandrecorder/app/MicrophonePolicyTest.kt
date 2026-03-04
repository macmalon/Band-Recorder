package com.bandrecorder.app

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophonePolicyTest {
    @Test
    fun `type mapping is explicit for key cases`() {
        assertEquals("Built-in Mic", MicrophonePolicy.typeLabel(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals("Telephony Input", MicrophonePolicy.typeLabel(AudioDeviceInfo.TYPE_TELEPHONY))
        assertEquals("Remote Submix", MicrophonePolicy.typeLabel(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
    }

    @Test
    fun `recommendation policy marks telephony and submix as non recommended`() {
        assertTrue(MicrophonePolicy.isRecommended(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertFalse(MicrophonePolicy.isRecommended(AudioDeviceInfo.TYPE_TELEPHONY))
        assertFalse(MicrophonePolicy.isRecommended(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
    }

    @Test
    fun `auto strategy prefers built-in then recommended then fallback`() {
        val nonRecommended = mic(18, AudioDeviceInfo.TYPE_TELEPHONY, false)
        val builtIn = mic(15, AudioDeviceInfo.TYPE_BUILTIN_MIC, true)
        val usb = mic(22, AudioDeviceInfo.TYPE_USB_HEADSET, true)

        assertEquals(builtIn.id, MicrophonePolicy.chooseAutoMicrophone(listOf(nonRecommended, builtIn, usb))?.id)
        assertEquals(usb.id, MicrophonePolicy.chooseAutoMicrophone(listOf(nonRecommended, usb))?.id)
        assertEquals(nonRecommended.id, MicrophonePolicy.chooseAutoMicrophone(listOf(nonRecommended))?.id)
    }

    @Test
    fun `warnings are present for non recommended special inputs`() {
        assertNotNull(MicrophonePolicy.warningForType(AudioDeviceInfo.TYPE_TELEPHONY))
        assertNotNull(MicrophonePolicy.warningForType(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
    }

    private fun mic(id: Int, type: Int, recommended: Boolean): MicrophoneOption {
        return MicrophoneOption(
            id = id,
            displayName = "Mic $id",
            typeCode = type,
            typeLabel = "Type",
            recommended = recommended,
            warning = null,
            description = null,
            locationLabel = null,
            directionalityLabel = null,
            position = null,
            orientation = null
        )
    }
}

