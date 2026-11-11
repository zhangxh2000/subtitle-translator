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

    /** 每次网络真正可用时发出一个事件，用于补发离线期间积压的变更 */
    val onlineEvents: Flow<Unit> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isOnline()) trySend(Unit)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    trySend(Unit)
                }
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
}
