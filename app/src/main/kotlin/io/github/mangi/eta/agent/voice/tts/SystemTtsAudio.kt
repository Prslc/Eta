package io.github.mangi.eta.agent.voice.tts

import java.io.File

/**
 * 系统 TTS 引擎（小米设备上为 MiBrain）通过 `synthesizeToFile` 输出 WAV；
 * 播放管线固定要求 24 kHz、单声道、PCM16，这里负责容器解析、声道下混和重采样。
 */
internal object SystemTtsAudio {
    private const val PLAYBACK_RATE = 24_000

    fun read(file: File): ByteArray = decodeWav(file.readBytes())

    fun decodeWav(bytes: ByteArray): ByteArray {
        val format = parse(bytes)
        val frames = format.samples.size / format.channels
        if (frames == 0) return ByteArray(0)
        val mono = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until format.channels) {
                sum += format.samples[frame * format.channels + channel]
            }
            mono[frame] = (sum / format.channels).toShort()
        }
        val pcm = if (format.sampleRate == PLAYBACK_RATE) mono else resample(mono, format.sampleRate)
        return toLittleEndian(pcm)
    }

    private class Format(val sampleRate: Int, val channels: Int, val samples: ShortArray)

    private fun parse(bytes: ByteArray): Format {
        require(bytes.size >= 12) { "WAV is too short" }
        require(ascii(bytes, 0) == "RIFF" && ascii(bytes, 8) == "WAVE") { "Not a WAV container" }
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0
        var formatSeen = false
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset)
            val size = leU32(bytes, offset + 4)
            val body = offset + 8
            if (id == "fmt ") {
                require(size >= 16 && body + 16 <= bytes.size) { "Invalid fmt chunk" }
                val encoding = leU16(bytes, body)
                require(encoding == 1 || encoding == 0xFFFE) { "Unsupported WAV encoding" }
                channels = leU16(bytes, body + 2)
                sampleRate = leU32(bytes, body + 4).toInt()
                bits = leU16(bytes, body + 14)
                require(channels in 1..2) { "Unsupported channel count" }
                require(sampleRate in 8_000..96_000) { "Unsupported sample rate" }
                require(bits == 8 || bits == 16 || bits == 24 || bits == 32) { "Unsupported sample size" }
                formatSeen = true
            } else if (id == "data") {
                require(formatSeen) { "WAV data appears before fmt" }
                dataOffset = body
                dataSize = minOf(size, (bytes.size - body).toLong()).toInt()
                break
            }
            val padded = size + (size and 1L)
            if (body + padded > bytes.size) break
            offset = (body + padded).toInt()
        }
        require(dataOffset >= 0 && dataSize > 0) { "WAV has no audio data" }
        val bytesPerSample = bits / 8
        val stride = channels * bytesPerSample
        require(stride > 0) { "Invalid WAV frame size" }
        val frames = dataSize / stride
        val samples = ShortArray(frames * channels)
        var position = dataOffset
        for (index in samples.indices) {
            samples[index] = readSample(bytes, position, bits)
            position += bytesPerSample
        }
        return Format(sampleRate, channels, samples)
    }

    private fun readSample(bytes: ByteArray, offset: Int, bits: Int): Short = when (bits) {
        8 -> (((bytes[offset].toInt() and 0xff) - 128) shl 8).toShort()
        16 -> leU16(bytes, offset).toShort()
        24 -> {
            val value = (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16)
            val signed = if (value and 0x800000 != 0) value or -0x1000000 else value
            (signed shr 8).toShort()
        }
        32 -> {
            val value = (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
            (value shr 16).toShort()
        }
        else -> 0
    }

    private fun resample(input: ShortArray, from: Int): ShortArray {
        val outSize = maxOf(1, (input.size.toLong() * PLAYBACK_RATE / from).toInt())
        val output = ShortArray(outSize)
        val step = from.toDouble() / PLAYBACK_RATE
        for (index in output.indices) {
            val position = index * step
            val left = minOf(position.toInt(), input.lastIndex)
            val right = minOf(left + 1, input.lastIndex)
            val fraction = position - left
            output[index] = (input[left] + (input[right] - input[left]) * fraction).toInt().toShort()
        }
        return output
    }

    private fun toLittleEndian(samples: ShortArray): ByteArray {
        val output = ByteArray(samples.size * 2)
        for (index in samples.indices) {
            val value = samples[index].toInt()
            output[index * 2] = (value and 0xff).toByte()
            output[index * 2 + 1] = (value shr 8 and 0xff).toByte()
        }
        return output
    }

    private fun ascii(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun leU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun leU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
}
