package io.github.mangi.eta.hook.xiaomi

import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.hook.support.HookInstallation
import io.github.mangi.eta.hook.support.HookRegistrar
import io.github.mangi.eta.hook.support.HookSupport

/**
 * 把小爱语音引擎的云端合成切到超级小爱同款音色。
 *
 * 超级小爱在合成事件里写 `Settings.TtsConfig` 的 vendor/speaker，同时在请求上下文
 * `General.RequestState` 里重复一份 ttsVendor/speaker；MiBrain 旧引擎两处都不写。
 * 这里两处都补齐，标准 TextToSpeech 调用方无需改动。
 * 仅当用户在语音设置里选了音色、且 LSPosed 作用域勾选小爱语音引擎时生效。
 */
internal object MibrainTtsHooks {
    private const val SYNTHESIZE_CLASS = "com.xiaomi.ai.api.SpeechSynthesizer\$Synthesize"
    private const val TTS_CONFIG_CLASS = "com.xiaomi.ai.api.Settings\$TtsConfig"
    private const val EVENT_CLASS = "com.xiaomi.ai.api.common.Event"
    private const val CONTEXT_CLASS = "com.xiaomi.ai.api.common.Context"
    private const val REQUEST_STATE_CLASS = "com.xiaomi.ai.api.General\$RequestState"

    /** 上一次实际生效的音色，仅用于在切换时打一条诊断日志，避免每次合成刷屏。 */
    @Volatile
    private var appliedVoice: MibrainTtsVoice? = null

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "MibrainTts")
        return hooks.install {
            installSynthesizeVoiceHook(module, classLoader)
            installRequestStateVoiceHook(module, classLoader)
        }
    }

    private fun currentVoice(logger: ModuleLogger): MibrainTtsVoice {
        val voice = MibrainTtsVoice.fromPref(Prefs.getString(Prefs.Keys.TTS_VOICE))
        if (voice != appliedVoice) {
            appliedVoice = voice
            logger.info(
                if (voice == MibrainTtsVoice.OFF) {
                    "小爱语音引擎: 已恢复系统默认音色"
                } else {
                    "小爱语音引擎: 已切换播报音色 voice=${voice.name}, vendor=${voice.vendor}"
                },
            )
        }
        return voice
    }

    /** `Synthesize.setTts(TtsConfig)`：合成事件自身的音色字段。 */
    private fun HookRegistrar.installSynthesizeVoiceHook(
        module: XposedModule,
        classLoader: ClassLoader,
    ) {
        val synthesizeClass = HookSupport.findClassOrNull(classLoader, SYNTHESIZE_CLASS)
        val ttsConfigClass = HookSupport.findClassOrNull(classLoader, TTS_CONFIG_CLASS)
        val setTts = if (synthesizeClass != null && ttsConfigClass != null) {
            HookSupport.findMethod(synthesizeClass, "setTts", ttsConfigClass)
        } else {
            null
        }
        val setVendor = ttsConfigClass?.let {
            HookSupport.findMethod(it, "setVendor", String::class.java)
        }
        val setSpeaker = ttsConfigClass?.let {
            HookSupport.findMethod(it, "setSpeaker", String::class.java)
        }
        if (setTts == null || setVendor == null || setSpeaker == null) {
            missing(
                "mibrain.tts-voice",
                "Synthesize.setTts",
                "小爱语音引擎: 未找到合成事件音色入口，跳过 TtsConfig Hook",
            )
            return
        }
        HookSupport.deoptimize(module, logger, setTts, "Synthesize.setTts")
        intercept(
            "mibrain.tts-voice",
            setTts,
            "Synthesize.setTts",
        ) { chain ->
            val voice = currentVoice(logger)
            if (voice == MibrainTtsVoice.OFF) return@intercept chain.proceed()
            val config = chain.getArg(0)
            if (config != null) {
                try {
                    setVendor.invoke(config, voice.vendor)
                    if (voice.speaker.isNotEmpty()) setSpeaker.invoke(config, voice.speaker)
                } catch (exception: Exception) {
                    logger.warnThrottled("mibrain_tts_voice_apply_failed") {
                        "小爱语音引擎: 应用 TtsConfig 音色失败 voice=${voice.name}, " +
                            "type=${exception.safeLogType()}"
                    }
                }
            }
            chain.proceed()
        }
    }

    /** `Event.addContext(Context)`：在请求上下文里重复一份音色，和超级小爱保持一致。 */
    private fun HookRegistrar.installRequestStateVoiceHook(
        module: XposedModule,
        classLoader: ClassLoader,
    ) {
        val eventClass = HookSupport.findClassOrNull(classLoader, EVENT_CLASS)
        val contextClass = HookSupport.findClassOrNull(classLoader, CONTEXT_CLASS)
        val requestStateClass = HookSupport.findClassOrNull(classLoader, REQUEST_STATE_CLASS)
        val addContext = if (eventClass != null && contextClass != null) {
            HookSupport.findMethod(eventClass, "addContext", contextClass)
        } else {
            null
        }
        val getPayload = contextClass?.let { HookSupport.findMethod(it, "getPayload") }
        val setTtsVendor = requestStateClass?.let {
            HookSupport.findMethod(it, "setTtsVendor", String::class.java)
        }
        val setSpeaker = requestStateClass?.let {
            HookSupport.findMethod(it, "setSpeaker", String::class.java)
        }
        if (addContext == null || getPayload == null || requestStateClass == null || setTtsVendor == null) {
            missing(
                "mibrain.tts-voice-context",
                "Event.addContext",
                "小爱语音引擎: 未找到请求上下文音色入口，跳过 RequestState Hook",
            )
            return
        }
        HookSupport.deoptimize(module, logger, addContext, "Event.addContext")
        intercept(
            "mibrain.tts-voice-context",
            addContext,
            "Event.addContext",
        ) { chain ->
            val voice = currentVoice(logger)
            if (voice != MibrainTtsVoice.OFF) {
                val context = chain.getArg(0)
                val payload = context?.let {
                    runCatching { getPayload.invoke(it) }.getOrNull()
                }
                if (payload != null && requestStateClass.isInstance(payload)) {
                    try {
                        setTtsVendor.invoke(payload, voice.vendor)
                        if (voice.speaker.isNotEmpty()) setSpeaker?.invoke(payload, voice.speaker)
                    } catch (exception: Exception) {
                        logger.warnThrottled("mibrain_tts_voice_context_failed") {
                            "小爱语音引擎: 应用 RequestState 音色失败 voice=${voice.name}, " +
                                "type=${exception.safeLogType()}"
                        }
                    }
                }
            }
            chain.proceed()
        }
    }
}
