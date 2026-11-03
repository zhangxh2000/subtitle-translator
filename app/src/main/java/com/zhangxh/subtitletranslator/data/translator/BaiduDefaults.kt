package com.zhangxh.subtitletranslator.data.translator

import com.zhangxh.subtitletranslator.domain.translator.BaiduCredentials

/**
 * 百度翻译内置配置
 *
 * 产品要求开箱即用，所以凭据随 APK 分发，用户不需要在设置里填写。
 * 注意：APK 可以被解包，凭据不是秘密；额度也是所有用户共用的。
 */
object BaiduDefaults {
    const val APP_ID = "20250811002428473"
    const val SECRET = "Dumr2APBB0vuZYelYqZy"

    val credentials: BaiduCredentials = BaiduCredentials(APP_ID, SECRET)
}
