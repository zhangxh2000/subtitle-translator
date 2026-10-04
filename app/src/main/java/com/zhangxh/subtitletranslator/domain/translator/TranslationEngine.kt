package com.zhangxh.subtitletranslator.domain.translator

/**
 * 翻译引擎
 *
 * 云端引擎的质量明显好于端侧的 ML Kit（尤其字幕这种口语化短句），
 * 但需要联网；因此云端引擎失败时都会自动回退到 ML Kit，保证离线也能用。
 */
enum class TranslationEngine(
    /** 设置界面展示的名称 */
    val label: String,
    /** 是否需要联网 */
    val needsNetwork: Boolean
) {
    /** 端侧离线翻译，开箱即用 */
    ML_KIT("ML Kit 离线（无需配置）", false),

    /** 百度翻译，需要用户填自己的 APP ID 与密钥（免费额度按账号计算） */
    BAIDU("百度翻译（需填密钥）", true),

    /** Google 翻译，走公开接口，无需配置 */
    GOOGLE("Google 翻译（无需密钥）", true);

    companion object {
        /** 默认用离线引擎：不需要任何配置就能用 */
        val DEFAULT = ML_KIT

        /** 按枚举名还原，无法识别时返回 [DEFAULT]（兼容旧版本存的设置） */
        fun fromName(name: String?): TranslationEngine =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 百度翻译的凭据
 *
 * 由用户自己申请并填写：免费额度是按账号计算的，内置到 APK 里会随包泄露并被他人耗尽。
 */
data class BaiduCredentials(
    val appId: String,
    val secret: String
) {
    /** 两项都填了才算配置完成 */
    val isComplete: Boolean
        get() = appId.isNotBlank() && secret.isNotBlank()
}
