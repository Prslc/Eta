package io.github.mangi.eta.agent.voice.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.audio.SpeechErrorCode
import io.github.mangi.eta.agent.voice.audio.SpeechFailure
import io.github.mangi.eta.core.AppStrings
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val MIBRAIN_PACKAGE = "com.xiaomi.mibrain.speech"
private const val CHUNK_TIMEOUT_MS = 60_000L
private const val AUDIO_EMIT_BYTES = 16_384
private const val SYSTEM_TTS_DIR = "eta-system-tts"

internal suspend fun synthesizeSystemSpeech(
    context: Context,
    text: String,
    onAudio: (ByteArray) -> Unit,
) = SystemTtsEngine.speakAll(context, listOf(text), onAudio)

internal suspend fun streamSystemSpeech(
    context: Context,
    chunks: List<String>,
    onAudio: (ByteArray) -> Unit,
) = SystemTtsEngine.speakAll(context, chunks, onAudio)

/**
 * 进程内唯一的系统 TTS 连接。
 *
 * MiBrain 在最后一个 TTS 客户端解绑时会在 `TtsService.onDestroy()` 里释放共享的云端引擎，
 * 而同一进程内不会再重建该引擎；下一次绑定只能退回本地离线音色。因此这里必须跨播放
 * 保持连接，不能在每次朗读结束后 `shutdown()`。
 */
private object SystemTtsEngine {
    private val mutex = Mutex()
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var pending: Pair<String, CompletableDeferred<Unit>>? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) {
            pending?.takeIf { it.first == utteranceId }?.second?.complete(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            fail(utteranceId, AppStrings.get(R.string.voice_i18n_system_tts_failed, "系统语音合成失败"))
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            fail(
                utteranceId,
                AppStrings.get(
                    R.string.voice_i18n_system_tts_failed_code,
                    "系统语音合成失败（%1\$d）",
                    errorCode,
                ),
            )
        }

        private fun fail(utteranceId: String, message: String) {
            pending?.takeIf { it.first == utteranceId }?.second?.completeExceptionally(
                SpeechFailure(SpeechErrorCode.AUDIO, message),
            )
        }
    }

    suspend fun speakAll(context: Context, chunks: List<String>, onAudio: (ByteArray) -> Unit) {
        if (chunks.none { it.isNotBlank() }) return
        mutex.withLock {
            val appContext = context.applicationContext
            val engine = ensureEngine(appContext)
            val dir = File(appContext.cacheDir, SYSTEM_TTS_DIR).apply { mkdirs() }
            for (chunk in chunks) {
                if (chunk.isNotBlank()) speakChunk(engine, dir, chunk, onAudio)
            }
        }
    }

    private suspend fun ensureEngine(context: Context): TextToSpeech {
        tts?.let { return it }
        val preferred = preferredEngine(context)
        val engine = create(context, preferred)
            ?: preferred?.let { create(context, null) }
            ?: throw SpeechFailure(
                SpeechErrorCode.CONFIGURATION,
                AppStrings.get(
                    R.string.voice_i18n_system_tts_unavailable,
                    "系统语音合成不可用，请检查系统 TTS 引擎",
                ),
            )
        engine.setOnUtteranceProgressListener(listener)
        tts = engine
        return engine
    }

    private suspend fun speakChunk(
        engine: TextToSpeech,
        dir: File,
        text: String,
        onAudio: (ByteArray) -> Unit,
    ) {
        val file = try {
            File.createTempFile("chunk-", ".wav", dir)
        } catch (error: IOException) {
            throw SpeechFailure(
                SpeechErrorCode.STORAGE,
                AppStrings.get(R.string.voice_i18n_system_tts_storage, "无法创建系统语音缓存文件"),
            )
        }
        try {
            val utteranceId = UUID.randomUUID().toString()
            val done = CompletableDeferred<Unit>()
            pending = utteranceId to done
            try {
                withContext(Dispatchers.Main.immediate) {
                    prepareLanguage(engine, text)
                    val status = engine.synthesizeToFile(text, Bundle(), file, utteranceId)
                    if (status != TextToSpeech.SUCCESS) {
                        throw SpeechFailure(
                            SpeechErrorCode.AUDIO,
                            AppStrings.get(R.string.voice_i18n_system_tts_failed, "系统语音合成失败"),
                        )
                    }
                }
                try {
                    withTimeout(CHUNK_TIMEOUT_MS) { done.await() }
                } catch (error: TimeoutCancellationException) {
                    runCatching { engine.stop() }
                    throw SpeechFailure(
                        SpeechErrorCode.TIMEOUT,
                        AppStrings.get(R.string.voice_i18n_system_tts_timeout, "系统语音合成超时"),
                    )
                } catch (error: CancellationException) {
                    runCatching { engine.stop() }
                    throw error
                }
            } finally {
                if (pending?.first == utteranceId) pending = null
            }
            val pcm = withContext(Dispatchers.IO) {
                val bytes = try {
                    file.readBytes()
                } catch (error: IOException) {
                    throw SpeechFailure(
                        SpeechErrorCode.STORAGE,
                        AppStrings.get(R.string.voice_i18n_system_tts_storage, "无法创建系统语音缓存文件"),
                    )
                }
                try {
                    SystemTtsAudio.decodeWav(bytes)
                } catch (error: RuntimeException) {
                    throw SpeechFailure(
                        SpeechErrorCode.PROTOCOL,
                        AppStrings.get(
                            R.string.voice_i18n_system_tts_bad_audio,
                            "系统语音引擎返回了无法处理的音频",
                        ),
                    )
                }
            }
            withContext(Dispatchers.IO) { emit(pcm, onAudio) }
        } finally {
            file.delete()
        }
    }

    private suspend fun create(context: Context, engine: String?): TextToSpeech? =
        withContext(Dispatchers.Main.immediate) {
            val initialized = CompletableDeferred<Int>()
            val created = TextToSpeech(context, { status -> initialized.complete(status) }, engine)
            try {
                if (initialized.await() == TextToSpeech.SUCCESS) {
                    created
                } else {
                    runCatching { created.shutdown() }
                    null
                }
            } catch (error: CancellationException) {
                runCatching { created.shutdown() }
                throw error
            }
        }

    private fun preferredEngine(context: Context): String? = runCatching {
        val services = context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
        services.firstOrNull { it.serviceInfo?.packageName == MIBRAIN_PACKAGE }
            ?.serviceInfo?.packageName
    }.getOrNull()

    private fun prepareLanguage(engine: TextToSpeech, text: String) {
        val desired = if (containsHan(text)) Locale.SIMPLIFIED_CHINESE else Locale.getDefault()
        if (engine.setLanguage(desired) < 0) {
            engine.setLanguage(Locale.getDefault())
        }
    }

    private fun emit(bytes: ByteArray, onAudio: (ByteArray) -> Unit) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + AUDIO_EMIT_BYTES, bytes.size)
            onAudio(bytes.copyOfRange(offset, end))
            offset = end
        }
    }
}

private fun containsHan(text: String): Boolean = text.any { char ->
    char in '一'..'鿿' || char in '㐀'..'䶿'
}
