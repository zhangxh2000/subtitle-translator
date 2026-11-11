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
import com.zhangxh.subtitletranslator.data.net.NetworkMonitor
import com.zhangxh.subtitletranslator.data.remote.AuthApi
import com.zhangxh.subtitletranslator.data.remote.RetrofitFactory
import com.zhangxh.subtitletranslator.data.remote.VocabularyApi
import com.zhangxh.subtitletranslator.data.vocabulary.SharedPrefsVocabularySyncStateStore
import com.zhangxh.subtitletranslator.data.vocabulary.SqliteVocabularyRepository
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularySyncCoordinator
import com.zhangxh.subtitletranslator.data.vocabulary.VocabularySyncManager
import com.zhangxh.subtitletranslator.domain.auth.AuthRepository
import com.zhangxh.subtitletranslator.domain.config.ConfigRepository
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
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

    private val networkMonitor = NetworkMonitor(context)

    /**
     * 进程级单例生词仓库
     *
     * 浮窗服务与各页面共享同一个同步队列；每次本地写库后通知同步器，
     * 由同步器合并短时间内的多次操作再静默推送到云端。
     */
    private val sqliteVocabularyRepository = SqliteVocabularyRepository(
        context = context,
        onLocalChange = { vocabularySyncCoordinator.trigger() }
    )
    val vocabularyRepository: IVocabularyRepository = sqliteVocabularyRepository

    private val vocabularySyncManager = VocabularySyncManager(
        api = authRetrofit.create(VocabularyApi::class.java),
        store = sqliteVocabularyRepository,
        stateStore = SharedPrefsVocabularySyncStateStore(context),
        sessionManager = sessionManager,
        networkStatus = networkMonitor
    )

    private val vocabularySyncCoordinator: VocabularySyncCoordinator by lazy {
        VocabularySyncCoordinator(
            authManager = sessionManager,
            syncManager = vocabularySyncManager,
            networkMonitor = networkMonitor,
            scope = appScope
        )
    }

    init {
        // 登录、网络恢复、本地收藏都会触发；未登录时同步器直接返回，不产生网络请求
        vocabularySyncCoordinator.start()
    }

    /** 应用启动时在后台恢复会话，不阻塞首帧；恢复成功会自动触发增量同步 */
    fun restoreSession() {
        appScope.launch {
            authRepository.restoreSession()
        }
    }
}
