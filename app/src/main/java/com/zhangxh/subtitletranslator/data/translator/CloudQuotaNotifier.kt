package com.zhangxh.subtitletranslator.data.translator

import com.zhangxh.subtitletranslator.domain.translation.CloudQuotaExceeded
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 云端额度用尽事件
 *
 * 翻译器运行在后台线程，不能直接启动 Activity；由悬浮窗服务收集后
 * 在主线程提示并打开主应用的受限页。
 */
class CloudQuotaNotifier {

    private val _events = MutableSharedFlow<CloudQuotaExceeded>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<CloudQuotaExceeded> = _events.asSharedFlow()

    fun emit(event: CloudQuotaExceeded) {
        _events.tryEmit(event)
    }
}
