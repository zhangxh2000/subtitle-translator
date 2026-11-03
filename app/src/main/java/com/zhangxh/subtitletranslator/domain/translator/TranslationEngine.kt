package com.zhangxh.subtitletranslator.domain.translator

/**
 * 翻译引擎
 *
 * 云端引擎的质量明显好于端侧的 ML Kit（尤其字幕这种口语化短句），
 * 但需要联网；因此云端引擎失败时都会自动回退到 ML Kit，保证离线也能用。
 */
enum class TranslationEngine(
    /** 是否需要联网 */
    val needsNetwork: Boolean
) {
    /** 端侧离线翻译，开箱即用 */
    ML_KIT(false),

    /** 百度翻译，使用内置凭据，开箱即用 */
    BAIDU(true),

    /** Google 翻译，走公开接口，无需配置 */
    GOOGLE(true);

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
 * 当前由应用内置（见 data 层的 BaiduDefaults），所有用户共用同一份额度。
 */
data class BaiduCredentials(
    val appId: String,
    val secret: String
) {
    /** 两项都填了才算配置完成 */
    val isComplete: Boolean
        get() = appId.isNotBlank() && secret.isNotBlank()
}
