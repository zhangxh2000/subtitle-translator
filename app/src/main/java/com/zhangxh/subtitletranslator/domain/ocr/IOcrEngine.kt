package com.zhangxh.subtitletranslator.domain.ocr

import android.graphics.Bitmap

/**
 * OCR 引擎接口
 */
interface IOcrEngine {
    /**
     * 识别图片中的文字
     * @param bitmap 待识别图片
     * @return 识别结果
     */
    suspend fun recognizeText(bitmap: Bitmap): OcrResult

    /**
     * 释放资源
     */
    fun release()
}

/**
 * OCR 识别结果
 */
data class OcrResult(
    val text: String = "",
    val blocks: List<TextBlock> = emptyList(),
    val isSuccess: Boolean = false,
    val errorMessage: String = ""
) {
    /**
     * 获取字幕区域文字（通常在屏幕底部）
     */
    fun getSubtitleText(screenHeight: Int): String {
        // 打印所有识别到的文字块位置，用于调试
        android.util.Log.d("OcrResult", "屏幕高度: $screenHeight, 识别到 ${blocks.size} 个文字块")
        blocks.forEachIndexed { index, block ->
            val centerY = (block.boundingBox.top + block.boundingBox.bottom) / 2
            val relativeY = centerY.toFloat() / screenHeight
            android.util.Log.d("OcrResult", "文字块[$index]: \"${block.text}\" | 位置: top=${block.boundingBox.top}, bottom=${block.boundingBox.bottom}, centerY=$centerY, relativeY=${String.format("%.2f", relativeY)}")
        }

        // 字幕通常在屏幕底部区域，扩大范围到 55%-98%
        val subtitleBlocks = blocks.filter { block ->
            val centerY = (block.boundingBox.top + block.boundingBox.bottom) / 2
            val relativeY = centerY.toFloat() / screenHeight
            relativeY >= 0.55f && relativeY <= 0.98f
        }

        android.util.Log.d("OcrResult", "筛选后字幕文字块数量: ${subtitleBlocks.size}")

        return subtitleBlocks
            .sortedBy { it.boundingBox.top }
            .joinToString(" ") { it.text }
            .trim()
    }
}

/**
 * 文本块，包含文字和位置信息
 */
data class TextBlock(
    val text: String,
    val boundingBox: BoundingBox,
    val confidence: Float = 1.0f
)

/**
 * 边界框
 */
data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
