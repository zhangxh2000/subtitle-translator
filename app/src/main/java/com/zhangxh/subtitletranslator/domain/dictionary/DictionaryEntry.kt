package com.zhangxh.subtitletranslator.domain.dictionary

/**
 * 词典条目（领域模型，与具体数据源无关）
 *
 * 当前数据来自本地 SQLite 版 ECDICT，未来接入在线词典或其它语言的词库时，
 * 只要实现 [IDictionaryRepository] 并返回本模型即可，上层无需改动。
 */
data class DictionaryEntry(
    /** 词条原形（词典中的词头），如 "wolf" */
    val word: String,
    /** 语言代码，如 "en" */
    val lang: String,
    /** 音标，如 "wʊlf" */
    val phonetic: String? = null,
    /** 中文释义，按词性拆分后的义项 */
    val senses: List<WordSense> = emptyList(),
    /** 英文释义，按词性拆分后的义项 */
    val definitions: List<WordSense> = emptyList(),
    /** 考试大纲标签（zk/gk/cet4/cet6/ky/toefl/ielts/gre），用于评估难度 */
    val tags: List<String> = emptyList(),
    /** 柯林斯星级，0 表示无（1~5 越高越常用） */
    val collins: Int = 0,
    /** 是否为牛津三千核心词 */
    val oxford: Boolean = false,
    /** 英国国家语料库词频排名，0 表示无排名（越小越常见） */
    val bncRank: Int = 0,
    /** 当代语料库词频排名，0 表示无排名（越小越常见） */
    val frqRank: Int = 0,
    /** 词形变化，如 {"过去式": ["went"], "复数": ["wolves"]} */
    val exchange: Map<String, List<String>> = emptyMap(),
    /**
     * 命中的原形。当查询的是屈折形式（如 "wolves"）且通过词形还原查到时，
     * 这里是被还原到的词头（"wolf"）；精确命中时为 null。
     */
    val matchedLemma: String? = null,
    /** 用户实际查询的形式，如 "wolves" */
    val queriedForm: String? = null
) {
    /** 词条是否包含可展示的中文释义 */
    val hasTranslation: Boolean get() = senses.isNotEmpty()

    /** 综合词频排名，两个语料库都取更常见的那个（都没有则为 0） */
    val frequencyRank: Int
        get() = when {
            bncRank <= 0 -> frqRank
            frqRank <= 0 -> bncRank
            else -> minOf(bncRank, frqRank)
        }
}
