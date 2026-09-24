package com.zhangxh.subtitletranslator.domain.dictionary

/**
 * 词典仓库接口
 *
 * 每个实例绑定一种语言（[language]），便于按语言扩展：
 * 以后支持日语只需生成 ja 词库并注册一个新的仓库实现，接口本身不变。
 *
 * 实现需保证：查询在 IO 线程执行、数据库只读打开、查询失败返回 null 而不是抛异常。
 */
interface IDictionaryRepository {

    /** 本仓库负责的语言代码，如 "en" */
    val language: String

    /**
     * 查询单个单词
     *
     * 实现应先做精确匹配，未命中再尝试词形还原
     * （如 "wolves" 还原为 "wolf"），命中的原形放在 [DictionaryEntry.matchedLemma] 中。
     *
     * @return 未收录的词返回 null
     */
    suspend fun lookup(word: String): DictionaryEntry?

    /**
     * 批量查询
     *
     * @param words 待查询的词（可含屈折形式），大小写不敏感
     * @return 以**传入的查询形式**为 key 的结果表，未收录的词不出现在结果中。
     *         例如传入 "wolves" 且还原到 "wolf"，key 是 "wolves"，value 是 wolf 的词条
     */
    suspend fun lookupBatch(words: List<String>): Map<String, DictionaryEntry>

    /** 词典是否已就绪（数据库已打开且可查询） */
    fun isReady(): Boolean
}
