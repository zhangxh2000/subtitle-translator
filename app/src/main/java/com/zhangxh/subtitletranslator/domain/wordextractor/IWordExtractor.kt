package com.zhangxh.subtitletranslator.domain.wordextractor

/**
 * 难词提取器接口
 */
interface IWordExtractor {
    /**
     * 从文本中提取难词
     * @param text 输入文本
     * @param maxWords 最大返回单词数
     * @return 单词列表（按难度排序）
     */
    suspend fun extractDifficultWords(text: String, maxWords: Int = 5): List<WordEntry>

    /**
     * 获取单词的详细释义
     * @param word 单词
     * @return 单词条目
     */
    suspend fun lookupWord(word: String): WordEntry?
}