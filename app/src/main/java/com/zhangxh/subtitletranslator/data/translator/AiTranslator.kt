package com.zhangxh.subtitletranslator.data.translator

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.remote.AiImageTranslationRequestDto
import com.zhangxh.subtitletranslator.data.remote.AiImageTranslationResponseDto
import com.zhangxh.subtitletranslator.data.remote.AiTranslatedSubtitleSegmentDto
import com.zhangxh.subtitletranslator.data.remote.AiTranslationApi
import com.zhangxh.subtitletranslator.data.remote.AiTranslationContextDto
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.error.AppError
import com.zhangxh.subtitletranslator.domain.error.TranslationError
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.translator.AiSubtitleTranslation
import com.zhangxh.subtitletranslator.domain.translator.IAiTranslator
import com.zhangxh.subtitletranslator.domain.translator.TranslationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 后端会员 AI 图片字幕翻译（POST /api/v1/translations/ai/image）
 *
 * 与基础翻译器分开：上传裁剪后的字幕区域图片，由服务端多模态模型同时完成
 * OCR 与翻译，返回的原文与译文一起用于刷新面板。失败时不回退、不覆盖现有译文。
 * 上下文只携带最近已展示的字幕对，并按服务端限制裁剪；
 * 游客直接失败，不发起注定 401 的请求。
 */
