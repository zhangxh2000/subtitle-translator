package com.zhangxh.subtitletranslator.domain.history

import java.time.Instant
import java.time.ZoneId

/**
 * 按天聚合的翻译历史
 *
 * 当前产品还拿不到视频标题，所以每天用最后一条字幕原文作为展示文字；
 * 后续接入播放器信息后可以替换成剧集/视频名称。
 */
data class TranslationDayHistory(
    /** 当地时区当天的零点时间戳，作为这一天的唯一键 */
    val dayStart: Long,
    val lastTranslatedAt: Long,
    val subtitleCount: Int,
    val lastSubtitle: String
)

/** 一条具体的字幕识别/翻译记录，用于按天查看详情 */
data class TranslationRecord(
    val id: Long,
    val originalText: String,
    val translatedText: String,
    val translatedAt: Long
)

interface ITranslationHistoryRepository {

    /** 记录一条成功翻译的字幕；空白文本会被忽略 */
    suspend fun record(
        originalText: String,
        translatedText: String = "",
        timestamp: Long = System.currentTimeMillis()
    ): Boolean

    /** 按天倒序返回，最近的一天在最前 */
    suspend fun recentDays(limit: Int = Int.MAX_VALUE): List<TranslationDayHistory>

    /** 某一天的所有记录，时间倒序 */
    suspend fun recordsForDay(dayStart: Long): List<TranslationRecord>

    /** 全部记录，时间倒序 */
    suspend fun allRecords(): List<TranslationRecord>

    suspend fun clear(): Boolean
}

/** 本地时区下某一天的零点；跨时区/夏令时都由 java.time 处理 */
fun startOfDay(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(timestamp)
        .atZone(zoneId)
        .toLocalDate()
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()
