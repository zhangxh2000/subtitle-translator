package com.zhangxh.subtitletranslator.data.translator

import android.util.Log
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.entitlement.IEntitlementManager
import com.zhangxh.subtitletranslator.domain.entitlement.Plan
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.translator.ITranslator

/**
 * 按登录态自动选择翻译通道
 *
 * - 游客：只用端侧 ML Kit，不发起任何云端请求；
 * - 已登录：优先云端文本翻译；断网、服务不可用、语言对不支持或额度用尽时回退 ML Kit。
 *
 * 每次翻译都重新读取登录态，因此服务运行期间登录 / 退出会立即生效，
 * 不需要用户重启悬浮窗。额度判断放在这里而不是 [BackendTranslator]，
 * 保证「额度已用完」时连请求都不发。会员不受本地额度缓存限制：
 * 免费账号当天用完后再升级会员时，缓存里仍是 0，必须绕过它才能立刻使用云端。
 */
class AccountAwareTranslator(
    private val authManager: AuthManager,
    private val entitlementManager: IEntitlementManager,
    private val local: ITranslator,
    private val cloud: ITranslator,
    private val quotaGuard: CloudQuotaGuard
) : ITranslator {

    private companion object {
        const val TAG = "AccountAwareTranslator"
    }

    override fun getName(): String = "自动（游客本地 / 登录云端）"

    override fun isOffline(): Boolean = false

    /** 本地引擎必须始终可用；云端接口无需预加载 */
    override suspend fun prepare(sourceLang: String, targetLang: String): Result<Unit> =
        local.prepare(sourceLang, targetLang)

    override suspend fun translate(text: String, from: String, to: String): Result<String> {
        val user = authManager.currentUser()
        if (user == null) {
            Log.d(TAG, "游客使用本地翻译")
            return local.translate(text, from, to)
        }

        val isMember = entitlementManager.entitlement.value.effectivePlan() == Plan.MEMBER
        if (!isMember && !quotaGuard.canUseCloud(user.id)) {
            Log.i(TAG, "云端额度已用完，使用本地翻译")
            quotaGuard.notifyIfExhausted(user.id)
            return local.translate(text, from, to)
        }

        val result = cloud.translate(text, from, to)
        if (result.isSuccess) return result

        val cause = result.exceptionOrNull()
        val reason = (cause as? TranslationException)?.error?.code
            ?: cause?.javaClass?.simpleName
            ?: "unknown"
        Log.w(TAG, "云端翻译失败，回退本地: $reason")
        return local.translate(text, from, to)
    }

    override fun release() {
        cloud.release()
        local.release()
    }
}
