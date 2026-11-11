package com.zhangxh.subtitletranslator.data.vocabulary

import android.app.Application
import android.content.Context
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStatus
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWordContext
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyItemType
import com.zhangxh.subtitletranslator.domain.wordextractor.Meaning
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 生词库 SQLite 实现测试
 *
 * 用真实的数据库文件（Robolectric 的沙箱里），每个用例前后删库，保证互不影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SqliteVocabularyRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: SqliteVocabularyRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(VocabularyDatabaseHelper.DATABASE_NAME)
        repository = SqliteVocabularyRepository(context)
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(VocabularyDatabaseHelper.DATABASE_NAME)
    }

    private fun entry(
        word: String,
        phonetic: String = "ˈwɜːd",
        difficulty: WordDifficulty = WordDifficulty.MEDIUM,
        meanings: List<Meaning> = listOf(
            Meaning("n.", "a word", "单词", listOf("This is a word."))
        )
    ) = WordEntry(word = word, phonetic = phonetic, meanings = meanings, difficulty = difficulty)

    /** 收藏的字段必须原样回来（含例句） */
    @Test
    fun `saved word round trips all fields`() {
        val item = entry(
            word = "serendipity",
            phonetic = "ˌserənˈdɪpəti",
            difficulty = WordDifficulty.HARD,
            meanings = listOf(
                Meaning(
                    partOfSpeech = "n.",
                    definition = "the occurrence of events by chance",
                    chineseDefinition = "意外发现",
                    examples = listOf("A moment of serendipity.", "意外的幸运")
                ),
                Meaning("adj.", "lucky", "幸运的", emptyList())
            )
        )

        assertTrue(runBlocking { repository.add(item) })

        val saved = runBlocking { repository.list() }.single()
        assertEquals("serendipity", saved.word)
        assertEquals("ˌserənˈdɪpəti", saved.phonetic)
        assertEquals(WordDifficulty.HARD, saved.difficulty)
        assertEquals(item.meanings, saved.meanings)
        assertTrue("收藏时间未写入", saved.createdAt > 0)
        assertTrue("自增 id 未写入", saved.id > 0)
    }

    /** 最新收藏的排在最前 */
    @Test
    fun `list returns newest first`() {
        runBlocking {
            repository.add(entry("one"))
            repository.add(entry("two"))
        }

        assertEquals(
            listOf("two", "one"),
            runBlocking { repository.list() }.map { it.word }
        )
    }

    /** 同一个词大小写不同只存一条，并保留第一次的展示写法 */
    @Test
    fun `dedupes case insensitively and keeps the first display form`() {
        runBlocking {
            repository.add(entry("Hello"))
            repository.add(entry("hello"))
        }

        val saved = runBlocking { repository.list() }
        assertEquals(1, saved.size)
        assertEquals("Hello", saved.single().word)
    }

    /** toggle：未收藏 → 收藏，再点 → 取消 */
    @Test
    fun `toggle saves then removes`() {
        val word = entry("ubiquitous")

        val first = runBlocking { repository.toggle(word) }
        assertTrue("第一次切换应成功", first.success)
        assertTrue("第一次切换后应为已收藏", first.saved)
        assertEquals(1, runBlocking { repository.list() }.size)

        val second = runBlocking { repository.toggle(word) }
        assertTrue("第二次切换应成功", second.success)
        assertFalse("第二次切换后应为未收藏", second.saved)
        assertTrue(runBlocking { repository.list() }.isEmpty())
    }

    @Test
    fun `remove and clear`() {
        runBlocking {
            repository.add(entry("one"))
            repository.add(entry("two"))

            assertTrue(repository.remove("ONE"))
            assertEquals(listOf("two"), repository.list().map { it.word })

            // 词本来就不在也算成功（幂等）
            assertTrue(repository.remove("missing"))

            assertTrue(repository.clear())
            assertTrue(repository.list().isEmpty())
        }
    }

    /** 批量查询大小写不敏感，返回值保持入参写法，便于调用方直接匹配行 */
    @Test
    fun `savedWords matches case insensitively and keeps input casing`() {
        runBlocking {
            repository.add(entry("Hello"))
            repository.add(entry("world"))

            assertEquals(
                setOf("HELLO", "World"),
                repository.savedWords(listOf("HELLO", "World", "missing"))
            )
            assertTrue(repository.savedWords(emptyList()).isEmpty())
        }
    }

    @Test
    fun `empty meanings round trip`() {
        runBlocking { repository.add(entry("empty", meanings = emptyList())) }

        assertTrue(runBlocking { repository.list() }.single().meanings.isEmpty())
    }

    /** 收藏时带字幕上下文；短语自动识别，学习状态可更新 */
    @Test
    fun `context learning state and item type round trip`() {
        val phrase = entry("give up")
        runBlocking {
            repository.add(
                phrase,
                SavedWordContext(
                    source = "I won't give up.",
                    translation = "我不会放弃。"
                )
            )
        }

        val saved = runBlocking { repository.list() }.single()
        assertEquals(VocabularyItemType.PHRASE, saved.itemType)
        assertEquals("I won't give up.", saved.context?.source)
        assertEquals("我不会放弃。", saved.context?.translation)
        assertEquals(LearningStatus.NEW, saved.learningStatus)
        assertEquals(0, saved.familiarity)

        val reviewedAt = 1_700_000_000_000L
        assertTrue(
            runBlocking {
                repository.updateLearningState(
                    word = "give up",
                    status = LearningStatus.MASTERED,
                    familiarity = 3,
                    reviewedAt = reviewedAt
                )
            }
        )

        val updated = requireNotNull(runBlocking { repository.find("GIVE UP") })
        assertEquals(LearningStatus.MASTERED, updated.learningStatus)
        assertEquals(3, updated.familiarity)
        assertEquals(reviewedAt, updated.lastReviewedAt)
        assertEquals("我不会放弃。", updated.context?.translation)
    }

    @Test
    fun `word item type is inferred for single words`() {
        runBlocking { repository.add(entry("hello")) }
        assertEquals(
            VocabularyItemType.WORD,
            runBlocking { repository.list() }.single().itemType
        )
    }

    /** 版本 1 的旧库升级后保留原数据，并补上新列默认值 */
    @Test
    fun `version one database migrates without losing data`() {
        repository.close()
        context.deleteDatabase(VocabularyDatabaseHelper.DATABASE_NAME)

        val legacy = context.openOrCreateDatabase(
            VocabularyDatabaseHelper.DATABASE_NAME,
            Context.MODE_PRIVATE,
            null
        )
        legacy.execSQL(
            """
            CREATE TABLE saved_words (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL,
                word_key TEXT NOT NULL COLLATE NOCASE,
                phonetic TEXT NOT NULL DEFAULT '',
                meanings_json TEXT NOT NULL DEFAULT '[]',
                difficulty TEXT NOT NULL DEFAULT 'MEDIUM',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        legacy.execSQL(
            "CREATE UNIQUE INDEX idx_saved_words_word_key " +
                "ON saved_words(word_key COLLATE NOCASE)"
        )
        legacy.execSQL(
            "INSERT INTO saved_words " +
                "(word, word_key, phonetic, meanings_json, difficulty, created_at) " +
                "VALUES ('legacy', 'legacy', '', '[]', 'EASY', 12345)"
        )
        legacy.version = 1
        legacy.close()

        repository = SqliteVocabularyRepository(context)
        val migrated = runBlocking { repository.list() }.single()
        assertEquals("legacy", migrated.word)
        assertEquals(12345L, migrated.createdAt)
        assertEquals(LearningStatus.NEW, migrated.learningStatus)
        assertEquals(0, migrated.familiarity)
        assertEquals(null, migrated.lastReviewedAt)
        assertEquals(null, migrated.context)
        assertEquals(VocabularyItemType.WORD, migrated.itemType)

        // v3 同步元数据：补 UUID 并排队等待首次上传
        val pending = runBlocking { repository.pendingChanges(10) }
        assertEquals(1, pending.size)
        assertTrue("迁移后应补上 clientReferenceId", pending.single().clientReferenceId.isNotBlank())
    }

    /** 关掉再重开（相当于进程重启）数据还在 */
    @Test
    fun `data survives repository recreation`() {
        runBlocking { repository.add(entry("persistent")) }
        repository.close()

        val reopened = SqliteVocabularyRepository(context)
        try {
            assertEquals(
                listOf("persistent"),
                runBlocking { reopened.list() }.map { it.word }
            )
        } finally {
            reopened.close()
        }
    }

    /** 连续快速切换不能交错出错：偶数次切换后回到未收藏 */
    @Test
    fun `concurrent toggles are serialized`() {
        val word = entry("race")

        val results = runBlocking {
            coroutineScope {
                (1..10).map {
                    async(Dispatchers.Default) { repository.toggle(word) }
                }.awaitAll()
            }
        }

        assertTrue("所有切换都应成功: $results", results.all { it.success })
        assertTrue("偶数次切换后应回到未收藏", runBlocking { repository.list() }.isEmpty())
    }
}
