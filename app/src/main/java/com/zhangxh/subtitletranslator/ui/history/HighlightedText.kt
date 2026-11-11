package com.zhangxh.subtitletranslator.ui.history

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.content.ContextCompat
import com.zhangxh.subtitletranslator.R

/** 把已收藏单词的区间渲染成品牌色高亮，历史列表和首页共用 */
object HighlightedText {

    fun build(context: Context, text: String, ranges: List<IntRange>): CharSequence {
        if (ranges.isEmpty() || text.isEmpty()) return text
        val spannable = SpannableString(text)
        ranges.forEach { range ->
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (start == end) return@forEach
            spannable.setSpan(
                BackgroundColorSpan(ContextCompat.getColor(context, R.color.accent_container)),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, R.color.accent)),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }
}
