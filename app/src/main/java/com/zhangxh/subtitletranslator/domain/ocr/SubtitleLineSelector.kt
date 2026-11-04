package com.zhangxh.subtitletranslator.domain.ocr

/**
 * 从 OCR 结果里挑出字幕行
 *
 * 字幕导航是在暂停/显示播放控件的画面上识别，底部裁剪区里会混入
 * 播放时间、点赞/评论数、倍速等 UI 文本。字幕通常是裁剪区里最宽的
 * 文本行，UI 文本则很短；先按宽度粗筛，避免控件文字污染相似度比较。
 */
object SubtitleLineSelector {

    /** 行宽达到裁剪区宽度的这个比例才认为是字幕行 */
    const val MIN_WIDTH_RATIO = 0.20f

    fun select(ocrResult: OcrResult, imageWidth: Int): String {
        if (imageWidth <= 0) return ocrResult.text.trim()

        val minWidth = (imageWidth * MIN_WIDTH_RATIO).toInt().coerceAtLeast(1)
        val subtitleLines = ocrResult.blocks
            .filter { it.text.isNotBlank() && it.boundingBox.width >= minWidth }
            .sortedBy { it.boundingBox.top }
            .map { it.text.trim() }

        return if (subtitleLines.isNotEmpty()) {
            subtitleLines.joinToString(" ")
        } else {
            // 极短字幕可能达不到宽度阈值，退回完整结果，避免直接识别为空
            ocrResult.text.trim()
        }
    }
}
