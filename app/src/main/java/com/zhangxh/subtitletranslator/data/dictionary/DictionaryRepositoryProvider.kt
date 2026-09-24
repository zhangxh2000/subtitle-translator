package com.zhangxh.subtitletranslator.data.dictionary

import android.content.Context
import android.util.Log
import com.zhangxh.subtitletranslator.domain.dictionary.EnglishWordSenseParser
import com.zhangxh.subtitletranslator.domain.dictionary.IDictionaryRepository
import com.zhangxh.subtitletranslator.domain.dictionary.WordSenseParser

/**
 * 词典仓库注册表
 *
 * 按语言提供对应的词典仓库，是**多语言扩展的接入点**：
 * 目前只有英文，以后支持日语时按下面三步即可，上层代码无需改动 ——
 * 1. 写一个类似 scripts/import_ecdict.py 的脚本，生成 `assets/dictionary_ja.db`
 * 2. 实现该语言的 [WordSenseParser]（解析其释义格式）
 * 3. 在 [DEFAULT_SOURCES] 中登记 `DictionaryAsset("ja", "dictionary_ja.db", 日文解析器)`
 *
 * 注意：构造本身不做任何 IO，数据库要在首次查询时才会拷贝/打开（见 [DictionaryDatabase.prepare]）。
 */
class DictionaryRepositoryProvider(
    context: Context,
    private val sources: List<DictionaryAsset> = DEFAULT_SOURCES
) {

    /**
     * 一份可用的词典数据
     *
     * @param language 语言代码，如 "en"
     * @param assetName assets 中的数据库文件名
     * @param senseParser 该语言释义格式的解析器
     * @param schemaVersion 期望的数据库 schema 版本
     */
    data class DictionaryAsset(
        val language: String,
        val assetName: String,
        val senseParser: WordSenseParser,
        val schemaVersion: Int = DictionaryDatabase.DEFAULT_SCHEMA_VERSION
    )

    companion object {
        private const val TAG = "DictionaryProvider"

        /** 已内置的词典；新增语言在此登记 */
        val DEFAULT_SOURCES = listOf(
            DictionaryAsset(
                language = "en",
                assetName = "dictionary.db",
                senseParser = EnglishWordSenseParser
            )
        )
    }

    private val databases: Map<String, DictionaryDatabase>
    private val repositories: Map<String, IDictionaryRepository>

    init {
        val assetDatabases = mutableMapOf<String, DictionaryDatabase>()
        val assetRepositories = mutableMapOf<String, IDictionaryRepository>()

        sources.forEach { source ->
            // 同一份数据库可能被多个语言共用，复用同一个 Database 实例
            val database = assetDatabases.getOrPut(source.assetName) {
                DictionaryDatabase(context, source.assetName, source.schemaVersion)
            }
            assetRepositories[source.language] = SqliteDictionaryRepository(
                database = database,
                senseParser = source.senseParser,
                language = source.language
            )
        }

        databases = assetDatabases
        repositories = assetRepositories
        Log.d(TAG, "已注册词典语言: ${repositories.keys}")
    }

    /**
     * 获取指定语言的词典仓库
     *
     * @return 该语言没有内置词典时返回 null
     */
    fun repository(language: String): IDictionaryRepository? =
        repositories[language.lowercase()]

    /** 已内置词典的语言列表 */
    fun supportedLanguages(): List<String> = repositories.keys.toList()

    /**
     * 预热词典（拷贝 assets、打开数据库）
     *
     * 首次调用会复制约 16MB 的数据库文件，应在后台线程提前执行，
     * 避免用户第一次查词时等待。
     *
     * @return 是否准备成功
     */
    suspend fun prepare(language: String): Boolean {
        val repository = repository(language) as? SqliteDictionaryRepository ?: return false
        return repository.prepareForUse()
    }

    /** 关闭所有已打开的数据库 */
    fun release() {
        databases.values.forEach { it.close() }
    }
}
