package com.zhangxh.subtitletranslator.util

import android.graphics.Bitmap

/**
 * 一次字幕识别的现场记录（待保存）
 *
 * 三张图对应识别流程的三个阶段，便于判断问题出在哪一环：
 * 截图本身有问题 / 裁剪区域切错了 / 预处理把文字破坏了 / OCR 算法不准。
 */
data class DebugCapture(
    /** 原始截图 */
    val screenshot: Bitmap?,
    /** 裁剪出的字幕区域 */
    val subtitle: Bitmap?,
    /** 预处理后喂给 OCR 的图（灰度 + 二值化） */
    val processed: Bitmap?,
    /** OCR 原始输出 */
    val ocrRawText: String,
    /** 清洗后的字幕文本（实际送去翻译和提取生词的文本） */
    val cleanedText: String,
    /** 译文 */
    val translatedText: String,
    /** 失败时的错误信息 */
    val errorMessage: String?,
    /** 裁剪参数 */
    val cropInfo: CropInfo?,
    /** 预处理时的放大倍数（当前为 1.0，即关闭放大） */
    val scaleFactor: Float,
    /** 预处理方式（OcrPreprocessMode 的枚举名），便于对比哪次记录用的哪种方式 */
    val preprocessMode: String
)

/**
 * 字幕区域裁剪参数
 *
 * 裁剪逻辑分横屏/竖屏两条分支，[mode] 记录走的哪条 —— 判断"切对没切对"时这是关键信息。
 */
data class CropInfo(
    /** "横屏" 或 "竖屏" */
    val mode: String,
    val screenWidth: Int,
    val screenHeight: Int,
    /** 裁剪区域在原图中的纵向范围 */
    val top: Int,
    val bottom: Int,
    val width: Int,
    val height: Int,
    /** 竖屏下按 16:9 估算出的视频画面高度；横屏无此概念，为 0 */
    val videoHeight: Int = 0
)

/**
 * 界面上展示的一条记录（由 [DebugCaptureStore] 从磁盘读回）
 */
data class CaptureRecord(
    /** 会话目录名，也是时间戳字符串 */
    val id: String,
    val timestamp: Long,
    val cropInfo: CropInfo?,
    val scaleFactor: Float,
    val preprocessMode: String,
    val ocrRawText: String,
    val cleanedText: String,
    val translatedText: String,
    val errorMessage: String?,
    /** 实际存在的图片文件名，按 截图 / 裁剪 / 预处理 的顺序 */
    val imageFiles: List<String>
) {
    val isFailed: Boolean get() = !errorMessage.isNullOrBlank()
}
