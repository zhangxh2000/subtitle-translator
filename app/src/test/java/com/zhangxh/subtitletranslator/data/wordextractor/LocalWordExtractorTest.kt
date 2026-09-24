package com.zhangxh.subtitletranslator.data.wordextractor

import com.zhangxh.subtitletranslator.data.dictionary.DictionaryDatabase
import com.zhangxh.subtitletranslator.data.dictionary.SqliteDictionaryRepository
import com.zhangxh.subtitletranslator.domain.dictionary.EnglishWordSenseParser
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
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
 * 生词提取测试（对着真实词典运行）
 *
 * 这是整个功能的最终效果验证：以前词典为空时所有释义都是「未找到释义」，
 * 这里断言提取出的生词都带有真实释义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalWordExtractorTest {

    private lateinit var database: DictionaryDatabase
    private lateinit var extractor: LocalWordExtractor

    @Before
    fun setUp() {
        database = DictionaryDatabase(RuntimeEnvironment.getApplication())
        val repository = SqliteDictionaryRepository(database, EnglishWordSenseParser, "en")
        extractor = LocalWordExtractor(repository)
        assertTrue(runBlocking { repository.prepareForUse() })
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun extract(text: String, maxWords: Int = 5) =
        runBlocking { extractor.extractDifficultWords(text, maxWords) }

    @Test
    fun `extracts difficult words with real definitions`() {
        val words = extract("The mitochondrial DNA evidence was inconclusive, so the prosecutor dropped the charges.")

        assertTrue("应提取到生词，实际 $words", words.isNotEmpty())
        // 词典中确有释义的词必须被提取出来
        assertTrue(
            "应提取到 mitochondrial: ${words.map { it.word }}",
            words.any { it.word == "mitochondrial" }
        )
        words.forEach { word ->
            assertTrue("${word.word} 应有义项", word.meanings.isNotEmpty())
            word.meanings.forEach { meaning ->
                assertFalse(
                    "${word.word} 的释义仍是占位文案",
                    meaning.chineseDefinition.contains("未找到释义")
                )
                assertFalse("${word.word} 的释义为空", meaning.chineseDefinition.isBlank())
            }
        }
    }

    /** 缩写、冠词、基础词都不该被当作生词 */
    @Test
    fun `skips contractions and basic words`() {
        val words = extract("I don't know what you're talking about, but I think you should calm down.")
        val extracted = words.map { it.word }

        assertFalse("缩写不应被提取: $extracted", extracted.any { it.contains("'") })
        // 基础词即便出现也不该被标为生词
        assertFalse(extracted.contains("know"))
        assertFalse(extracted.contains("think"))
        assertFalse(extracted.contains("about"))
    }

    /**
     * 屈折形式还原成原形后，若原形是基础词也不该被提取
     *
     * 回归测试：met 会还原成 meet，而 meet 同时挂着中考和 GRE 标签——
     * 若难度评估取「最难的标签」而非「最基础的标签」，meet 会被误判成中等难词。
     */
    @Test
    fun `skips inflected form of a basic word`() {
        val extracted = extract("Sherlock Holmes met Moriarty near Reichenbach.").map { it.word }

        assertFalse("meet 是基础词，不应被提取: $extracted", extracted.contains("meet"))
    }

    /**
     * 词典未收录的词不作为生词，避免出现无释义的卡片
     *
     * 注意 ECDICT 收录了不少著名专有名词（holmes、sherlock 等）并带中文释义，
     * 这类词被提取出来是合理的，所以这里用一个确实未收录的地名来验证。
     */
    @Test
    fun `skips words missing from dictionary`() {
        val words = extract("They met near Reichenbach Falls.")
        val extracted = words.map { it.word }

        assertFalse("未收录的词不应被提取: $extracted", extracted.contains("reichenbach"))
    }

    @Test
    fun `respects max words limit`() {
        val text = "The ubiquitous serendipity was inexplicable, devastating, and quintessential."

        assertTrue(extract(text, maxWords = 2).size <= 2)
        assertTrue(extract(text, maxWords = 5).size <= 5)
    }

    /** 越难的词越靠前 */
    @Test
    fun `orders harder words first`() {
        val words = extract("It was an inexplicable and inevitable turn of events.")
        val difficulties = words.map { it.difficulty }

        assertEquals(
            "应按难度降序: ${words.map { it.word to it.difficulty }}",
            difficulties.sortedByDescending { it.ordinal },
            difficulties
        )
    }

    /** 屈折形式提取出来的是原形（wolves → wolf），这样用户查到的才是词典里的词 */
    @Test
    fun `extracts headword for inflected forms`() {
        val words = extract("The wolves were howling at the moon.")
        val extracted = words.map { it.word }

        assertTrue("应提取到 wolf 而非 wolves: $extracted", extracted.contains("wolf"))
        assertFalse(extracted.contains("wolves"))
    }

    @Test
    fun `returns empty list for text without candidate words`() {
        assertTrue(extract("").isEmpty())
        assertTrue(extract("?! ... 123").isEmpty())
    }

    // ---------------------------------------------------------------------
    // 单词查询
    // ---------------------------------------------------------------------

    @Test
    fun `lookup word returns definition for inflected form`() {
        val entry = checkNotNull(runBlocking { extractor.lookupWord("wolves") })

        assertEquals("wolf", entry.word)
        assertTrue("应是狼的释义", entry.meanings.any { it.chineseDefinition.contains("狼") })
    }

    @Test
    fun `lookup word returns null when not found`() {
        assertNull(runBlocking { extractor.lookupWord("zzzzqqqxyz") })
    }

    @Test
    fun `lookup word marks difficulty`() {
        val hard = checkNotNull(runBlocking { extractor.lookupWord("serendipity") })
        assertEquals(WordDifficulty.HARD, hard.difficulty)

        val easy = checkNotNull(runBlocking { extractor.lookupWord("time") })
        assertEquals(WordDifficulty.EASY, easy.difficulty)
    }
}
