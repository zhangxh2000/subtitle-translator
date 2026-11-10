package com.zhangxh.subtitletranslator.data.history

import android.content.Context
import android.util.Log
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import com.zhangxh.subtitletranslator.domain.history.TranslationDayHistory
import com.zhangxh.subtitletranslator.domain.history.startOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 翻译历史（按天聚合，SharedPreferences 持久化）
 *
 * 每天只保存一条聚合记录：最后一条字幕原文、最后翻译时间、当天字幕条数。
 * 文本量很小，使用 SharedPreferences 比新建一张数据库表更轻；写入串行化，
 * 悬浮窗连续翻译时不会彼此覆盖。
 */
class PrefsTranslationHistoryRepository(
    context: Context,
    private val prefsName: String = PREFS_NAME,
    private val maxDays: Int = MAX_DAYS
) : ITranslationHistoryRepository {

    private companion object {
        const val TAG = "TranslationHistory"
        const val PREFS_NAME = "translation_history"
        const val KEY_DAYS = "days"
        const val MAX_DAYS = 60
        const val MAX_TEXT_LENGTH = 300
    }

    private val appContext = context.applicationContext
    private val writeMutex = Mutex()

    override suspend fun record(originalText: String, timestamp: Long): Boolean =
        withContext(Dispatchers.IO) {
            val text = originalText.trim().take(MAX_TEXT_LENGTH)
            if (text.isBlank()) return@withContext false

            writeMutex.withLock {
                try {
                    val dayStart = startOfDay(timestamp)
                    val days = readDays().toMutableList()
                    val index = days.indexOfFirst { it.dayStart == dayStart }
                    val existing = days.getOrNull(index)

                    val updated = if (existing == null) {
                        TranslationDayHistory(
                            dayStart = dayStart,
                            lastTranslatedAt = timestamp,
                            subtitleCount = 1,
                            lastSubtitle = text
                        )
                    } else if (existing.lastSubtitle == text) {
                        // 同一条字幕重复翻译（如反复点悬浮球）不重复计数
                        existing.copy(lastTranslatedAt = timestamp)
                    } else {
                        existing.copy(
                            lastTranslatedAt = timestamp,
                            subtitleCount = existing.subtitleCount + 1,
                            lastSubtitle = text
                        )
                    }

                    if (index >= 0) {
                        days[index] = updated
                    } else {
                        days.add(updated)
                    }
                    val capped = days.sortedByDescending { it.dayStart }.take(maxDays)
                    writeDays(capped)
                } catch (e: Exception) {
                    Log.e(TAG, "写入翻译历史失败", e)
                    false
                }
            }
        }

    override suspend fun recentDays(limit: Int): List<TranslationDayHistory> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            readDays().take(limit)
        }

    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                prefs().edit().remove(KEY_DAYS).commit()
            } catch (e: Exception) {
                Log.e(TAG, "清空翻译历史失败", e)
                false
            }
        }
    }

    private fun prefs() = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun readDays(): List<TranslationDayHistory> {
        val raw = prefs().getString(KEY_DAYS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val dayStart = item.optLong("dayStart", 0L)
                    val lastSubtitle = item.optString("lastSubtitle")
                    if (dayStart <= 0L || lastSubtitle.isBlank()) continue
                    add(
                        TranslationDayHistory(
                            dayStart = dayStart,
                            lastTranslatedAt = item.optLong("lastTranslatedAt", dayStart),
                            subtitleCount = item.optInt("subtitleCount", 1).coerceAtLeast(1),
                            lastSubtitle = lastSubtitle
                        )
                    )
                }
            }.sortedByDescending { it.dayStart }
        } catch (e: Exception) {
            Log.w(TAG, "翻译历史 JSON 解析失败，按空历史处理", e)
            emptyList()
        }
    }

    private fun writeDays(days: List<TranslationDayHistory>): Boolean {
        val array = JSONArray()
        days.forEach { day ->
            array.put(
                JSONObject().apply {
                    put("dayStart", day.dayStart)
                    put("lastTranslatedAt", day.lastTranslatedAt)
                    put("subtitleCount", day.subtitleCount)
                    put("lastSubtitle", day.lastSubtitle)
                }
            )
        }
        return prefs().edit().putString(KEY_DAYS, array.toString()).commit()
    }
}
