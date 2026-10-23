package com.zhangxh.subtitletranslator.domain

import android.content.Context
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.ocr.IOcrEngine
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import com.zhangxh.subtitletranslator.domain.ocr.OcrResult
import com.zhangxh.subtitletranslator.domain.screenshot.IScreenCaptureManager
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import com.zhangxh.subtitletranslator.domain.wordextractor.IWordExtractor
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 翻译流程的媒体暂停时机
 *
 * YouTube 等 App 暂停后会显示控制条，控制条被截进图片会干扰 OCR。
 * 因此必须先完成截图，再触发暂停（onScreenshotCaptured）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranslationCoordinatorTest {

    private val events = mutableListOf<String>()

    private fun screenBitmap(): Bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)

    private inner class FakeScreenCapture(private val result: Bitmap?) : IScreenCaptureManager {
        override fun initialize(
            context: Context,
            mediaProjection: MediaProjection,
            width: Int,
            height: Int,
            density: Int
        ) = Unit

        override suspend fun captureScreen(): Bitmap? {
            events.add("capture")
            return result
        }

        override fun release() = Unit
        override fun isInitialized(): Boolean = true
    }

    private inner class FakeOcrEngine : IOcrEngine {
        override suspend fun recognizeText(bitmap: Bitmap): OcrResult {
            events.add("ocr")
            return OcrResult(text = "Hello there", isSuccess = true)
        }

        override fun release() = Unit
    }

    private inner class FakeTranslator : ITranslator {
        override suspend fun translate(text: String, from: String, to: String): Result<String> {
            events.add("translate")
            return Result.success("你好")
        }

        override fun getName(): String = "fake"
        override fun isOffline(): Boolean = true
        override suspend fun prepare(sourceLang: String, targetLang: String): Result<Unit> =
            Result.success(Unit)

        override fun release() = Unit
    }

    private inner class FakeWordExtractor : IWordExtractor {
        override suspend fun extractDifficultWords(text: String, maxWords: Int): List<WordEntry> {
            events.add("extract")
            return emptyList()
        }

        override suspend fun lookupWord(word: String): WordEntry? = null
    }

    private fun coordinator(screen: Bitmap?): TranslationCoordinator = TranslationCoordinator(
        context = RuntimeEnvironment.getApplication(),
        screenCapture = FakeScreenCapture(screen),
        ocrEngine = FakeOcrEngine(),
        translator = FakeTranslator(),
        wordExtractor = FakeWordExtractor(),
        isDebugCaptureEnabled = { false },
        preprocessMode = { OcrPreprocessMode.GRAYSCALE },
        onScreenshotCaptured = { events.add("pause") }
    )

    @Test
    fun `pauses after capturing and before OCR`() = runBlocking {
        val result = coordinator(screenBitmap()).translateSubtitle()

        assertTrue(result.isSuccess)
        assertEquals(listOf("capture", "pause", "ocr", "translate", "extract"), events)
    }

    @Test
    fun `does not pause when the screenshot fails`() = runBlocking {
        val result = coordinator(null).translateSubtitle()

        assertEquals(AppError.CAPTURE_FAILED, result.error?.code)
        assertEquals(listOf("capture"), events)
    }
}
