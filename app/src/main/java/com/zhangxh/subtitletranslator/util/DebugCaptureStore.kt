package com.zhangxh.subtitletranslator.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试截图存储
 *
 * 每次识别存成一个以时间戳命名的会话目录，目录名形如 `20260927_143012_123`，
 * 字典序即时间序，排序和挑最旧的都不用解析时间。
 *
 * ```
 * filesDir/debug_captures/
 *   └── 20260927_143012_123/
 *       ├── 1_screenshot.jpg   原始截图（JPEG，原图较大）
 *       ├── 2_subtitle.png     裁剪出的字幕区域（无损，用于判断裁剪位置）
 *       ├── 3_processed.png    预处理后喂给 OCR 的图（二值化后压缩率很高）
 *       └── meta.json          OCR 文本、裁剪参数、失败原因等
 * ```
 *
 * 存在应用私有目录，不需要任何存储权限，也不会出现在系统相册里
 * （字幕截图可能包含聊天记录等隐私内容）。需要导出时走 FileProvider 分享。
 *
 * 只保留最近 [MAX_CAPTURES] 次，超出自动删除最旧的会话目录。
 */
class DebugCaptureStore(private val context: Context) {

    companion object {
        private const val TAG = "DebugCaptureStore"
        private const val DIR_NAME = "debug_captures"

        /** 最多保留几次识别记录；每次约 1~1.5MB */
        const val MAX_CAPTURES = 5

        /** 三个阶段的图片文件名，顺序即界面展示顺序 */
        const val FILE_SCREENSHOT = "1_screenshot.jpg"
        const val FILE_SUBTITLE = "2_subtitle.png"
        const val FILE_PROCESSED = "3_processed.png"
        private const val FILE_META = "meta.json"

        /** 会话名 / 展示用的时间格式，同时也是目录名 */
        private const val SESSION_PATTERN = "yyyyMMdd_HHmmss_SSS"

        private const val JPEG_QUALITY = 90
    }

    private val rootDir: File get() = File(context.filesDir, DIR_NAME)

    /**
     * 保存一次识别记录
     *
     * 三张图任一为 null（例如流程提前失败）时对应文件会跳过，其余照常保存 ——
     * 失败现场恰恰最需要看，所以不做"要么全存要么全不存"的限制。
     *
     * @return 是否成功写入
     */
    suspend fun save(capture: DebugCapture): Boolean = withContext(Dispatchers.IO) {
        try {
            val sessionDir = createSessionDir()
            val id = sessionDir.name

            capture.screenshot?.writeTo(File(sessionDir, FILE_SCREENSHOT), Bitmap.CompressFormat.JPEG, JPEG_QUALITY)
            capture.subtitle?.writeTo(File(sessionDir, FILE_SUBTITLE), Bitmap.CompressFormat.PNG, 100)
            capture.processed?.writeTo(File(sessionDir, FILE_PROCESSED), Bitmap.CompressFormat.PNG, 100)

            File(sessionDir, FILE_META).writeText(buildMeta(id, capture))

            cleanup()
            Log.d(TAG, "调试记录已保存: $id")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存调试记录失败", e)
            false
        }
    }

    /**
     * 列出最近的识别记录，最新的在前
     */
    suspend fun listCaptures(): List<CaptureRecord> = withContext(Dispatchers.IO) {
        sessionDirs().take(MAX_CAPTURES).mapNotNull { readRecord(it) }
    }

    /**
     * 读取会话中的图片，按 [maxWidthPx] 采样解码避免 OOM
     */
    suspend fun loadImage(sessionId: String, fileName: String, maxWidthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = sessionFile(sessionId, fileName)
            if (!file.exists()) return@withContext null
            try {
                decodeSampled(file, maxWidthPx)
            } catch (e: Exception) {
                Log.e(TAG, "读取调试图片失败: ${file.absolutePath}", e)
                null
            }
        }

    /** 会话中的文件，供分享时构造 FileProvider Uri */
    fun sessionFile(sessionId: String, fileName: String): File =
        File(File(rootDir, sessionId), fileName)

