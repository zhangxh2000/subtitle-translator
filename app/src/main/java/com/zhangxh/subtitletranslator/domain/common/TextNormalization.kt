package com.zhangxh.subtitletranslator.domain.common

import java.util.Locale

/**
 * 句子归一化：去首尾空白、合并连续空白、统一小写。
 *
 * 收藏去重和历史页「这句话是否已收藏」必须使用同一套规则，
 * 否则同一句字幕会因为换行或大小写差异被当成两句话。
 */
fun normalizeSentence(text: String): String =
    text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
