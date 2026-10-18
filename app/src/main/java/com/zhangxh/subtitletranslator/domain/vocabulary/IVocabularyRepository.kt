package com.zhangxh.subtitletranslator.domain.vocabulary

import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry

/**
 * 生词库仓库
 *
 * 实现必须在 IO 线程执行数据库操作，且不抛异常：读失败返回空结果，写失败返回 false。
 */
interface IVocabularyRepository {

    /** 已收藏则删除，未收藏则插入；返回切换后的状态 */
    suspend fun toggle(entry: WordEntry): ToggleResult

    /** 幂等新增；已存在时保留第一次的展示写法与释义 */
    suspend fun add(entry: WordEntry): Boolean

    /** 按词删除（大小写不敏感）；词不存在也视为成功 */
    suspend fun remove(word: String): Boolean

    /** 全部生词，最新收藏的在前 */
    suspend fun list(): List<SavedWord>

    /** 清空生词库 */
    suspend fun clear(): Boolean

    /**
     * 批量查询哪些词已收藏
     *
     * 返回值是入参的原样字符串（大小写/空白保持不变），方便调用方直接 `word in result`
     * 去更新对应行的图标。空输入返回空集合。
     */
    suspend fun savedWords(words: List<String>): Set<String>
}
