package io.github.mangi.eta.hook.xiaomi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.hook.support.HookInstallation
import io.github.mangi.eta.hook.support.HookRegistrar
import io.github.mangi.eta.hook.support.HookSupport
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 让 MiBrain 的系统识别服务真正返回 partial results。
 *
 * MiBrain 把 AIVS 的 `asr.enable_partial_result` 关掉，而且 `EngineCapabilityUtils` 不区分
 * `RecognizeResult.isFinal`，每个中间结果都会走 `Callback.results()`。这里在 MiBrain 进程内：
 * 1. 放宽引擎配置（partial、录音上限、接收超时）；
 * 2. 截住 `isFinal=false` 的结果，改走 `Callback.partialResults()`；
 * 3. 从 `AsrService.onStartListening` 取得当前会话的 `Callback`。
 * 仅当 LSPosed 作用域勾选小爱语音引擎时生效。
 */
internal object MibrainAsrHooks {
    private const val APP_CONFIG_CLASS = "com.xiaomi.mibrain.speech.a"
    private const val ASR_SERVICE_CLASS = "com.xiaomi.mibrain.speech.asr.AsrService"
    private const val CAPABILITY_CLASS = "com.xiaomi.mibrain.speech.utils.h\$d"
    private const val INSTRUCTION_CLASS = "com.xiaomi.ai.api.common.Instruction"
    private const val RECOGNIZE_RESULT_CLASS = "com.xiaomi.ai.api.SpeechRecognizer\$RecognizeResult"
    private const val RECOGNIZE_ITEM_CLASS = "com.xiaomi.ai.api.SpeechRecognizer\$RecognizeResultItem"

    private const val KEY_ENABLE_PARTIAL = "asr.enable_partial_result"
    private const val KEY_MAX_AUDIO_SECONDS = "asr.max_audio_seconds"
    private const val KEY_RECV_TIMEOUT = "asr.recv_timeout"
    private const val MAX_AUDIO_SECONDS = 30
    private const val RECV_TIMEOUT_SECONDS = 20

    /** 当前识别会话的远端回调，由 onStartListening 注入。 */
    @Volatile
    private var activeCallback: RecognitionService.Callback? = null

