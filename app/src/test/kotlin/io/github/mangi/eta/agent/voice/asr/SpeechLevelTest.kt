package io.github.mangi.eta.agent.voice.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechLevelTest {

    @Test
    fun silenceIsZero() {
        assertEquals(0f, SpeechLevel.fromPcm16(ByteArray(640)) ?: error("expected level"), 0.001f)
    }

    @Test
    fun tooShortBufferIsIgnored() {
        assertNull(SpeechLevel.fromPcm16(ByteArray(1)))
    }

    @Test
    fun midLevelLandsInTheMiddleOfTheRange() {
        // 1000 / 32768 ≈ -30.3 dB，按 -60..-10 dB 映射约 0.59。
        val level = SpeechLevel.fromPcm16(squareWave(320, amplitude = 1000)) ?: error("expected level")
        assertEquals(0.594f, level, 0.02f)
    }

    @Test
    fun loudSignalClampsToOne() {
        val level = SpeechLevel.fromPcm16(squareWave(320, amplitude = 30_000)) ?: error("expected level")
        assertEquals(1f, level, 0.001f)
    }

    @Test
    fun veryQuietSignalClampsToZero() {
        val level = SpeechLevel.fromPcm16(squareWave(320, amplitude = 10)) ?: error("expected level")
        assertEquals(0f, level, 0.001f)
    }

    private fun squareWave(sampleCount: Int, amplitude: Int): ByteArray {
        val bytes = ByteArray(sampleCount * 2)
        for (index in 0 until sampleCount) {
            val sample = (if (index % 2 == 0) amplitude else -amplitude).toShort().toInt()
            bytes[index * 2] = (sample and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
