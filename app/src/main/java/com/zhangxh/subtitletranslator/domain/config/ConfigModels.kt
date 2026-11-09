package com.zhangxh.subtitletranslator.domain.config

/** 注册时提交给服务端的条款/隐私版本 */
data class LegalVersions(
    val termsVersion: String,
    val privacyVersion: String
)

/** 服务端功能开关；客户端不得展示被关闭的功能入口 */
data class FeatureFlags(
    val imageTranslation: Boolean = false,
    val googleSignIn: Boolean = false,
    val appleSignIn: Boolean = false,
    val vocabularyExtraction: Boolean = false,
    val subscriptions: Boolean = false
)

data class LanguagePair(
    val source: String,
    val target: String
)

/** 客户端需要的公开配置子集 */
data class AppConfig(
    val minimumClientVersion: String,
    val legal: LegalVersions,
    val features: FeatureFlags,
    val supportedLanguagePairs: List<LanguagePair> = emptyList()
)
