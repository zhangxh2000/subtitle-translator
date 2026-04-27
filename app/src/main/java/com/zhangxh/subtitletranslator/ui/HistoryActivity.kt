package com.zhangxh.subtitletranslator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.TranslationResult
import com.zhangxh.subtitletranslator.service.FloatingButtonService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 翻译历史记录界面
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private val adapter = HistoryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty = findViewById(R.id.tvEmpty)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btnClear)?.setOnClickListener {
            FloatingButtonService.clearTranslationHistory()
            loadHistory()
            Toast.makeText(this, "历史记录已清空", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            finish()
        }

        loadHistory()
    }

    private fun loadHistory() {
        val history = FloatingButtonService.getTranslationHistory().reversed()
        adapter.setData(history)
        tvEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        private var items = listOf<TranslationResult>()
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun setData(data: List<TranslationResult>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            private val tvOriginal: TextView = itemView.findViewById(R.id.tvOriginal)
            private val tvTranslated: TextView = itemView.findViewById(R.id.tvTranslated)

            fun bind(item: TranslationResult) {
                tvTime.text = dateFormat.format(Date(item.timestamp))
                tvOriginal.text = item.originalText
                tvTranslated.text = item.translatedText
            }
        }
    }
}
