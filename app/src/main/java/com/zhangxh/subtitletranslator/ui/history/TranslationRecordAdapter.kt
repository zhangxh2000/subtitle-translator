package com.zhangxh.subtitletranslator.ui.history

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.zhangxh.subtitletranslator.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 翻译记录列表
 *
 * 详情的日期由标题承担；「全部记录」模式下每条记录额外显示日期。
 * 已收藏单词用品牌色高亮；句子被收藏过时右侧书签点亮。
 */
class TranslationRecordAdapter(
    private val showDate: Boolean
) : RecyclerView.Adapter<TranslationRecordAdapter.ViewHolder>() {

    private var items: List<HistoryRecordUi> = emptyList()
    private val dayFormatter = SimpleDateFormat("MM-dd", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submit(data: List<HistoryRecordUi>) {
        items = data
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_translation_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == items.lastIndex)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tvRecordTime)
        private val tvOriginal: TextView = itemView.findViewById(R.id.tvRecordOriginal)
        private val tvTranslation: TextView = itemView.findViewById(R.id.tvRecordTranslation)
        private val ivSaved: ImageView = itemView.findViewById(R.id.ivRecordSaved)
        private val divider: View = itemView.findViewById(R.id.recordDivider)

        fun bind(item: HistoryRecordUi, isLast: Boolean) {
            val context = itemView.context
            val time = timeFormatter.format(Date(item.translatedAt))
            tvTime.text = if (showDate) {
                "${dayFormatter.format(Date(item.translatedAt))} $time"
            } else {
                time
            }
            tvOriginal.text = highlight(item.originalText, item.highlightRanges)
            tvTranslation.isVisible = item.translatedText.isNotBlank()
            tvTranslation.text = item.translatedText

            if (item.collected) {
                ivSaved.setImageResource(R.drawable.ic_bookmark)
                ImageViewCompat.setImageTintList(
                    ivSaved,
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent))
                )
                ivSaved.contentDescription = context.getString(R.string.history_sentence_saved)
            } else {
                ivSaved.setImageResource(R.drawable.ic_bookmark_border)
                ImageViewCompat.setImageTintList(
                    ivSaved,
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_secondary))
                )
                ivSaved.contentDescription =
                    context.getString(R.string.history_sentence_not_saved)
            }
            divider.isVisible = !isLast
        }

        private fun highlight(text: String, ranges: List<IntRange>): CharSequence {
            if (ranges.isEmpty() || text.isEmpty()) return text
            val context = itemView.context
            val spannable = SpannableString(text)
            ranges.forEach { range ->
                val start = range.first.coerceIn(0, text.length)
                val end = (range.last + 1).coerceIn(start, text.length)
                if (start == end) return@forEach
                spannable.setSpan(
                    BackgroundColorSpan(
                        ContextCompat.getColor(context, R.color.accent_container)
                    ),
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
}
