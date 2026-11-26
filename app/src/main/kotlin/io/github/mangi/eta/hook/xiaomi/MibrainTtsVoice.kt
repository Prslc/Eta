package io.github.mangi.eta.hook.xiaomi

/**
 * 超级小爱官方播报音色。
 *
 * vendor/speaker 与 com.miui.voiceassist 的 TTSVendorFragment 配置表一致：两者共用同一套
 * AIVS 语音协议，只是 MiBrain 旧引擎在合成请求里从不填写这两个字段。
 */
internal enum class MibrainTtsVoice(
    val prefValue: String,
    val vendor: String,
    val speaker: String,
) {
    OFF("", "", ""),
    MITANG("Default", "Default", ""),
    MUMIAN("XiaoMi_female", "XiaoMi_female", ""),
    XINGHE("XiaoMi_male", "XiaoMi_male", ""),
    MOLI("XiaoMi", "XiaoMi", ""),
    PAOFU("XiaoMi_xinran", "XiaoMi", "xinran"),
    QINGCONG("XiaoMi_M88", "XiaoMi_M88", "");

    companion object {
        private val byPrefValue: Map<String, MibrainTtsVoice> =
            entries.associateBy(MibrainTtsVoice::prefValue)

        fun fromPref(value: String?): MibrainTtsVoice = byPrefValue[value] ?: OFF
    }
}
