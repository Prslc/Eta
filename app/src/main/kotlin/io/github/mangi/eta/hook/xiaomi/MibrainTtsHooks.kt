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
 * MiBrain 的 AIVS 请求本身支持 `Settings.TtsConfig` 的 vendor/speaker，
 * 只是旧引擎从不设置；在 MiBrain 进程内补齐这两个字段，标准 TextToSpeech 调用方无需改动。
 * 仅当用户在 Eta 设置里选了音色、且 LSPosed 作用域勾选小爱语音引擎时生效。
 */
internal object MibrainTtsHooks {
    private const val SYNTHESIZE_CLASS = "com.xiaomi.ai.api.SpeechSynthesizer\$Synthesize"
    private const val TTS_CONFIG_CLASS = "com.xiaomi.ai.api.Settings\$TtsConfig"

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
                    "小爱语音引擎: 未找到云端合成音色入口，跳过音色 Hook",
                )
                return@install
            }
            HookSupport.deoptimize(
                module,
                hooks.logger,
                setTts,
                "Synthesize.setTts",
            )
            intercept(
                "mibrain.tts-voice",
                setTts,
                "Synthesize.setTts",
            ) { chain ->
                val voice = MibrainTtsVoice.fromPref(Prefs.getString(Prefs.Keys.TTS_VOICE))
                if (voice != appliedVoice) {
                    appliedVoice = voice
                    hooks.logger.info(
                        if (voice == MibrainTtsVoice.OFF) {
                            "小爱语音引擎: 已恢复系统默认音色"
                        } else {
                            "小爱语音引擎: 已切换播报音色 voice=${voice.name}, vendor=${voice.vendor}"
                        },
                    )
                }
                if (voice == MibrainTtsVoice.OFF) return@intercept chain.proceed()
                val config = chain.getArg(0)
                if (config != null) {
                    try {
                        setVendor.invoke(config, voice.vendor)
                        if (voice.speaker.isNotEmpty()) setSpeaker.invoke(config, voice.speaker)
                    } catch (exception: Exception) {
                        hooks.logger.warnThrottled("mibrain_tts_voice_apply_failed") {
                            "小爱语音引擎: 应用音色失败 voice=${voice.name}, " +
                                "type=${exception.safeLogType()}"
                        }
                    }
                }
                chain.proceed()
            }
        }
    }
}
