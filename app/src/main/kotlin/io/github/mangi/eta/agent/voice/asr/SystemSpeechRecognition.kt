package io.github.mangi.eta.agent.voice.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.audio.SpeechErrorCode
import io.github.mangi.eta.agent.voice.audio.SpeechFailure
import io.github.mangi.eta.agent.voice.audio.SpeechRecorder
import io.github.mangi.eta.core.AppStrings
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val LISTEN_TIMEOUT_MS = 90_000L

/**
 * 把识别交给系统 `RecognitionService`（小米设备上为 MiBrain，即小爱识别引擎）。
 *
 * 与云端 ASR 不同，音频由系统服务自己录制，客户端只接收识别回调，因此这里不使用
 * [SpeechRecorder] 的 PCM 通道；[finish] 只请求服务尽快收尾，实际断句仍由服务决定。
 */
internal class SystemSpeechRecognition(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onPreview: (String) -> Unit,
    private val onEndOfSpeech: () -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var result: CompletableDeferred<String>? = null
    @Volatile private var finishRequested = false
    @Volatile private var closed = false

    suspend fun listen(language: String, endpointSilenceMs: Int): String =
        withContext(Dispatchers.Main.immediate) {
            val engine = SystemSpeechRecognizer.create(context) ?: throw SpeechFailure(
                SpeechErrorCode.CONFIGURATION,
                AppStrings.get(
                    R.string.voice_i18n_system_asr_unavailable,
                    "系统语音识别不可用，请检查系统语音识别服务",
                ),
            )
            recognizer = engine
            val deferred = CompletableDeferred<String>()
            result = deferred
            engine.setRecognitionListener(listener)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    language.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag(),
                )
                if (endpointSilenceMs > 0) {
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        endpointSilenceMs.toLong(),
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        endpointSilenceMs.toLong(),
                    )
                }
            }
            try {
                engine.startListening(intent)
                if (finishRequested) runCatching { engine.stopListening() }
                withTimeout(LISTEN_TIMEOUT_MS) { deferred.await() }
            } catch (error: TimeoutCancellationException) {
                runCatching { engine.cancel() }
                throw SpeechFailure(
                    SpeechErrorCode.TIMEOUT,
                    AppStrings.get(R.string.voice_i18n_system_asr_timeout, "系统语音识别超时"),
                )
            } catch (error: CancellationException) {
                runCatching { engine.cancel() }
                throw error
            } finally {
                closed = true
                runCatching { engine.destroy() }
                if (recognizer === engine) recognizer = null
                if (result === deferred) result = null
            }
        }

    fun finish() {
        finishRequested = true
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        closed = true
        runCatching { recognizer?.cancel() }
        result?.cancel()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!closed) onReady()
        }

        override fun onBeginningOfSpeech() {
            if (!closed) onReady()
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (!closed) onLevel((rmsdB / 10f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!closed) onEndOfSpeech()
        }

        override fun onError(error: Int) {
            if (!closed) result?.completeExceptionally(error.toSpeechFailure())
        }

        override fun onResults(results: Bundle?) {
            if (!closed) result?.complete(text(results).orEmpty())
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!closed) text(partialResults)?.takeIf { it.isNotBlank() }?.let(onPreview)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun text(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()

    private fun Int.toSpeechFailure(): SpeechFailure = when (this) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechFailure(
            SpeechErrorCode.NETWORK,
            AppStrings.get(R.string.voice_i18n_network_unavailable, "无法连接语音服务，请检查网络"),
        )
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure(
            SpeechErrorCode.NO_SPEECH,
            AppStrings.get(R.string.voice_i18n_no_speech_recognized, "没有识别到语音，请重试"),
        )
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure(
            SpeechErrorCode.PERMISSION,
            AppStrings.get(R.string.voice_i18n_mic_permission_required, "请允许 Eta 使用麦克风"),
        )
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechFailure(
            SpeechErrorCode.AUDIO,
            AppStrings.get(R.string.voice_i18n_system_asr_busy, "系统语音识别正忙，请稍后重试"),
        )
        SpeechRecognizer.ERROR_AUDIO -> SpeechFailure(
            SpeechErrorCode.AUDIO,
            AppStrings.get(R.string.voice_i18n_system_asr_failed, "系统语音识别失败"),
        )
        SpeechRecognizer.ERROR_SERVER -> SpeechFailure(
            SpeechErrorCode.SERVER,
            AppStrings.get(R.string.voice_i18n_system_asr_failed, "系统语音识别失败"),
        )
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechFailure(
            SpeechErrorCode.CONFIGURATION,
            AppStrings.get(
                R.string.voice_i18n_system_asr_language_unsupported,
                "系统语音识别不支持当前语言",
            ),
        )
        else -> SpeechFailure(
            SpeechErrorCode.PROTOCOL,
            AppStrings.get(R.string.voice_i18n_system_asr_failed, "系统语音识别失败"),
        )
    }
}