    private val configLogged = AtomicBoolean(false)
    private val partialLogged = AtomicBoolean(false)

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "MibrainAsr")
        return hooks.install {
            installConfigHook(module, classLoader)
            installCallbackHook(module, classLoader)
            installPartialRoutingHook(module, classLoader)
        }
    }

    /** 放宽 AIVS 引擎的 ASR 配置；引擎在服务首次绑定时创建，早于识别请求。 */
    private fun HookRegistrar.installConfigHook(
        module: XposedModule,
        classLoader: ClassLoader,
    ) {
        val configClass = HookSupport.findClassOrNull(classLoader, APP_CONFIG_CLASS)
        val getDefaultConfig = configClass?.let {
            HookSupport.findMethod(it, "getDefaultConfig", Context::class.java)
        }
        if (getDefaultConfig == null) {
            missing(
                "mibrain.asr-config",
                "SpeechConfig.getDefaultConfig",
                "小爱语音识别: 未找到引擎配置入口，partial 可能不会下发",
            )
            return
        }
        HookSupport.deoptimize(module, logger, getDefaultConfig, "SpeechConfig.getDefaultConfig")
        intercept(
            "mibrain.asr-config",
            getDefaultConfig,
            "SpeechConfig.getDefaultConfig",
        ) { chain ->
            val config = chain.proceed()
            if (config != null) {
                try {
                    val type = config.javaClass
                    type.getMethod("putBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                        .invoke(config, KEY_ENABLE_PARTIAL, true)
                    type.getMethod("putInt", String::class.java, Int::class.javaPrimitiveType)
                        .invoke(config, KEY_MAX_AUDIO_SECONDS, MAX_AUDIO_SECONDS)
                    type.getMethod("putInt", String::class.java, Int::class.javaPrimitiveType)
                        .invoke(config, KEY_RECV_TIMEOUT, RECV_TIMEOUT_SECONDS)
                    if (configLogged.compareAndSet(false, true)) {
                        logger.info(
                            "小爱语音识别: 已开启 partial，录音上限 ${MAX_AUDIO_SECONDS}s，接收超时 ${RECV_TIMEOUT_SECONDS}s",
                        )
                    }
                } catch (exception: Exception) {
                    logger.warnThrottled("mibrain_asr_config_failed") {
                        "小爱语音识别: 修改引擎配置失败 type=${exception.safeLogType()}"
                    }
                }
            }
            config
        }
    }

    /** 取得当前会话的 RecognitionService.Callback，供 partial 转发使用。 */
    private fun HookRegistrar.installCallbackHook(
        module: XposedModule,
        classLoader: ClassLoader,
    ) {
        val serviceClass = HookSupport.findClassOrNull(classLoader, ASR_SERVICE_CLASS)
        val callbackClass = RecognitionService.Callback::class.java
        val onStartListening = serviceClass?.let {
            HookSupport.findMethod(it, "onStartListening", Intent::class.java, callbackClass)
        }
        if (onStartListening == null) {
            missing(
                "mibrain.asr-callback",
                "AsrService.onStartListening",
                "小爱语音识别: 未找到识别会话入口，partial 无法转发",
            )
            return
        }
        HookSupport.deoptimize(module, logger, onStartListening, "AsrService.onStartListening")
        intercept(
            "mibrain.asr-callback",
            onStartListening,
            "AsrService.onStartListening",
        ) { chain ->
            activeCallback = chain.getArg(1) as? RecognitionService.Callback
            chain.proceed()
        }

        val clearTargets = listOf(
            "onStopListening" to "mibrain.asr-callback-stop",
            "onCancel" to "mibrain.asr-callback-cancel",
        )
        clearTargets.forEach { (methodName, id) ->
            val method = HookSupport.findMethod(serviceClass, methodName, callbackClass)
            if (method == null) {
                skipped(id, "AsrService.$methodName", "小爱语音识别: 跳过会话清理入口")
                return@forEach
            }
            HookSupport.deoptimize(module, logger, method, "AsrService.$methodName")
            intercept(id, method, "AsrService.$methodName") { chain ->
                val callback = chain.getArg(0) as? RecognitionService.Callback
                if (callback != null && callback === activeCallback) activeCallback = null
                chain.proceed()
            }
        }
    }

    /** 按 isFinal 分流：中间结果走 partialResults，最终结果保留原路径。 */
    private fun HookRegistrar.installPartialRoutingHook(
        module: XposedModule,
        classLoader: ClassLoader,
    ) {
        val instructionClass = HookSupport.findClassOrNull(classLoader, INSTRUCTION_CLASS)
        val capabilityClass = HookSupport.findClassOrNull(classLoader, CAPABILITY_CLASS)
        val recognizeResultClass = HookSupport.findClassOrNull(classLoader, RECOGNIZE_RESULT_CLASS)
        val recognizeItemClass = HookSupport.findClassOrNull(classLoader, RECOGNIZE_ITEM_CLASS)
        val process = if (instructionClass != null && capabilityClass != null) {
            HookSupport.findMethod(capabilityClass, "process", instructionClass)
        } else {
            null
        }
        val getPayload = instructionClass?.let { HookSupport.findMethod(it, "getPayload") }
        val isFinal = recognizeResultClass?.let { HookSupport.findMethod(it, "isFinal") }
        val getResults = recognizeResultClass?.let { HookSupport.findMethod(it, "getResults") }
        val getText = recognizeItemClass?.let { HookSupport.findMethod(it, "getText") }
        if (process == null || getPayload == null || recognizeResultClass == null ||
            isFinal == null || getResults == null || getText == null
        ) {
            missing(
                "mibrain.asr-partial",
                "EngineCapabilityUtils.process",
                "小爱语音识别: 未找到 partial 分流入口，partial 仍按最终结果处理",
            )
            return
        }
        HookSupport.deoptimize(module, logger, process, "EngineCapabilityUtils.process")
        intercept(
            "mibrain.asr-partial",
            process,
            "EngineCapabilityUtils.process",
        ) { chain ->
            val instruction = chain.getArg(0)
            val payload = instruction?.let {
                runCatching { getPayload.invoke(it) }.getOrNull()
            }
            if (payload != null && recognizeResultClass.isInstance(payload)) {
                val final = runCatching { isFinal.invoke(payload) as? Boolean }.getOrNull()
                if (final == false) {
                    val text = runCatching {
                        (getResults.invoke(payload) as? List<*>)
                            ?.firstOrNull()
                            ?.let { getText.invoke(it) as? String }
                    }.getOrNull()
                    if (!text.isNullOrBlank()) {
                        forwardPartial(text, logger)
                        return@intercept true
                    }
                }
            }
            chain.proceed()
        }
    }

    private fun forwardPartial(text: String, logger: ModuleLogger) {
        val callback = activeCallback ?: return
        val results = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        }
        try {
            callback.partialResults(results)
            if (partialLogged.compareAndSet(false, true)) {
                logger.info("小爱语音识别: 已开始转发 partial 结果")
            }
        } catch (exception: Exception) {
            logger.warnThrottled("mibrain_asr_partial_failed") {
                "小爱语音识别: partial 转发失败 type=${exception.safeLogType()}"
            }
        }
    }
}
