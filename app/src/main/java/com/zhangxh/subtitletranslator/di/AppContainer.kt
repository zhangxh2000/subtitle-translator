package com.zhangxh.subtitletranslator.di

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.zhangxh.subtitletranslator.data.auth.AuthRepositoryImpl
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.auth.EncryptedSessionStore
import com.zhangxh.subtitletranslator.data.auth.InMemorySessionStore
import com.zhangxh.subtitletranslator.data.auth.LoginPromptCoordinator
import com.zhangxh.subtitletranslator.data.auth.SessionStore
import com.zhangxh.subtitletranslator.data.auth.TokenRefresher
import com.zhangxh.subtitletranslator.data.config.RemoteConfigRepository
import com.zhangxh.subtitletranslator.data.history.SqliteTranslationHistoryRepository
import com.zhangxh.subtitletranslator.data.remote.AuthApi
import com.zhangxh.subtitletranslator.data.remote.RetrofitFactory
import com.zhangxh.subtitletranslator.domain.auth.AuthRepository
import com.zhangxh.subtitletranslator.domain.config.ConfigRepository
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 轻量级手工依赖容器
 *
 * 项目没有 DI 框架；认证需要进程级共享的 Session/Token/Retrofit 实例，
 * 集中在这里装配即可，不为本次需求引入 Hilt/Koin。
 */
class AppContainer(context: Context) {

    private companion object {
        const val TAG = "AppContainer"
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val gson: Gson = GsonBuilder().create()

    /** Keystore 不可用时降级为内存会话，绝不明文落盘 */
    val sessionStore: SessionStore = try {
        EncryptedSessionStore(context)
    } catch (e: Exception) {
        Log.e(TAG, "安全存储初始化失败，会话将不持久化: ${e.javaClass.simpleName}")
        InMemorySessionStore()
    }

    val sessionManager = AuthSessionManager(sessionStore)

    private val bareRetrofit = RetrofitFactory.createBareRetrofit(gson)
    private val bareApi: AuthApi = bareRetrofit.create(AuthApi::class.java)
    private val tokenRefresher = TokenRefresher(sessionManager, bareApi)

    private val authRetrofit = RetrofitFactory.createAuthRetrofit(
        gson = gson,
        tokenProvider = sessionManager,
        tokenRefresher = tokenRefresher
    )
    private val authApi: AuthApi = authRetrofit.create(AuthApi::class.java)

    val authRepository: AuthRepository = AuthRepositoryImpl(authApi, sessionManager)
    // 公开配置走无认证客户端：即使本地 Access Token 已过期，也不应因 /config 触发刷新
    val configRepository: ConfigRepository = RemoteConfigRepository(context, bareApi, gson)
    val loginPromptCoordinator = LoginPromptCoordinator()

    /** 翻译历史与登录无关，游客也会正常记录 */
    val translationHistory: ITranslationHistoryRepository =
        SqliteTranslationHistoryRepository(context)

    /** 应用启动时在后台恢复会话，不阻塞首帧 */
    fun restoreSession() {
        appScope.launch {
            authRepository.restoreSession()
        }
    }
}
