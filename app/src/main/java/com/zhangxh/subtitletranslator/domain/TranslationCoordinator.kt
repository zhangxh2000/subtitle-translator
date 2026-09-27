package com.zhangxh.subtitletranslator.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.zhangxh.subtitletranslator.domain.ocr.IOcrEngine
import com.zhangxh.subtitletranslator.domain.ocr.OcrTextCleaner
import com.zhangxh.subtitletranslator.domain.screenshot.IScreenCaptureManager
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import com.zhangxh.subtitletranslator.domain.wordextractor.IWordExtractor
import com.zhangxh.subtitletranslator.util.CropInfo
import com.zhangxh.subtitletranslator.util.DebugCapture
import com.zhangxh.subtitletranslator.util.DebugCaptureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 翻译协调器
 * 整合截图、OCR、翻译、难词提取，提供完整的翻译流程
 */
class TranslationCoordinator(
    private val context: Context,
    private val screenCapture: IScreenCaptureManager,
    private val ocrEngine: IOcrEngine,
    private val translator: ITranslator,
    private val wordExtractor: IWordExtractor,
    private val sourceLang: String = "en",
    private val targetLang: String = "zh",
    /**
     * 是否保存调试截图
     *
     * 用回调而不是直接读设置，是为了让领域层不依赖 UI 层；
     * 每次识别都问一次，所以用户在设置里打开开关后立刻生效，不必重启服务。
     */
    private val isDebugCaptureEnabled: () -> Boolean = { false }
) {

    companion object {
        private const val TAG = "TranslationCoordinator"

        // 横屏字幕区域：屏幕底部区域（70%~100%）
        private const val LANDSCAPE_SUBTITLE_TOP_RATIO = 0.7f
        private const val LANDSCAPE_SUBTITLE_BOTTOM_RATIO = 1f

        // 竖屏字幕区域：基于 16:9 视频比例估算视频画面高度，截取视频下半部分
        // 视频画面高度 = width * 9 / 16，字幕通常在画面下半区
        private const val VIDEO_ASPECT_RATIO_WIDTH = 16f
        private const val VIDEO_ASPECT_RATIO_HEIGHT = 9f

        // OCR 预处理参数
        private const val SCALE_FACTOR = 1.0f      // 放大倍数（临时设为 1.0 测试 OCR 速度）
        private const val BINARY_THRESHOLD = 128   // 二值化固定阈值
    }

    private val debugCaptureStore = DebugCaptureStore(context)

    /**
     * 执行完整的翻译流程
     */
    suspend fun translateSubtitle(): TranslationResult = withContext(Dispatchers.IO) {
        var screenshot: Bitmap? = null
        var subtitleBitmap: Bitmap? = null
        var processedBitmap: Bitmap? = null
        var cropInfo: CropInfo? = null

        // 调试记录需要的信息。失败路径同样要留现场（OCR 失败、未识别到文字时最需要看截图），
        // 所以这些变量声明在 try 外面，在 finally 里统一落盘。
        var rawText = ""
        var cleanedText = ""
        var translatedText = ""
        var errorMessage: String? = null

        /** 记录失败原因后返回失败结果，保证 finally 里的调试记录能带上原因 */
        fun fail(message: String): TranslationResult {
            errorMessage = message
            return TranslationResult(isSuccess = false, errorMessage = message)
        }

        try {
            // 1. 截图
            Log.d(TAG, "开始截图")
            screenshot = screenCapture.captureScreen() ?: return@withContext fail("截图失败")

            // 2. 裁剪字幕区域（底部 1/3）
            val cropped = cropSubtitleArea(screenshot) ?: return@withContext fail("字幕区域裁剪失败")
            subtitleBitmap = cropped.bitmap
            cropInfo = cropped.info

            // 3. 图像预处理：放大 + 灰度化 + 二值化
            processedBitmap = preprocessForOcr(subtitleBitmap)
            Log.d(TAG, "预处理后尺寸: ${processedBitmap.width}x${processedBitmap.height}")

            // 4. OCR 识别
            val ocrResult = ocrEngine.recognizeText(processedBitmap)

            if (!ocrResult.isSuccess) {
                return@withContext fail("OCR 识别失败: ${ocrResult.errorMessage}")
            }

            // 5. 获取字幕文字并清洗
            rawText = ocrResult.text.trim()
            cleanedText = OcrTextCleaner.clean(rawText)

            if (cleanedText.isBlank()) {
                return@withContext fail("未识别到字幕文字")
            }

            Log.d(TAG, "OCR原始结果: $rawText")
            Log.d(TAG, "清洗后字幕: $cleanedText")

            // 6. 翻译
            Log.d(TAG, "开始翻译")
            val translationResult = translator.translate(cleanedText, sourceLang, targetLang)
            translatedText = translationResult.getOrElse {
                return@withContext fail("翻译失败: ${it.message}")
            }

            // 7. 提取难词
            Log.d(TAG, "提取难词")
            val difficultWords = wordExtractor.extractDifficultWords(cleanedText, maxWords = 5)

            TranslationResult(
                originalText = cleanedText,
                translatedText = translatedText,
                difficultWords = difficultWords,
                isSuccess = true
            )

        } catch (e: Exception) {
            Log.e(TAG, "翻译流程失败", e)
            fail("翻译失败: ${e.message}")
        } finally {
            // 落盘必须在 recycle() 之前，否则写入的是已回收的 Bitmap
            if (isDebugCaptureEnabled()) {
                debugCaptureStore.save(
                    DebugCapture(
                        screenshot = screenshot,
                        subtitle = subtitleBitmap,
                        processed = processedBitmap,
                        ocrRawText = rawText,
                        cleanedText = cleanedText,
                        translatedText = translatedText,
                        errorMessage = errorMessage,
                        cropInfo = cropInfo,
                        scaleFactor = SCALE_FACTOR,
                        binaryThreshold = BINARY_THRESHOLD
                    )
                )
            }

            // 确保所有临时 Bitmap 都被回收
            processedBitmap?.recycle()
            subtitleBitmap?.recycle()
            screenshot?.recycle()
        }
    }

    /**
     * 裁剪字幕区域
     *
     * 支持横竖屏自适应：
     * - 横屏（宽 > 高）：字幕通常在视频底部，截取屏幕底部 1/3
     * - 竖屏（高 > 宽）：按 16:9 比例估算视频画面高度，截取视频画面下半部分
     */
    /** 裁剪结果：图像 + 实际使用的参数 */
    private data class CroppedArea(val bitmap: Bitmap, val info: CropInfo)

    private fun cropSubtitleArea(bitmap: Bitmap): CroppedArea? {
        return try {
            val isLandscape = bitmap.width > bitmap.height

            if (isLandscape) {
                // 横屏：截取底部 1/3（约 66%~95%，避开底部导航栏）
                val top = (bitmap.height * LANDSCAPE_SUBTITLE_TOP_RATIO).toInt()
                val bottom = (bitmap.height * LANDSCAPE_SUBTITLE_BOTTOM_RATIO).toInt()
                val height = bottom - top

                if (height <= 0 || top >= bitmap.height) {
                    Log.w(TAG, "横屏字幕区域裁剪参数异常: top=$top, bottom=$bottom, height=${bitmap.height}")
                    return null
                }

                Log.d(TAG, "横屏裁剪字幕区域: top=$top, bottom=$bottom, width=${bitmap.width}, height=$height")
                val info = CropInfo(
                    mode = "横屏",
                    screenWidth = bitmap.width,
                    screenHeight = bitmap.height,
                    top = top,
                    bottom = bottom,
                    width = bitmap.width,
                    height = height
                )
                CroppedArea(Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height), info)
            } else {
                // 竖屏：按 16:9 估算视频画面高度，截取视频下半部分
                // 视频画面宽度填满屏幕，高度 = width * 9 / 16
                val videoHeight = (bitmap.width * VIDEO_ASPECT_RATIO_HEIGHT / VIDEO_ASPECT_RATIO_WIDTH).toInt()
                val top = (videoHeight / 2).coerceAtLeast(0)
                val bottom = videoHeight.coerceAtMost(bitmap.height)
                val height = bottom - top

                if (height <= 0 || top >= bitmap.height) {
                    Log.w(TAG, "竖屏字幕区域裁剪参数异常: top=$top, bottom=$bottom, videoHeight=$videoHeight, bitmapHeight=${bitmap.height}")
                    return null
                }

                Log.d(TAG, "竖屏裁剪字幕区域: top=$top, bottom=$bottom, width=${bitmap.width}, height=$height")
                val info = CropInfo(
                    mode = "竖屏",
                    screenWidth = bitmap.width,
                    screenHeight = bitmap.height,
                    top = top,
                    bottom = bottom,
                    width = bitmap.width,
                    height = height,
                    videoHeight = videoHeight
                )
                CroppedArea(Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height), info)
            }
        } catch (e: Exception) {
            Log.e(TAG, "字幕区域裁剪失败", e)
            null
        }
    }

    /**
     * OCR 图像预处理：放大 + 灰度化 + 二值化
     *
     * 处理流程：
     * 1. 放大 2x：提升字幕文字高度到 ML Kit 最佳识别区间（32~64px）
     * 2. 灰度化：去除颜色干扰
     * 3. 二值化：文字边缘锐化，减少描边/阴影导致的字符粘连
     */
    private fun preprocessForOcr(bitmap: Bitmap): Bitmap {
        // 1. 放大
        val scaledWidth = (bitmap.width * SCALE_FACTOR).toInt()
        val scaledHeight = (bitmap.height * SCALE_FACTOR).toInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        // 2. 创建输出图
        val output = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)

        // 3. 逐像素处理：灰度化 + 二值化
        val pixels = IntArray(scaledWidth * scaledHeight)
        scaled.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)

            // 灰度化
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

            // 二值化：亮度高于阈值设为白色，否则黑色
            // 字幕通常是亮色文字在暗背景上，二值化后边缘会更锐利
            val binary = if (gray > BINARY_THRESHOLD) Color.WHITE else Color.BLACK
            pixels[i] = binary
        }

        output.setPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
        scaled.recycle()

        return output
    }

    /**
     * 预加载翻译环境
     */
    suspend fun prepare() {
        Log.d(TAG, "预加载翻译环境")
        translator.prepare(sourceLang, targetLang)
    }

    /**
     * 释放所有资源
     */
    fun release() {
        Log.d(TAG, "释放资源")
        screenCapture.release()
        ocrEngine.release()
        translator.release()
    }
}
