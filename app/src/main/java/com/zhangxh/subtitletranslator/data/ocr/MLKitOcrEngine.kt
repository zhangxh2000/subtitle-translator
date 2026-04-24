package com.zhangxh.subtitletranslator.data.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.zhangxh.subtitletranslator.domain.ocr.BoundingBox
import com.zhangxh.subtitletranslator.domain.ocr.IOcrEngine
import com.zhangxh.subtitletranslator.domain.ocr.OcrResult
import com.zhangxh.subtitletranslator.domain.ocr.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * ML Kit 文字识别实现
 * 支持离线识别
 */
class MLKitOcrEngine : IOcrEngine {

    companion object {
        private const val TAG = "MLKitOcrEngine"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognizeText(bitmap: Bitmap): OcrResult =
        withContext(Dispatchers.IO) {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val visionText = recognizer.process(image).await()

                val blocks = visionText.textBlocks.flatMap { block ->
                    block.lines.map { line ->
                        TextBlock(
                            text = line.text,
                            boundingBox = line.boundingBox?.let { rect ->
                                BoundingBox(
                                    left = rect.left,
                                    top = rect.top,
                                    right = rect.right,
                                    bottom = rect.bottom
                                )
                            } ?: BoundingBox(0, 0, 0, 0),
                            confidence = line.confidence
                        )
                    }
                }

                OcrResult(
                    text = visionText.text,
                    blocks = blocks,
                    isSuccess = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "OCR 识别失败", e)
                OcrResult(
                    isSuccess = false,
                    errorMessage = e.message ?: "未知错误"
                )
            }
        }

    override fun release() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            Log.e(TAG, "释放 OCR 资源失败", e)
        }
    }
}