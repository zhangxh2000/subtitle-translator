package com.zhangxh.subtitletranslator.domain.vocabulary

import com.zhangxh.subtitletranslator.domain.wordextractor.Meaning
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty

/**
 * 生词库里的一条生词
 */
data class SavedWord(
    val id: Long,
    val word: String,
    val phonetic: String,
    val meanings: List<Meaning>,
    val difficulty: WordDifficulty,
    val createdAt: Long
)

/**
 * 收藏切换的结果
 *
 * @property saved 切换后的收藏状态
 * @property success 数据库操作是否成功；失败时 [saved] 无意义
 */
data class ToggleResult(
    val saved: Boolean,
    val success: Boolean
)
