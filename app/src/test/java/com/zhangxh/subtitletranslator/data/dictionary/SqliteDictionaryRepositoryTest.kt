package com.zhangxh.subtitletranslator.data.dictionary

import com.zhangxh.subtitletranslator.domain.dictionary.EnglishWordSenseParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 词典仓库测试（对着 assets 中真实的 dictionary.db 运行）
 *
 * 数据库由 scripts/import_ecdict.py 从 ECDICT 生成，本测试同时验证了生成脚本的产物，
 * 所以一旦词典数据重新生成后行为变化，这里会失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SqliteDictionaryRepositoryTest {

    private lateinit var database: DictionaryDatabase
    private lateinit var repository: SqliteDictionaryRepository

    @Before
    fun setUp() {
        database = DictionaryDatabase(RuntimeEnvironment.getApplication())
        repository = SqliteDictionaryRepository(database, EnglishWordSenseParser, "en")
        assertTrue("词典应从 assets 准备成功", runBlocking { repository.prepareForUse() })
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun lookup(word: String) = runBlocking { repository.lookup(word) }

    @Test
    fun `looks up word with phonetic and senses`() {
        val entry = checkNotNull(lookup("run")) { "run 应能查到" }

        assertEquals("run", entry.word)
        assertEquals("en", entry.lang)
        assertFalse("音标不应为空", entry.phonetic.isNullOrBlank())
        assertTrue("应有多个义项", entry.senses.size >= 2)
        assertTrue("应有中文释义", entry.senses.any { it.text.contains("跑") })
        // 精确命中，不应标记为词形还原
        assertNull(entry.matchedLemma)
    }

    @Test
    fun `is case insensitive and trims punctuation`() {
        val upper = checkNotNull(lookup("RUN"))
        val withPunctuation = checkNotNull(lookup("  \"Run,\" "))

        assertEquals("run", upper.word)
        assertEquals("run", withPunctuation.word)
    }

    /** 词典未收录的词返回 null，而不是抛异常或返回空壳条目 */
    @Test
    fun `returns null for unknown word`() {
        assertNull(lookup("zzzzqqqxyz"))
        assertNull(lookup(""))
        assertNull(lookup("   "))
    }

    /**
     * 屈折形式通过 word_form 表还原到原形
     *
     * wolves 在词典中没有独立词条，应还原为 wolf 并记录命中信息。
     */
    @Test
    fun `resolves inflected form to its lemma`() {
        val entry = checkNotNull(lookup("wolves")) { "wolves 应能还原到 wolf" }

        assertEquals("wolf", entry.word)
        assertEquals("wolf", entry.matchedLemma)
        assertEquals("wolves", entry.queriedForm)
        assertTrue("应是狼的释义", entry.senses.any { it.text.contains("狼") })
    }

    /**
     * 屈折形式自身词条冷僻时，用原形词条
     *
     * felt 在 ECDICT 中的独立词条是「毛毯, 毡」(rank 13940)，
     * 但字幕里几乎总是 feel 的过去式，应展示 feel。
     */
    @Test
    fun `prefers lemma when the inflection has an obscure entry`() {
        val felt = checkNotNull(lookup("felt"))
        assertEquals("feel", felt.word)
        assertEquals("feel", felt.matchedLemma)

        val dropped = checkNotNull(lookup("dropped"))
        assertEquals("drop", dropped.word)

        val charges = checkNotNull(lookup("charges"))
        assertEquals("charge", charges.word)
        assertTrue("应展示 charge 的指控义项", charges.senses.any { it.text.contains("指控") })
    }

    /**
     * 屈折形式自身是高频独立词时，保留自身词条
     *
     * left 虽然也是 leave 的过去式，但它本身是 rank 771 的高频词（左边的），
     * 字幕里 "on the left" 远多于 "he left"，必须用自身词条。
     */
    @Test
    fun `keeps standalone word instead of its lemma`() {
        val left = checkNotNull(lookup("left"))
        assertEquals("left", left.word)
        assertNull("left 自身是常用词，不应标记为词形还原", left.matchedLemma)
        assertTrue("应是左边的释义", left.senses.any { it.text.contains("左") })

        val ground = checkNotNull(lookup("ground"))
        assertEquals("ground", ground.word)
        assertTrue(ground.senses.any { it.text.contains("地面") })
    }

    /** 缩写词也能查到（虽然生词提取会跳过它们） */
    @Test
    fun `looks up contractions`() {
        val entry = checkNotNull(lookup("don't")) { "don't 应能查到" }
        assertTrue(entry.senses.isNotEmpty())
    }

    /** 释义中不能残留字面的反斜杠 n，否则 UI 上会直接显示 "\n" */
    @Test
    fun `does not contain literal backslash-n in senses`() {
        listOf("run", "go", "happy", "inevitable").forEach { word ->
            val entry = checkNotNull(lookup(word))
            entry.senses.forEach { sense ->
                assertFalse(
                    "$word 的义项残留字面 \\n: ${sense.text}",
                    sense.text.contains("\\n")
                )
            }
        }
    }

    @Test
    fun `parses word forms from exchange field`() {
        val go = checkNotNull(lookup("go"))
        assertEquals(listOf("went"), go.exchange["过去式"])
        assertEquals(listOf("gone"), go.exchange["过去分词"])
    }

    @Test
    fun `carries difficulty metadata`() {
        val run = checkNotNull(lookup("run"))
        assertTrue("run 应是高频词", run.frequencyRank in 1..2000)
        assertTrue("run 应有关试标签", run.tags.isNotEmpty())
        assertEquals(5, run.collins)
        assertTrue(run.oxford)
    }

    // ---------------------------------------------------------------------
    // 批量查询
    // ---------------------------------------------------------------------

    @Test
    fun `batch lookup keys results by queried form`() {
        val results = runBlocking { repository.lookupBatch(listOf("wolves", "left", "zzzzqqqxyz")) }

        // 未收录的词不应出现在结果里
        assertFalse(results.containsKey("zzzzqqqxyz"))
        // key 是查询形式，value 是命中的词条
        assertEquals("wolf", results.getValue("wolves").word)
        assertEquals("left", results.getValue("left").word)
    }

    /** 批量查询的分批逻辑：超过 SQLite 变量上限也要正常返回 */
    @Test
    fun `batch lookup handles more words than sql variable limit`() {
        val words = (1..1500).map { "fakeword$it" } + listOf("wolves", "run")

        val results = runBlocking { repository.lookupBatch(words) }

        assertEquals(2, results.size)
        assertEquals("wolf", results.getValue("wolves").word)
        assertEquals("run", results.getValue("run").word)
    }

    @Test
    fun `batch lookup returns empty map for empty input`() {
        assertTrue(runBlocking { repository.lookupBatch(emptyList()) }.isEmpty())
    }

    @Test
    fun `database reports ready after preparation`() {
        assertTrue(repository.isReady())
        assertTrue(repository.language == "en")
    }
}
