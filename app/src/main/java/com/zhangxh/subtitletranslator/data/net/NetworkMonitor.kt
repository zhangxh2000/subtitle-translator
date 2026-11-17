package com.zhangxh.subtitletranslator.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** 同步器只需要一个「能否发请求」的判断，便于测试替换 */
fun interface NetworkStatus {
    fun isOnline(): Boolean
}

/**
 * 网络可达性
 *
 * 用 NET_CAPABILITY_VALIDATED 而不是单纯 hasCapability(INTERNET)：
 * 连上 Wi-Fi 但需要网页认证时，请求也发不出去，不应触发同步。
 */
class NetworkMonitor(context: Context) : NetworkStatus {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * 网络从不可用恢复到真正可用时发出一个事件，用于补发离线期间积压的变更。
     *
     * 注册回调时系统会立刻回调一次当前网络，之后每次能力变化（信号强度、
     * 验证状态等）也会回调；只在「离线 -> 在线」的边沿发事件，避免一次启动
     * 触发多次同步。
     */
    val onlineEvents: Flow<Unit> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            awaitClose { }
            return@callbackFlow
        }

        // 以当前状态为基线：注册回调时系统会立刻回报一次现有网络，
        // 那不是「恢复」，启动时的首次同步由登录态恢复触发。
        var wasOnline = isOnline()
        fun notify(online: Boolean) {
            if (online && !wasOnline) trySend(Unit)
            wasOnline = online
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                notify(isOnline())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                notify(
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED
                        )
                )
            }

            override fun onLost(network: Network) {
                wasOnline = false
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
}
