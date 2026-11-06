package com.zhangxh.subtitletranslator.domain.ocr

/**
 * 从 OCR 结果里挑出字幕行
 *
 * 字幕导航是在暂停/显示播放控件的画面上识别，底部裁剪区里会混入
 * 播放时间、点赞/评论数、倍速等 UI 文本。字幕通常包含文字且较宽，
 * UI 数字行只有数字；先按内容筛掉纯数字行，再按宽度粗筛。
 *
 * 注意：所有行都达不到宽度阈值时，不能退回整段 OCR 文本——那会把
 * 播放控件的数字一起带进来，污染后续的同句判断。此时只退回包含
 * 文字的行；只有整屏都是数字（例如数字字幕）才保留完整结果。
 */
object SubtitleLineSelector {

    /** 行宽达到裁剪区宽度的这个比例才认为是字幕行 */
    const val MIN_WIDTH_RATIO = 0.20f

    fun select(ocrResult: OcrResult, imageWidth: Int): String {
        if (imageWidth <= 0) return ocrResult.text.trim()

        val lines = ocrResult.blocks.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return ocrResult.text.trim()

        // 纯时间/点赞数等 UI 行不含字母（汉字也算 Letter），优先排除
        val textLines = lines.filter { line -> line.text.any { it.isLetter() } }
        val candidates = textLines.ifEmpty { lines }

        val minWidth = (imageWidth * MIN_WIDTH_RATIO).toInt().coerceAtLeast(1)
        val wideLines = candidates.filter { it.boundingBox.width >= minWidth }
        val selected = wideLines.ifEmpty { candidates }

        return selected
            .sortedBy { it.boundingBox.top }
            .joinToString(" ") { it.text.trim() }
            .trim()
            .ifEmpty { ocrResult.text.trim() }
    }
}
