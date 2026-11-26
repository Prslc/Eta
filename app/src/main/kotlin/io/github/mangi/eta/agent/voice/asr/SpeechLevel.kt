package io.github.mangi.eta.agent.voice.asr

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 把 16-bit 单声道 PCM 的 RMS 映射成 0..1 的界面音量。
 *
 * 小米识别服务从不回调 `onRmsChanged`，但会把原始 PCM 通过 `onBufferReceived` 送过来，
 * 因此 DEVICE 路径自己算电平。
 */
internal object SpeechLevel {
    private const val FLOOR_DB = -60.0
    private const val CEILING_DB = -10.0

    fun fromPcm16(buffer: ByteArray): Float? {
        if (buffer.size < 2) return null
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < buffer.size) {
            val low = buffer[index].toInt() and 0xFF
            val high = buffer[index + 1].toInt() and 0xFF
            val sample = ((high shl 8) or low).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            index += 2
        }
        if (count == 0) return null
        val rms = sqrt(sum / count)
        if (rms < 1.0) return 0f
        val db = 20.0 * log10(rms / 32768.0)
        return ((db - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).toFloat().coerceIn(0f, 1f)
    }
}
