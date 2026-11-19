package com.zhangxh.subtitletranslator.data.translator

import android.content.Context
import android.util.Log
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.remote.TranslationApi
import com.zhangxh.subtitletranslator.domain.entitlement.IEntitlementManager
import com.zhangxh.subtitletranslator.domain.translator.ITranslator

/**
 * 创建按登录态自动选择的翻译器
 *
 * ML Kit 在本机翻译；云端统一调用应用后端的文本翻译接口，不再直连第三方。
 * 游客只走 ML Kit；登录用户优先云端，失败或额度用尽时回退 ML Kit。
 */
class TranslatorProvider(
    private val context: Context,
    private val translationApi: TranslationApi,
    private val sessionManager: AuthSessionManager,
    private val entitlementManager: IEntitlementManager,
    private val quotaGuard: CloudQuotaGuard
) {

    private companion object {
        const val TAG = "TranslatorProvider"
    }

    fun create(): ITranslator {
        Log.d(TAG, "创建按账号自动选择的翻译器")
        return AccountAwareTranslator(
            authManager = sessionManager,
            entitlementManager = entitlementManager,
            local = MLKitTranslator(context),
            cloud = BackendTranslator(translationApi, sessionManager, quotaGuard),
            quotaGuard = quotaGuard
        )
    }
}
