package com.zhangxh.subtitletranslator.domain.sentence

/**
 * 句子收藏仓库
 *
 * 实现必须在 IO 线程执行数据库操作，且不抛异常：读失败返回空结果，写失败返回 false。
 */
interface ISentenceRepository {

    /** 已收藏则删除，未收藏则插入；返回切换后的状态 */
    suspend fun toggle(source: String, translation: String? = null): SentenceToggleResult

    /** 幂等新增；已存在时保留第一次收藏的译文与时间 */
    suspend fun add(source: String, translation: String? = null): Boolean

    /** 按原文删除（大小写、空白不敏感）；不存在也视为成功 */
    suspend fun remove(source: String): Boolean

    /** 按原文查询是否已收藏 */
    suspend fun isSaved(source: String): Boolean

    /** 按原文查询完整记录；不存在返回 null */
    suspend fun find(source: String): SavedSentence?

    /** 全部句子收藏，最新收藏的在前 */
    suspend fun list(): List<SavedSentence>
}
