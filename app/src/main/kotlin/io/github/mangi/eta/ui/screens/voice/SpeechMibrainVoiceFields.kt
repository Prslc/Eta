package io.github.mangi.eta.ui.screens.voice

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.hook.xiaomi.MibrainTtsVoice
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference

/**
 * 系统 TTS 的音色选择。
 *
 * 小米的系统 TTS 引擎（MiBrain）只提供内置音色，超级小爱的音色靠 Eta 的 LSPosed 模块在
 * 云端合成请求里注入 vendor/speaker。这里直接写模块的 RemotePreferences；没有连接
 * LSPosed 时下拉保持禁用，避免误以为设置已生效。
 */
@Composable
internal fun SpeechMibrainVoiceField() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prefs by remember {
        mutableStateOf(Prefs.cachedRemotePreferencesForUi(EtaApp.serviceInstance))
    }
    var voice by remember(prefs) {
        mutableStateOf(MibrainTtsVoice.fromPref(prefs?.getString(Prefs.Keys.TTS_VOICE, null)))
    }

    DisposableEffect(Unit) {
        var lastService = EtaApp.serviceInstance
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                if (service === lastService && prefs != null) return
                lastService = service
                scope.launch {
                    prefs = withContext(Dispatchers.IO) { Prefs.remotePreferencesForUi(service) }
                }
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = prefs == null)
        onDispose { EtaApp.removeServiceStateListener(listener) }
    }

    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == Prefs.Keys.TTS_VOICE) {
                voice = MibrainTtsVoice.fromPref(changed.getString(key, null))
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val voices = MibrainTtsVoice.entries
    val labels = voices.map { entry ->
        stringResource(
            when (entry) {
                MibrainTtsVoice.OFF -> R.string.speech_mibrain_voice_system
                MibrainTtsVoice.MITANG -> R.string.speech_mibrain_voice_mitang
                MibrainTtsVoice.MUMIAN -> R.string.speech_mibrain_voice_mumian
                MibrainTtsVoice.XINGHE -> R.string.speech_mibrain_voice_xinghe
                MibrainTtsVoice.MOLI -> R.string.speech_mibrain_voice_moli
                MibrainTtsVoice.PAOFU -> R.string.speech_mibrain_voice_paofu
                MibrainTtsVoice.QINGCONG -> R.string.speech_mibrain_voice_qingcong
            },
        )
    }
    val onSelect: (Int) -> Unit = { index ->
        val selected = voices.getOrNull(index)
        val target = prefs
        if (selected != null && target != null) {
            val saved = runCatching {
                target.edit().putString(Prefs.Keys.TTS_VOICE, selected.prefValue).commit()
            }.getOrDefault(false)
            if (saved) {
                voice = selected
            } else {
                Toast.makeText(context, R.string.settings_write_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column {
        SpeechGroupCard(title = stringResource(R.string.speech_group_voice)) {
            GlassDropdownPreference(
                title = stringResource(R.string.speech_mibrain_voice),
                items = labels,
                selectedIndex = voices.indexOf(voice),
                backdrop = currentGlassOverlayBackdrop(),
                onSelectedIndexChange = onSelect,
                enabled = prefs != null,
            )
        }
        SpeechNote(stringResource(R.string.speech_system_voice_note))
    }
}