    /** 删除全部记录 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        rootDir.deleteRecursively()
        Unit
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    /**
     * 创建本次会话的目录
     *
     * 目录名是毫秒级时间戳，正常不会重复；万一同一毫秒内保存两次（例如测试中连续调用），
     * 加后缀避免两次记录挤进同一个目录。加后缀后字典序仍与时间序一致。
     */
    private fun createSessionDir(): File {
        val base = SimpleDateFormat(SESSION_PATTERN, Locale.US).format(Date())
        var dir = File(rootDir, base)
        var suffix = 1
        while (dir.exists()) {
            dir = File(rootDir, "${base}_$suffix")
            suffix++
        }
        dir.mkdirs()
        return dir
    }

    /** 会话目录，最新的在前 */
    private fun sessionDirs(): List<File> =
        rootDir.listFiles { file -> file.isDirectory }
            ?.sortedByDescending { it.name }
            .orEmpty()

    /** 只保留最近的若干个会话 */
    private fun cleanup() {
        sessionDirs().drop(MAX_CAPTURES).forEach { stale ->
            if (stale.deleteRecursively()) {
                Log.d(TAG, "已删除过旧的调试记录: ${stale.name}")
            }
        }
    }

    private fun buildMeta(id: String, capture: DebugCapture): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("timestamp", System.currentTimeMillis())
        json.put("scaleFactor", capture.scaleFactor.toDouble())
        json.put("binaryThreshold", capture.binaryThreshold)
        capture.cropInfo?.let { crop ->
            json.put("mode", crop.mode)
            json.put("screenWidth", crop.screenWidth)
            json.put("screenHeight", crop.screenHeight)
            json.put("top", crop.top)
            json.put("bottom", crop.bottom)
            json.put("width", crop.width)
            json.put("height", crop.height)
            json.put("videoHeight", crop.videoHeight)
        }
        json.put("ocrRawText", capture.ocrRawText)
        json.put("cleanedText", capture.cleanedText)
        json.put("translatedText", capture.translatedText)
        json.put("errorMessage", capture.errorMessage.orEmpty())
        return json.toString(2)
    }

    private fun readRecord(sessionDir: File): CaptureRecord? {
        val metaFile = File(sessionDir, FILE_META)
        if (!metaFile.exists()) return null

        return try {
            val json = JSONObject(metaFile.readText())
            CaptureRecord(
                id = sessionDir.name,
                timestamp = json.optLong("timestamp", sessionDir.lastModified()),
                cropInfo = if (json.has("mode")) {
                    CropInfo(
                        mode = json.getString("mode"),
                        screenWidth = json.optInt("screenWidth"),
                        screenHeight = json.optInt("screenHeight"),
                        top = json.optInt("top"),
                        bottom = json.optInt("bottom"),
                        width = json.optInt("width"),
                        height = json.optInt("height"),
                        videoHeight = json.optInt("videoHeight")
                    )
                } else null,
                scaleFactor = json.optDouble("scaleFactor", 1.0).toFloat(),
                binaryThreshold = json.optInt("binaryThreshold", 0),
                ocrRawText = json.optString("ocrRawText"),
                cleanedText = json.optString("cleanedText"),
                translatedText = json.optString("translatedText"),
                errorMessage = json.optString("errorMessage").ifBlank { null },
                imageFiles = listOf(FILE_SCREENSHOT, FILE_SUBTITLE, FILE_PROCESSED)
                    .filter { File(sessionDir, it).exists() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取调试记录失败: ${sessionDir.name}", e)
            null
        }
    }

    /** 跳过已回收的 Bitmap，避免写文件时抛异常 */
    private fun Bitmap.writeTo(file: File, format: Bitmap.CompressFormat, quality: Int) {
        if (isRecycled) return
        try {
            FileOutputStream(file).use { out -> compress(format, quality, out) }
        } catch (e: Exception) {
            Log.e(TAG, "写入调试图片失败: ${file.name}", e)
        }
    }

    /** 按目标宽度计算采样率，只做 2 的幂次降采样 */
    private fun decodeSampled(file: File, maxWidthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        if (maxWidthPx > 0) {
            while (bounds.outWidth / sampleSize > maxWidthPx) {
                sampleSize *= 2
            }
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }
}
