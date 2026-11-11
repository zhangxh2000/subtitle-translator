package com.zhangxh.subtitletranslator.ui.history

import android.content.Context
import com.zhangxh.subtitletranslator.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 历史详情标题的日期与次数格式 */
object TranslationHistoryFormats {

    fun formatDayLabel(
        context: Context,
        dayStart: Long,
        now: Long = System.currentTimeMillis()
    ): String = if (isSameDay(dayStart, now)) {
        context.getString(R.string.history_today_label)
    } else {
        SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(dayStart))
    }

    fun formatDetailTitle(
        context: Context,
        dayStart: Long,
        count: Int,
        now: Long = System.currentTimeMillis()
    ): String = if (isSameDay(dayStart, now)) {
        context.resources.getQuantityString(
            R.plurals.history_detail_title_today,
            count,
            count
        )
    } else {
        context.resources.getQuantityString(
            R.plurals.history_detail_title_date,
            count,
            formatDayLabel(context, dayStart, now),
            count
        )
    }

    private fun isSameDay(dayStart: Long, timestamp: Long): Boolean {
        val left = Calendar.getInstance().apply { timeInMillis = dayStart }
        val right = Calendar.getInstance().apply { timeInMillis = timestamp }
        return left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
            left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)
    }
}
