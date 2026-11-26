package io.github.mangi.eta.hook.xiaomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MibrainTtsVoiceTest {

    @Test
    fun unknownOrMissingPreferenceFallsBackToOff() {
        assertEquals(MibrainTtsVoice.OFF, MibrainTtsVoice.fromPref(null))
        assertEquals(MibrainTtsVoice.OFF, MibrainTtsVoice.fromPref(""))
        assertEquals(MibrainTtsVoice.OFF, MibrainTtsVoice.fromPref("not-a-voice"))
    }

    @Test
    fun everyPreferenceValueRoundTrips() {
        MibrainTtsVoice.entries.forEach { voice ->
            assertEquals(voice, MibrainTtsVoice.fromPref(voice.prefValue))
        }
    }

    @Test
    fun officialVoicesUseSuperXiaoAiVendorTable() {
        assertEquals("Default", MibrainTtsVoice.MITANG.vendor)
        assertEquals("XiaoMi_female", MibrainTtsVoice.MUMIAN.vendor)
        assertEquals("XiaoMi_male", MibrainTtsVoice.XINGHE.vendor)
        assertEquals("XiaoMi", MibrainTtsVoice.MOLI.vendor)
        assertEquals("xinran", MibrainTtsVoice.PAOFU.speaker)
        assertEquals("XiaoMi", MibrainTtsVoice.PAOFU.vendor)
        assertEquals("XiaoMi_M88", MibrainTtsVoice.QINGCONG.vendor)
    }

    @Test
    fun selectableVoicesCarryAVendorAndDistinctPreferenceValues() {
        val selectable = MibrainTtsVoice.entries.filter { it != MibrainTtsVoice.OFF }
        assertTrue(selectable.all { it.vendor.isNotBlank() })
        assertEquals(selectable.size, selectable.map { it.prefValue }.distinct().size)
        assertTrue(selectable.all { it != MibrainTtsVoice.OFF && it.prefValue.isNotEmpty() })
        assertNotEquals(MibrainTtsVoice.OFF.vendor, MibrainTtsVoice.MUMIAN.vendor)
    }
}
