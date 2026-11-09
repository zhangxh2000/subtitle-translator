package com.zhangxh.subtitletranslator.ui.auth

import com.zhangxh.subtitletranslator.data.auth.testUserDomain
import com.zhangxh.subtitletranslator.domain.auth.AuthRepository
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import com.zhangxh.subtitletranslator.domain.config.AppConfig
import com.zhangxh.subtitletranslator.domain.config.ConfigRepository
import com.zhangxh.subtitletranslator.domain.config.FeatureFlags
import com.zhangxh.subtitletranslator.domain.config.LegalVersions
import kotlinx.coroutines.CompletableDeferred

class FakeAuthRepository : AuthRepository {
    var loginCalls = 0
    var registerCalls = 0
    var lastEmail: String? = null
    var lastPassword: String? = null
    var lastDisplayName: String? = null
    var lastTermsVersion: String? = null
    var lastPrivacyVersion: String? = null
    var logoutCalls = 0

    var loginResult: Result<AuthUser> = Result.success(testUserDomain())
    var registerResult: Result<AuthUser> = Result.success(testUserDomain())
    var loginGate: CompletableDeferred<Result<AuthUser>>? = null

    override suspend fun restoreSession() = Unit

    override suspend fun login(email: String, password: String): Result<AuthUser> {
        loginCalls++
        lastEmail = email
        lastPassword = password
        return loginGate?.await() ?: loginResult
    }

    override suspend fun register(
        email: String,
        password: String,
        displayName: String?,
        termsVersion: String,
        privacyVersion: String
    ): Result<AuthUser> {
        registerCalls++
        lastEmail = email
        lastPassword = password
        lastDisplayName = displayName
        lastTermsVersion = termsVersion
        lastPrivacyVersion = privacyVersion
        return registerResult
    }

    override suspend fun logout() {
        logoutCalls++
    }
}

class FakeConfigRepository(
    var config: AppConfig? = testAppConfig()
) : ConfigRepository {
    var loadCalls = 0

    override suspend fun load(forceRefresh: Boolean): AppConfig? {
        loadCalls++
        return config
    }

    override fun cached(): AppConfig? = config
}

fun testAppConfig(
    termsVersion: String = "terms-1",
    privacyVersion: String = "privacy-1"
): AppConfig = AppConfig(
    minimumClientVersion = "1.0.0",
    legal = LegalVersions(termsVersion, privacyVersion),
    features = FeatureFlags()
)
