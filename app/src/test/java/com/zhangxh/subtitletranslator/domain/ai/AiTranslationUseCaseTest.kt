package com.zhangxh.subtitletranslator.domain.ai

import android.graphics.Bitmap
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import com.zhangxh.subtitletranslator.domain.history.TranslationDayHistory
import com.zhangxh.subtitletranslator.domain.history.TranslationRecord
import com.zhangxh.subtitletranslator.domain.translator.AiSubtitleTranslation
import com.zhangxh.subtitletranslator.domain.translator.IAiTranslator
import com.zhangxh.subtitletranslator.domain.translator.TranslationContext
import com.zhangxh.subtitletranslator.domain.wordextractor.IWordExtractor
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AI 图片精译的关键失败路径：生词重提取失败沿用旧词表，历史写入失败不影响结果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiTranslationUseCaseTest {

    private val image = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
    private val fallbackWord = WordEntry(word = "fallback")
    private val extractedWord = WordEntry(word = "extracted")

    private inner class FakeTranslator(
        private val result: Result<AiSubtitleTranslation>
    ) : IAiTranslator {
        override suspend fun translate(
            image: Bitmap,
            from: String,
            to: String,
            context: List<TranslationContext>
        ): Result<AiSubtitleTranslation> = result
    }

    private inner class FakeWordExtractor(
        private val words: List<WordEntry> = emptyList(),
        private val failure: Exception? = null
    ) : IWordExtractor {
        override suspend fun extractDifficultWords(text: String, maxWords: Int): List<WordEntry> {
            failure?.let { throw it }
            return words
        }

        override suspend fun lookupWord(word: String): WordEntry? = null
    }

    private inner class FakeHistory(
        private val updateResult: Boolean = true,
        private val failure: Exception? = null
    ) : ITranslationHistoryRepository {
        var updateArgs: Triple<String, String, String?>? = null

        override suspend fun record(
            originalText: String,
            translatedText: String,
            timestamp: Long
        ): Boolean = true

        override suspend fun updateLatestTranslation(
            originalText: String,
            translatedText: String,
            correctedOriginalText: String?
        ): Boolean {
            failure?.let { throw it }
            updateArgs = Triple(originalText, translatedText, correctedOriginalText)
            return updateResult
        }

        override suspend fun recentDays(limit: Int): List<TranslationDayHistory> = emptyList()
        override suspend fun recordsForDay(dayStart: Long): List<TranslationRecord> = emptyList()
        override suspend fun allRecords(): List<TranslationRecord> = emptyList()
        override suspend fun clear(): Boolean = true
    }

    private fun request() = AiTranslationRequest(
        image = image,
        previousOriginalText = "Helo there",
        sourceLanguage = "en",
        targetLanguage = "zh",
        context = emptyList(),
        fallbackWords = listOf(fallbackWord)
    )

    private fun useCase(
        translator: IAiTranslator,
        extractor: IWordExtractor,
        history: ITranslationHistoryRepository
    ) = AiTranslationUseCase(translator, extractor, history)

    @Test
    fun `successful translation returns corrected text and fresh words`() = runBlocking {
        val history = FakeHistory()
        val useCase = useCase(
            translator = FakeTranslator(
                Result.success(AiSubtitleTranslation("Hello there", "你好"))
            ),
            extractor = FakeWordExtractor(words = listOf(extractedWord)),
            history = history
        )

        val outcome = useCase.translate(request())

        val output = outcome.getOrThrow()
        assertEquals("Hello there", output.originalText)
        assertEquals("你好", output.translatedText)
        assertEquals(listOf(extractedWord), output.difficultWords)
        assertEquals(
            Triple("Helo there", "你好", "Hello there"),
            history.updateArgs
        )
    }

    @Test
    fun `word extraction failure keeps fallback words`() = runBlocking {
        val useCase = useCase(
            translator = FakeTranslator(
                Result.success(AiSubtitleTranslation("Hello there", "你好"))
            ),
            extractor = FakeWordExtractor(failure = IllegalStateException("db closed")),
            history = FakeHistory()
        )

        val output = useCase.translate(request()).getOrThrow()

        assertEquals(listOf(fallbackWord), output.difficultWords)
    }

    @Test
    fun `history failure is not fatal`() = runBlocking {
        val useCase = useCase(
            translator = FakeTranslator(
                Result.success(AiSubtitleTranslation("Hello there", "你好"))
            ),
            extractor = FakeWordExtractor(words = listOf(extractedWord)),
            history = FakeHistory(failure = IllegalStateException("db closed"))
        )

        val outcome = useCase.translate(request())

        assertTrue(outcome.isSuccess)
        assertEquals("Hello there", outcome.getOrThrow().originalText)
    }

    @Test
    fun `translator failure does not touch history`() = runBlocking {
        val history = FakeHistory()
        val error = TranslationException(TranslationError(AppError.TRANSLATION_FAILED))
        val useCase = useCase(
            translator = FakeTranslator(Result.failure(error)),
            extractor = FakeWordExtractor(words = listOf(extractedWord)),
            history = history
        )

        val outcome = useCase.translate(request())

        assertTrue(outcome.isFailure)
        assertNull(history.updateArgs)
    }
}
