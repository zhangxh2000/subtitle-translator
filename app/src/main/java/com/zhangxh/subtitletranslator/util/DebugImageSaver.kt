package com.zhangxh.subtitletranslator.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试图片保存工具
 * 将截图、裁剪后的字幕区域、预处理后的图片保存到手机本地，便于调试查看
 */
object DebugImageSaver {

    private const val TAG = "DebugImageSaver"
    private const val DEBUG_DIR_NAME = "SubtitleTranslatorDebug"

    // 是否启用调试保存（发布版建议设为 false）
    @Volatile
    var isEnabled: Boolean = true

    /**
     * 保存调试图到公共 Pictures 目录下的 SubtitleTranslatorDebug 文件夹
     * 文件名格式: prefix_YYYYMMDD_HHmmss_SSS.png
     *
     * @param context  Context
     * @param bitmap   要保存的 Bitmap
     * @param prefix   文件名前缀，如 "screenshot", "subtitle", "processed"
     * @return 保存后的文件路径，失败返回 null
     */
    fun saveDebugImage(context: Context, bitmap: Bitmap?, prefix: String): String? {
        if (!isEnabled || bitmap == null || bitmap.isRecycled) {
            return null
        }

        return try {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val debugDir = File(picturesDir, DEBUG_DIR_NAME)
            if (!debugDir.exists()) {
                debugDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val fileName = "${prefix}_${timestamp}.png"
            val file = File(debugDir, fileName)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            // 通知系统扫描新图片，使其立即出现在相册中
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)

            Log.d(TAG, "调试图片已保存: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存调试图片失败", e)
            null
        }
    }

    /**
     * 保存一组调试图（截图、字幕裁剪、预处理结果）
     *
     * @param context         Context
     * @param screenshot      完整截图
     * @param subtitleBitmap  裁剪后的字幕区域
     * @param processedBitmap 预处理后的 OCR 图像
     * @return 各图片的保存路径映射
     */
    fun saveDebugPipeline(
        context: Context,
        screenshot: Bitmap?,
        subtitleBitmap: Bitmap?,
        processedBitmap: Bitmap?
    ): Map<String, String?> {
        val result = mutableMapOf<String, String?>()
        result["screenshot"] = saveDebugImage(context, screenshot, "01_screenshot")
        result["subtitle"] = saveDebugImage(context, subtitleBitmap, "02_subtitle")
        result["processed"] = saveDebugImage(context, processedBitmap, "03_processed")
        return result
    }
}
