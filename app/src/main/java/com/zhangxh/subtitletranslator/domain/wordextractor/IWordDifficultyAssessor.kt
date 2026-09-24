package com.zhangxh.subtitletranslator.domain.wordextractor

import com.zhangxh.subtitletranslator.domain.dictionary.DictionaryEntry

/**
 * 单词难度评估器
 *
 * 不同语言的难度判定依据不同（英文看考试大纲标签和语料库词频，
 * 日语可能看 JLPT 等级），因此按语言提供实现。
 */
interface IWordDifficultyAssessor {

    /**
     * 评估词条难度
     *
     * @param entry 词典条目，为 null 表示词典未收录该词
     * @return 难度等级；返回 null 表示无法评估（通常因为词典未收录，
     *         多为专有名词或拼写错误，不应作为生词展示）
     */
    fun assess(entry: DictionaryEntry?): WordDifficulty?
}
