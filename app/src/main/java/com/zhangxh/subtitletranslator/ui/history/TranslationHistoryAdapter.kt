package com.zhangxh.subtitletranslator.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.history.TranslationDayHistory

class TranslationHistoryAdapter :
    RecyclerView.Adapter<TranslationHistoryAdapter.ViewHolder>() {

    private var items: List<TranslationDayHistory> = emptyList()

    fun submit(data: List<TranslationDayHistory>) {
        items = data
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_translation_day, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == items.lastIndex)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(private val view: android.view.View) : RecyclerView.ViewHolder(view) {
        fun bind(item: TranslationDayHistory, isLast: Boolean) {
            TranslationHistoryRow.bind(
                view = view,
                item = item,
                showChevron = false,
                showDivider = !isLast
            )
        }
    }
}
