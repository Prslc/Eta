package io.github.mangi.eta.hook.xiaomi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.hook.support.HookInstallation
import io.github.mangi.eta.hook.support.HookRegistrar
import io.github.mangi.eta.hook.support.HookSupport
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 修正 MiBrain 云端语音的网络预检。
 *
 * AIVS SDK 的 `NetworkUtils.isNetworkAvailable` 只检查 `getActiveNetwork()` 的
 * `NET_CAPABILITY_INTERNET`；在 VPN/Clash 或网络切换的瞬间会误判为离线，随后云端请求
 * 直接报 40010007 并回退本地音色。这里在原检查为 false 时用系统层面再确认一次，
 * 只要存在可用的联网 Network 就放行，让真正的连接失败自然暴露。
 */
internal object MibrainNetworkHooks {
    private const val NETWORK_UTILS_CLASS = "com.xiaomi.ai.android.utils.NetworkUtils"

    private val fallbackLogged = AtomicBoolean(false)

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "MibrainNetwork")
        return hooks.install {
            val networkUtils = HookSupport.findClassOrNull(classLoader, NETWORK_UTILS_CLASS)
            val isNetworkAvailable = networkUtils?.let {
                HookSupport.findMethod(it, "isNetworkAvailable", Context::class.java)
            }
            if (isNetworkAvailable == null) {
                missing(
                    "mibrain.network-check",
                    "NetworkUtils.isNetworkAvailable",
                    "小爱语音引擎: 未找到网络预检入口，保持 SDK 原判断",
                )
                return@install
            }
            HookSupport.deoptimize(module, logger, isNetworkAvailable, "NetworkUtils.isNetworkAvailable")
            intercept(
                "mibrain.network-check",
                isNetworkAvailable,
                "NetworkUtils.isNetworkAvailable",
            ) { chain ->
                val original = chain.proceed() as? Boolean ?: false
                if (original) return@intercept true
                val context = chain.getArg(0) as? Context
                val fallback = context != null && hasUsableNetwork(context)
                if (fallback && fallbackLogged.compareAndSet(false, true)) {
                    logger.info("小爱语音引擎: SDK 网络预检误判为离线，已按系统网络状态放行")
                }
                fallback
            }
        }
    }

    private fun hasUsableNetwork(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            false
        } else {
            val active = manager.activeNetwork
            val activeCapabilities = active?.let(manager::getNetworkCapabilities)
            val activeHasInternet =
                activeCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            @Suppress("DEPRECATION")
            val activeInfoConnected = manager.activeNetworkInfo?.isConnected == true
            activeHasInternet || activeInfoConnected ||
                manager.allNetworks.any { network ->
                    manager.getNetworkCapabilities(network)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                }
        }
    } catch (exception: Exception) {
        false
    }
}