class AiTranslator(
    private val api: AiTranslationApi,
    private val sessionManager: AuthSessionManager,
    private val gson: Gson = Gson()
) : IAiTranslator {

    internal companion object {
        private const val TAG = "AiTranslator"

        /** 服务端限制：上下文最多 4 组、每组每字段 500 字符、总长 1000 字符 */
        const val MAX_CONTEXT_PAIRS = 4
        const val MAX_CONTEXT_FIELD_CHARS = 500
        const val MAX_CONTEXT_TOTAL_CHARS = 1000

        /** 服务端限制：图片长边 ≤ 2560 px、≤ 5 MB；JPEG 质量固定，足够保留字幕细节 */
        const val MAX_IMAGE_EDGE = 2560
        const val JPEG_QUALITY = 88

        private const val IMAGE_PART_NAME = "image"
        private const val IMAGE_FILE_NAME = "subtitle.jpg"

        private val JSON_MEDIA_TYPE = MediaType.get("application/json")
        private val JPEG_MEDIA_TYPE = MediaType.get("image/jpeg")

        /**
         * 裁剪上下文
         *
         * 丢弃空白字幕对，单项截断到 500 字符，最多保留最近 4 组；
         * 再按总长 1000 字符从旧到新丢弃，保证最新的一句一定优先保留。
         */
        fun sanitizeContext(context: List<TranslationContext>): List<TranslationContext> {
            val valid = context.mapNotNull { pair ->
                val source = pair.source.trim().take(MAX_CONTEXT_FIELD_CHARS)
                val translation = pair.translation.trim().take(MAX_CONTEXT_FIELD_CHARS)
                if (source.isEmpty() || translation.isEmpty()) {
                    null
                } else {
                    TranslationContext(source, translation)
                }
            }.takeLast(MAX_CONTEXT_PAIRS)

            val kept = ArrayDeque<TranslationContext>()
            var totalChars = 0
            for (pair in valid.asReversed()) {
                val chars = pair.source.length + pair.translation.length
                // 超出预算就停止：跳过中间一句再保留更早的上下文会破坏对话连贯性
                if (totalChars + chars > MAX_CONTEXT_TOTAL_CHARS) break
                kept.addFirst(pair)
                totalChars += chars
            }
            return kept.toList()
        }

        /**
         * 把字幕区域图片编码成服务端接受的 JPEG
         *
         * 长边超过 2560 px 时等比缩小；只读取原图，不负责回收。
         * 编码失败或尺寸异常时返回 null。
         */
        fun encodeSubtitleImage(image: Bitmap): ByteArray? {
            if (image.isRecycled || image.width <= 0 || image.height <= 0) return null

            val longEdge = maxOf(image.width, image.height)
            val scale = MAX_IMAGE_EDGE.toFloat() / longEdge
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    image,
                    (image.width * scale).toInt().coerceAtLeast(1),
                    (image.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                image
            }

            return try {
                ByteArrayOutputStream().use { output ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        null
                    } else {
                        output.toByteArray()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "字幕截图编码失败: ${image.width}x${image.height}", e)
                null
            } finally {
                if (scaled !== image) scaled.recycle()
            }
        }
    }

    /** 一次观看会话的标识，服务端用于日志与链路追踪 */
    private val sessionId = UUID.randomUUID().toString()

    /** 同一会话内递增 */
    private val sequence = AtomicLong(0)

    override suspend fun translate(
        image: Bitmap,
        from: String,
        to: String,
        context: List<TranslationContext>
    ): Result<AiSubtitleTranslation> = withContext(Dispatchers.IO) {
        if (sessionManager.state.value !is AuthState.LoggedIn) {
            return@withContext failure(AppError.TRANSLATION_LOGIN_REQUIRED)
        }

        val source = BackendTranslator.serverLanguage(from)
        val target = BackendTranslator.serverLanguage(to)
        if (source == null || target == null) {
            return@withContext failure(
                AppError.UNSUPPORTED_LANGUAGE_PAIR,
                "$from → $to"
            )
        }
        // 截图中可能同时出现多种语言，服务端要求显式指定具体源语言
        if (source == "auto") {
            return@withContext failure(
                AppError.SOURCE_LANGUAGE_REQUIRED,
                "image translation requires a concrete source language"
            )
        }

        val imageBytes = encodeSubtitleImage(image)
            ?: return@withContext failure(AppError.INVALID_IMAGE)

        val request = AiImageTranslationRequestDto(
            sessionId = sessionId,
            sequence = sequence.incrementAndGet(),
            sourceLanguage = source,
            targetLanguage = target,
            context = sanitizeContext(context)
                .takeIf { it.isNotEmpty() }
                ?.map { AiTranslationContextDto(it.source, it.translation) }
        )

        Log.d(
            TAG,
            "AI 图片翻译请求: ${image.width}x${image.height}, " +
                "${imageBytes.size / 1024}KB, context=${request.context?.size ?: 0}, " +
                "$source → $target"
        )
        val startedNs = System.nanoTime()
        try {
            val response = api.translate(
                request = RequestBody.create(JSON_MEDIA_TYPE, gson.toJson(request)),
                image = MultipartBody.Part.createFormData(
                    IMAGE_PART_NAME,
                    IMAGE_FILE_NAME,
                    RequestBody.create(JPEG_MEDIA_TYPE, imageBytes)
                )
            )
            val result = parseResponse(response.data)
            result.onSuccess {
                Log.d(
                    TAG,
                    "AI 图片翻译成功: 原文 ${it.sourceText.length} 字, " +
                        "译文 ${it.translatedText.length} 字, " +
                        "${(System.nanoTime() - startedNs) / 1_000_000}ms"
                )
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            // 错误体只能读一次，先映射再记录，避免日志把后续解析读空
            val error = e.toTranslationError(membershipAware = true)
            Log.w(TAG, "AI 图片翻译失败: HTTP ${e.code()} (${error.detail})")
            Result.failure(TranslationException(error, e))
        } catch (e: IOException) {
            Log.w(TAG, "AI 图片翻译网络异常: ${e.javaClass.simpleName}")
            Result.failure(
                TranslationException(TranslationError(AppError.NETWORK_ERROR), e)
            )
        } catch (e: Exception) {
            Log.e(TAG, "AI 图片翻译异常", e)
            Result.failure(
                TranslationException(
                    TranslationError(AppError.TRANSLATION_FAILED, e.message),
                    e
                )
            )
        }
    }

    /**
     * 合并服务端返回的逐行字幕
     *
     * 同一画面可能有两行字幕，按返回顺序拼成一段，与本地 OCR 的合并方式一致。
     * 缺少原文或译文都视为无效响应，避免把半截结果写进面板。
     */
    private fun parseResponse(
        data: AiImageTranslationResponseDto?
    ): Result<AiSubtitleTranslation> {
        val segments = data?.segments ?: return failure(AppError.INVALID_RESPONSE)
        if (segments.isEmpty()) return failure(AppError.EMPTY_TRANSLATION)

        val pairs = segments.map { segment -> splitSegment(segment) }
        if (pairs.any { it == null }) return failure(AppError.INVALID_RESPONSE)

        val valid = pairs.filterNotNull()
        val sourceText = valid.joinToString(" ") { it.first }.trim()
        val translatedText = valid.joinToString(" ") { it.second }.trim()
        if (sourceText.isEmpty() || translatedText.isEmpty()) {
            return failure(AppError.EMPTY_TRANSLATION)
        }
        return Result.success(AiSubtitleTranslation(sourceText, translatedText))
    }

    private fun splitSegment(segment: AiTranslatedSubtitleSegmentDto): Pair<String, String>? {
        val source = segment.source?.trim().orEmpty()
        val translation = segment.translation?.trim().orEmpty()
        return if (source.isEmpty() || translation.isEmpty()) null else source to translation
    }

    private fun <T> failure(code: AppError, detail: String? = null): Result<T> =
        Result.failure(TranslationException(TranslationError(code, detail)))
}
