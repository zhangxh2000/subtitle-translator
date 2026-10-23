package com.zhangxh.subtitletranslator.ui.common

import android.content.Context
import androidx.annotation.StringRes

/**
 * 待本地化的文案
 *
 * ViewModel 只传资源 ID 和参数，由 Activity 在展示时按当前系统语言解析，
 * 这样 ViewModel 不持有 Context，也不会把某一种语言的字符串写死。
 */
data class UiText(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList()
) {
    fun resolve(context: Context): String =
        if (args.isEmpty()) {
            context.getString(resId)
        } else {
            context.getString(resId, *args.toTypedArray())
        }
}
