package com.zhangxh.subtitletranslator.domain.translator

/**
 * 翻译引擎
 *
 * ML Kit 完全在端侧运行；[BAIDU] / [GOOGLE] 都表示「云端翻译」，
 * 实际统一调用本应用后端的标准文本翻译接口，第三方凭据与供应商只在服务端配置。
 * 保留两个历史枚举名是为了兼容旧版本已保存的设置。
 */
enum class TranslationEngine(
    /** 是否需要联网 */
    val needsNetwork: Boolean
) {
    /** 端侧离线翻译，开箱即用 */
    ML_KIT(false),

    /** 云端翻译（历史选项，已改为走应用后端） */
    BAIDU(true),

    /** 云端翻译（历史选项，已改为走应用后端） */
    GOOGLE(true);

    companion object {
        /** 默认用离线引擎：不需要登录，也不用网络 */
        val DEFAULT = ML_KIT

        /** 按枚举名还原，无法识别时返回 [DEFAULT]（兼容旧版本存的设置） */
        fun fromName(name: String?): TranslationEngine =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
