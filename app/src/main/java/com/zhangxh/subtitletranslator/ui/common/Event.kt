package com.zhangxh.subtitletranslator.ui.common

/**
 * 一次性事件
 *
 * LiveData 会在配置变化/重新观察时重放最后一个值；Toast、权限请求、导航这类动作
 * 只应执行一次，所以用这个包装器把「已消费」状态记下来。
 */
class Event<out T>(private val content: T) {

    var hasBeenHandled = false
        private set

    /** 首次调用返回内容，之后返回 null */
    fun getContentIfNotHandled(): T? =
        if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }

    /** 查看内容但不消费 */
    fun peekContent(): T = content
}
