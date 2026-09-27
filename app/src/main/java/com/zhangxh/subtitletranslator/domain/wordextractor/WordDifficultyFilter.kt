package com.zhangxh.subtitletranslator.domain.wordextractor

/**
 * 生词筛选强度
 *
 * 难度分数是连续的（0 最容易，越大越难），但用户不关心具体数值，只关心
 * 「给我的词多一点还是少一点」，所以暴露为三档。
 *
 * [easyThreshold] 是「简单词」的上界：分数低于它的词不会作为生词展示。
 * 档位调宽松，等于把简单词的门槛下调，更多词会被当作生词。
 *
 * 各档取值是对 16 句真实字幕实测后定的（每句最多显示 5 个词）：
 *
 * | 档位 | 分数 | 词/句 | 无词句数 | 说明 |
 * |---|---|---|---|---|
 * | 宽松 | 0.55 | 2.3 | 1/16 | 多给出 consequence / arrest / inherit 这类词 |
 * | 适中 | 1.0 | 1.6 | 3/16 | 只保留更生僻的词 |
 * | 严格 | 3.0 | 0.3 | 12/16 | 只保留 GRE 级别难词 |
 *
 * 0.55 这个切点不是随手取的：难度分在 0.5 和 0.58 附近各聚着一簇词，
 * 0.5 那一簇里有 down 这种柯林斯星级数据偏低的高频词，切在 0.55 正好把它们挡在外面，
 * 同时保留 0.58 那一簇（consequence、career、entire 等确实值得学的词）。
 */
enum class WordDifficultyFilter(
    /** 设置界面展示的名称 */
    val label: String,
    /** 判定为简单词的分数上界 */
    val easyThreshold: Double
) {
    /** 宽松：多显示一些词，适合想多积累词汇的用户 */
    LOOSE("宽松（多显示一些词）", 0.55),

    /** 适中：过滤掉常见基础词 */
    NORMAL("适中", 1.0),

    /** 严格：只保留难度分很高的词 */
    STRICT("严格（只显示难词）", 3.0);

    companion object {
        /** 默认档位：默认偏宽松，避免译文下经常没有词汇可看 */
        val DEFAULT = LOOSE

        /** 按枚举名还原，无法识别时返回 [DEFAULT]（兼容旧版本存的设置） */
        fun fromName(name: String?): WordDifficultyFilter =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
