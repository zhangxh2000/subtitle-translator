package com.zhangxh.subtitletranslator.data.history

import com.zhangxh.subtitletranslator.domain.TranslationResult
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 翻译历史（进程内）
 *
 * 原本是 FloatingButtonService 里的静态列表；移到 data 层后由服务写入、HistoryViewModel 读取。
 */
object TranslationHistoryStore {

    private const val MAX_SIZE = 50

    private val items = CopyOnWriteArrayList<TranslationResult>()

    /** 追加一条历史，超出上限时丢掉最旧的 */
    fun add(result: TranslationResult) {
        items.add(result)
        while (items.size > MAX_SIZE) {
            items.removeAt(0)
        }
    }

    /** 按添加顺序返回（最旧的在前） */
    fun all(): List<TranslationResult> = items.toList()

    fun clear() {
        items.clear()
    }
}
