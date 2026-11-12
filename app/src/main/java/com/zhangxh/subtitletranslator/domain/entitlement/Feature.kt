package com.zhangxh.subtitletranslator.domain.entitlement

/**
 * 需要按账号等级控制的功能
 *
 * 只列出存在等级差异的能力；基础翻译、查词、发音、复习等所有账号都可用的功能
 * 不进入这个枚举，避免把权限判断散落到各处。
 */
enum class Feature(val requiredPlan: Plan) {
    /** 单词本完整管理：筛选、排序、批量清空（游客保留列表、搜索、单条删除） */
    FULL_WORD_LIST_MANAGEMENT(Plan.FREE),

    /** 自定义分类 */
    CUSTOM_CATEGORIES(Plan.FREE),

    /** 学习进度管理：每日目标、学习状态调整 */
    LEARNING_PROGRESS_MANAGEMENT(Plan.FREE),

    /** 基础学习统计 */
    BASIC_STATS(Plan.FREE),

    /** 云端备份与多设备同步 */
    CLOUD_SYNC(Plan.FREE),

    /** AI 字幕识别：云端多模态纠错 */
    AI_SUBTITLE_RECOGNITION(Plan.MEMBER),

    /** AI 翻译：结合上下文的整段精译 */
    AI_TRANSLATION(Plan.MEMBER),

    /** AI 语境分析：单词在当前句中的含义与用法 */
    AI_CONTEXT_ANALYSIS(Plan.MEMBER),

    /** AI 自动发现口语：识别值得收藏的口语、俚语和固定搭配 */
    AI_SPOKEN_DISCOVERY(Plan.MEMBER),

    /** AI 生成练习：基于收藏词生成语境练习 */
    AI_EXERCISE_GENERATION(Plan.MEMBER)
}
