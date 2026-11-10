package com.zhangxh.subtitletranslator.ui.history

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.history.TranslationDayHistory
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 首页「最近翻译」与完整历史页共用同一行布局与日期格式 */
object TranslationHistoryRow {

    fun bind(
        view: View,
        item: TranslationDayHistory,
        showChevron: Boolean,
        showDivider: Boolean,
        onClick: (() -> Unit)? = null
    ) {
        view.findViewById<TextView>(R.id.tvHistoryTitle).text = item.lastSubtitle
        view.findViewById<TextView>(R.id.tvHistoryMeta).text =
            formatHistoryMeta(view.context, item)
        view.findViewById<ImageView>(R.id.ivHistoryChevron).isVisible = showChevron
        view.findViewById<View>(R.id.historyDivider).isVisible = showDivider
        view.isClickable = onClick != null
        view.setOnClickListener(onClick?.let { action -> View.OnClickListener { action() } })
    }

    /** 详情页标题里的日期：今天用「今天」，其他日期用 MM-dd */
    fun formatDayLabel(
        context: Context,
        dayStart: Long,
        now: Long = System.currentTimeMillis()
    ): String = if (isSameDay(dayStart, now)) {
        context.getString(R.string.history_today_label)
    } else {
        SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(dayStart))
    }

    /** 某一天的详情标题：今天/日期 + 翻译次数 */
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

    fun formatHistoryMeta(
        context: Context,
        item: TranslationDayHistory,
        now: Long = System.currentTimeMillis()
    ): String {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(Date(item.lastTranslatedAt))
        val dateLabel = when {
            isSameDay(item.dayStart, now) ->
                context.getString(R.string.history_today_at, time)
            isSameDay(item.dayStart, yesterday(now)) ->
                context.getString(R.string.history_yesterday_at, time)
            else -> context.getString(
                R.string.history_date_at,
                SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(item.dayStart)),
                time
            )
        }
        val count = context.resources.getQuantityString(
            R.plurals.history_subtitle_count,
            item.subtitleCount,
            item.subtitleCount
        )
        return context.getString(R.string.history_meta, dateLabel, count)
    }

    private fun isSameDay(dayStart: Long, timestamp: Long): Boolean {
        val left = Calendar.getInstance().apply { timeInMillis = dayStart }
        val right = Calendar.getInstance().apply { timeInMillis = timestamp }
        return left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
            left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)
    }

    private fun yesterday(now: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
}
