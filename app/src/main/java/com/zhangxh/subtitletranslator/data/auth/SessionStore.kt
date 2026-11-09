package com.zhangxh.subtitletranslator.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.zhangxh.subtitletranslator.domain.auth.AuthTokens
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import java.time.Instant

/**
 * 登录会话的持久化接口
 *
 * Token 属于敏感凭证，正式实现必须放在加密存储里；测试使用内存实现。
 */
interface SessionStore {
    fun readUser(): AuthUser?
    fun readTokens(): AuthTokens?
    fun saveSession(user: AuthUser, tokens: AuthTokens)
    fun saveTokens(tokens: AuthTokens)
    fun clear()
}

/**
 * 基于 SharedPreferences 的会话序列化逻辑
 *
 * 单独抽出来是为了能在 Robolectric 中用普通 SharedPreferences 测试
 * Token/用户字段的读写；加密只是存储介质，不改变数据格式。
 */
internal class PrefsSessionStore(
    private val prefs: SharedPreferences,
    private val gson: Gson = Gson()
) : SessionStore {

    private companion object {
        const val TAG = "SessionStore"
        const val KEY_USER = "user"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_ACCESS_EXPIRES = "access_expires_at"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_REFRESH_EXPIRES = "refresh_expires_at"
        const val KEY_TOKEN_TYPE = "token_type"
    }

    private data class StoredUser(
        val id: String,
        val email: String?,
        val emailVerified: Boolean,
        val displayName: String?,
        val status: String,
        val createdAtMillis: Long?,
        val updatedAtMillis: Long?
    )

    override fun readUser(): AuthUser? = readSafely("读取用户") {
        val json = prefs.getString(KEY_USER, null) ?: return@readSafely null
        gson.fromJson(json, StoredUser::class.java)?.let { stored ->
            AuthUser(
                id = stored.id,
                email = stored.email,
                emailVerified = stored.emailVerified,
                displayName = stored.displayName,
                status = stored.status,
                createdAt = stored.createdAtMillis?.let(Instant::ofEpochMilli),
                updatedAt = stored.updatedAtMillis?.let(Instant::ofEpochMilli)
            )
        }
    }

    override fun readTokens(): AuthTokens? = readSafely("读取 Token") {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return@readSafely null
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return@readSafely null
        val accessExpires = prefs.getLong(KEY_ACCESS_EXPIRES, 0L)
        val refreshExpires = prefs.getLong(KEY_REFRESH_EXPIRES, 0L)
        if (accessExpires <= 0L || refreshExpires <= 0L) return@readSafely null
        AuthTokens(
            tokenType = prefs.getString(KEY_TOKEN_TYPE, "Bearer") ?: "Bearer",
            accessToken = access,
            accessTokenExpiresAt = Instant.ofEpochMilli(accessExpires),
            refreshToken = refresh,
            refreshTokenExpiresAt = Instant.ofEpochMilli(refreshExpires)
        )
    }

    override fun saveSession(user: AuthUser, tokens: AuthTokens) {
        writeSafely("保存会话") {
            prefs.edit()
                .putString(
                    KEY_USER,
                    gson.toJson(
                        StoredUser(
                            id = user.id,
                            email = user.email,
                            emailVerified = user.emailVerified,
                            displayName = user.displayName,
                            status = user.status,
                            createdAtMillis = user.createdAt?.toEpochMilli(),
                            updatedAtMillis = user.updatedAt?.toEpochMilli()
                        )
                    )
                )
                .putTokens(tokens)
                .commit()
        }
    }

    override fun saveTokens(tokens: AuthTokens) {
        writeSafely("保存 Token") {
            prefs.edit().putTokens(tokens).commit()
        }
    }

    override fun clear() {
        writeSafely("清空会话") { prefs.edit().clear().commit() }
    }

    private fun SharedPreferences.Editor.putTokens(tokens: AuthTokens): SharedPreferences.Editor =
        putString(KEY_TOKEN_TYPE, tokens.tokenType)
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .putLong(KEY_ACCESS_EXPIRES, tokens.accessTokenExpiresAt.toEpochMilli())
            .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            .putLong(KEY_REFRESH_EXPIRES, tokens.refreshTokenExpiresAt.toEpochMilli())

    private fun <T> readSafely(action: String, block: () -> T?): T? =
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$action 失败: ${e.javaClass.simpleName}")
            null
        }

    private fun writeSafely(action: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$action 失败: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * EncryptedSharedPreferences 实现
 *
 * 构造时可能因设备 Keystore 异常失败，由 AppContainer 回退到内存实现；
 * 任何读写异常都只记录类型，不打印 token 内容。
 */
class EncryptedSessionStore(context: Context) : SessionStore {

    private companion object {
        const val FILE_NAME = "auth_session_encrypted"
    }

    private val delegate: SessionStore = PrefsSessionStore(createPrefs(context.applicationContext))

    override fun readUser(): AuthUser? = delegate.readUser()
    override fun readTokens(): AuthTokens? = delegate.readTokens()
    override fun saveSession(user: AuthUser, tokens: AuthTokens) = delegate.saveSession(user, tokens)
    override fun saveTokens(tokens: AuthTokens) = delegate.saveTokens(tokens)
    override fun clear() = delegate.clear()

    private fun createPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}

/** Keystore 不可用时的降级实现：会话仅保留到进程结束，不落明文 */
class InMemorySessionStore : SessionStore {
    private var user: AuthUser? = null
    private var tokens: AuthTokens? = null

    @Synchronized
    override fun readUser(): AuthUser? = user

    @Synchronized
    override fun readTokens(): AuthTokens? = tokens

    @Synchronized
    override fun saveSession(user: AuthUser, tokens: AuthTokens) {
        this.user = user
        this.tokens = tokens
    }

    @Synchronized
    override fun saveTokens(tokens: AuthTokens) {
        this.tokens = tokens
    }

    @Synchronized
    override fun clear() {
        user = null
        tokens = null
    }
}
