package com.zhangxh.subtitletranslator.domain.vocabulary

import java.util.Locale

/** 生词的学习状态；持久化使用枚举名 */
enum class LearningStatus {
    NEW,
    LEARNING,
    REVIEWING,
    MASTERED,
    ARCHIVED;

    companion object {
        fun fromName(name: String?): LearningStatus =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NEW
    }
}

/** 词条类型；单词和短语共用收藏表，为后续短语/同步预留 */
enum class VocabularyItemType {
    WORD,
    PHRASE;

    companion object {
        fun fromName(name: String?): VocabularyItemType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: WORD

        fun infer(term: String): VocabularyItemType =
            if (term.trim().contains(Regex("\\s+"))) PHRASE else WORD
    }
}

/**
 * 收藏时的字幕上下文
 *
 * 这是本产品区别于普通词典的关键信息：用户能看到这个词在刚才的视频里
 * 是如何使用的，而不只是一个孤立的释义。
 */
data class SavedWordContext(
    val source: String? = null,
    val translation: String? = null
) {
    fun isValid(): Boolean =
        !source.isNullOrBlank() || !translation.isNullOrBlank()
}

/** 学习中心/首页需要的本地统计 */
data class LearningStats(
    val total: Int = 0,
    val wordCount: Int = 0,
    val phraseCount: Int = 0,
    val due: Int = 0,
    val mastered: Int = 0,
    val reviewedToday: Int = 0,
    val dailyGoal: Int = 10
) {
    val progress: Float
        get() = if (dailyGoal <= 0) 0f else (reviewedToday.toFloat() / dailyGoal).coerceIn(0f, 1f)

    val progressPercent: Int
        get() = (progress * 100).toInt()

    companion object {
        fun from(words: List<SavedWord>, now: Long = System.currentTimeMillis()): LearningStats {
            val startOfToday = startOfTodayMillis(now)
            return LearningStats(
                total = words.size,
                wordCount = words.count { it.itemType == VocabularyItemType.WORD },
                phraseCount = words.count { it.itemType == VocabularyItemType.PHRASE },
                due = words.count { it.learningStatus != LearningStatus.MASTERED },
                mastered = words.count { it.learningStatus == LearningStatus.MASTERED },
                reviewedToday = words.count { (it.lastReviewedAt ?: 0L) >= startOfToday }
            )
        }

        private fun startOfTodayMillis(now: Long): Long {
            // 用本地时区的零点；只依赖 JVM 时间 API，测试可传入固定 now
            val zone = java.util.TimeZone.getDefault().toZoneId()
            val date = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return date.atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }
}

/** 学习状态的中文/英文持久化兼容：数据库用枚举名，展示由 TextMappings 映射 */
fun LearningStatus.storageName(): String = name.uppercase(Locale.ROOT)
