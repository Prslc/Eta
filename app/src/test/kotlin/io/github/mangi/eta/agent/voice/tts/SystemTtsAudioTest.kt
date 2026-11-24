package io.github.mangi.eta.agent.voice.tts

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SystemTtsAudioTest {
    @Test fun resamplesMonoWavToPlaybackRate() {
        val input = (0 until 16).map { it * 1_000 }.toIntArray()
        val output = samples(SystemTtsAudio.decodeWav(wav(16_000, 1, 16, pcm16(input))))

        assertEquals(24, output.size)
        assertEquals(0, output.first().toInt())
        assertEquals(15_000, output.last().toInt())
        // 16 kHz -> 24 kHz 线性插值：第 1 个输出对应输入位置 2/3。
        assertEquals(666, output[1].toInt())
        assertEquals(1_333, output[2].toInt())
    }

    @Test fun downmixesStereoBeforeResampling() {
        val input = intArrayOf(1_000, 3_000, -1_000, 1_000)
        val output = samples(SystemTtsAudio.decodeWav(wav(24_000, 2, 16, pcm16(input))))

        assertArrayEquals(shortArrayOf(2_000, 0), output)
    }

    @Test fun skipsUnknownChunksBeforeData() {
        val input = intArrayOf(500, -500)
        val output = samples(
            SystemTtsAudio.decodeWav(
                wav(24_000, 1, 16, pcm16(input), extraChunk = "LIST" to byteArrayOf(1, 2, 3, 4)),
            ),
        )

        assertArrayEquals(shortArrayOf(500, -500), output)
    }

    @Test fun rejectsNonPcmWav() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemTtsAudio.decodeWav(wav(24_000, 1, 16, pcm16(intArrayOf(1)), encoding = 3))
        }
    }

    @Test fun rejectsIncompleteContainer() {
        assertThrows(IllegalArgumentException::class.java) {
            SystemTtsAudio.decodeWav(byteArrayOf('R'.code.toByte(), 'I'.code.toByte()))
        }
    }

    private fun samples(bytes: ByteArray): ShortArray {
        val output = ShortArray(bytes.size / 2)
        for (index in output.indices) {
            output[index] = (
                (bytes[index * 2].toInt() and 0xff) or
                    ((bytes[index * 2 + 1].toInt() and 0xff) shl 8)
                ).toShort()
        }
        return output
    }

    private fun pcm16(values: IntArray): ByteArray {
        val output = ByteArray(values.size * 2)
        for (index in values.indices) {
            output[index * 2] = (values[index] and 0xff).toByte()
            output[index * 2 + 1] = (values[index] shr 8 and 0xff).toByte()
        }
        return output
    }

    private fun wav(
        sampleRate: Int,
        channels: Int,
        bits: Int,
        pcm: ByteArray,
        extraChunk: Pair<String, ByteArray>? = null,
        encoding: Int = 1,
    ): ByteArray {
        val extra = extraChunk?.let { (id, payload) ->
            val padded = payload.size + (payload.size and 1)
            id.toByteArray(Charsets.US_ASCII) + le32(padded) + payload + ByteArray(padded - payload.size)
        } ?: ByteArray(0)
        val blockAlign = channels * bits / 8
        val fmt = le16(encoding) + le16(channels) + le32(sampleRate) +
            le32(sampleRate * blockAlign) + le16(blockAlign) + le16(bits)
        val body = "WAVE".toByteArray(Charsets.US_ASCII) +
            "fmt ".toByteArray(Charsets.US_ASCII) + le32(fmt.size) + fmt +
            extra +
            "data".toByteArray(Charsets.US_ASCII) + le32(pcm.size) + pcm
        return "RIFF".toByteArray(Charsets.US_ASCII) + le32(body.size) + body
    }

    private fun le16(value: Int): ByteArray =
        byteArrayOf((value and 0xff).toByte(), (value shr 8 and 0xff).toByte())

    private fun le32(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xff).toByte(),
            (value shr 8 and 0xff).toByte(),
            (value shr 16 and 0xff).toByte(),
            (value shr 24 and 0xff).toByte(),
        )
}
