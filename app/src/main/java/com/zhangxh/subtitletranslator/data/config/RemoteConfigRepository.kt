package com.zhangxh.subtitletranslator.data.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.zhangxh.subtitletranslator.data.remote.AuthApi
import com.zhangxh.subtitletranslator.data.remote.ClientConfigDto
import com.zhangxh.subtitletranslator.data.remote.toDomain
import com.zhangxh.subtitletranslator.domain.config.AppConfig
import com.zhangxh.subtitletranslator.domain.config.ConfigRepository
import com.zhangxh.subtitletranslator.domain.config.FeatureFlags
import com.zhangxh.subtitletranslator.domain.config.LanguagePair
import com.zhangxh.subtitletranslator.domain.config.LegalVersions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 公开配置仓库
 *
 * 配置包含注册必须的条款版本，不能写死在客户端。网络失败时使用上次缓存；
 * 配置本身不含敏感信息，放在普通 SharedPreferences。
 */
class RemoteConfigRepository(
    context: Context,
    private val api: AuthApi,
    private val gson: Gson = Gson()
) : ConfigRepository {

    private companion object {
        const val TAG = "RemoteConfig"
        const val PREFS_NAME = "remote_config"
        const val KEY_CONFIG = "cached_config"
    }

    private data class CachedConfig(
        val minimumClientVersion: String,
        val termsVersion: String,
        val privacyVersion: String,
        val features: FeatureFlags,
        val pairs: List<LanguagePair>
    )

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    @Volatile
    private var cache: AppConfig? = readCache()

    override fun cached(): AppConfig? = cache

    override suspend fun load(forceRefresh: Boolean): AppConfig? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!forceRefresh) {
                cache?.let { return@withLock it }
            }
            try {
                val response = api.config()
                val config = response.data?.toDomain()
                if (config != null) {
                    cache = config
                    writeCache(config)
                    config
                } else {
                    cache
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "读取配置失败: ${e.javaClass.simpleName}")
                cache
            }
        }
    }

    private fun readCache(): AppConfig? {
        return try {
            val json = prefs.getString(KEY_CONFIG, null) ?: return null
            gson.fromJson(json, CachedConfig::class.java)?.let {
                AppConfig(
                    minimumClientVersion = it.minimumClientVersion,
                    legal = LegalVersions(it.termsVersion, it.privacyVersion),
                    features = it.features,
                    supportedLanguagePairs = it.pairs
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取配置缓存失败: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun writeCache(config: AppConfig) {
        try {
            prefs.edit().putString(
                KEY_CONFIG,
                gson.toJson(
                    CachedConfig(
                        minimumClientVersion = config.minimumClientVersion,
                        termsVersion = config.legal.termsVersion,
                        privacyVersion = config.legal.privacyVersion,
                        features = config.features,
                        pairs = config.supportedLanguagePairs
                    )
                )
            ).apply()
        } catch (e: Exception) {
            Log.w(TAG, "写入配置缓存失败: ${e.javaClass.simpleName}")
        }
    }
}
