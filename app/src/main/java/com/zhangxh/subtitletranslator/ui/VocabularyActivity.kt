package com.zhangxh.subtitletranslator.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.ui.common.labelRes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生词库界面
 * 只负责列表渲染；数据、朗读与增删由 [VocabularyViewModel] 处理。
 */
class VocabularyActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private val viewModel: VocabularyViewModel by viewModels()
    private val adapter = VocabularyAdapter(
        onSpeak = { word -> viewModel.speak(word) },
        onDelete = { word -> viewModel.delete(word) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vocabulary)

        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty = findViewById(R.id.tvEmpty)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btnClear)?.setOnClickListener { viewModel.clear() }

        viewModel.uiState.observe(this) { state ->
            adapter.setData(state.items)
            tvEmpty.visibility = if (state.isEmpty) View.VISIBLE else View.GONE
            recyclerView.visibility = if (state.isEmpty) View.GONE else View.VISIBLE
        }
        viewModel.messages.observe(this) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message.resolve(this), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private class VocabularyAdapter(
        private val onSpeak: (String) -> Unit,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<VocabularyAdapter.ViewHolder>() {

        private var items = listOf<SavedWord>()
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun setData(data: List<SavedWord>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vocabulary, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvDifficulty: TextView = itemView.findViewById(R.id.tvDifficulty)
            private val tvWord: TextView = itemView.findViewById(R.id.tvWord)
            private val tvPhonetic: TextView = itemView.findViewById(R.id.tvPhonetic)
            private val tvMeanings: TextView = itemView.findViewById(R.id.tvMeanings)
            private val tvAddedAt: TextView = itemView.findViewById(R.id.tvAddedAt)
            private val btnPronounce: ImageButton = itemView.findViewById(R.id.btnPronounce)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

            fun bind(item: SavedWord) {
                tvWord.text = item.word
                tvPhonetic.text = item.phonetic

                // 释义：词性用强调色，释义本身保持中性色
                val meaningsText = SpannableStringBuilder()
                item.meanings.forEachIndexed { index, meaning ->
                    if (index > 0) meaningsText.append("\n")
                    if (meaning.partOfSpeech.isNotEmpty()) {
                        val start = meaningsText.length
                        meaningsText.append("[${meaning.partOfSpeech}] ")
                        meaningsText.setSpan(
                            ForegroundColorSpan(
                                tvMeanings.context.getColor(R.color.accent)
                            ),
                            start,
                            meaningsText.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    meaningsText.append(meaning.chineseDefinition)
                }
                tvMeanings.text = meaningsText
                tvMeanings.visibility = if (meaningsText.isEmpty()) View.GONE else View.VISIBLE

                // 难度标签：低饱和底色 + 同色系文字
                val difficulty = item.difficulty
                val (backgroundRes, textRes) = when (difficulty) {
                    WordDifficulty.HARD ->
                        R.color.difficulty_hard_bg to R.color.difficulty_hard_text
                    WordDifficulty.MEDIUM ->
                        R.color.difficulty_medium_bg to R.color.difficulty_medium_text
                    else ->
                        R.color.difficulty_easy_bg to R.color.difficulty_easy_text
                }
                tvDifficulty.text = tvDifficulty.context.getString(difficulty.labelRes())
                tvDifficulty.backgroundTintList = ColorStateList.valueOf(
                    tvDifficulty.context.getColor(backgroundRes)
                )
                tvDifficulty.setTextColor(tvDifficulty.context.getColor(textRes))

                tvAddedAt.text = tvAddedAt.context.getString(
                    R.string.vocabulary_added_at,
                    dateFormat.format(Date(item.createdAt))
                )

                btnPronounce.setOnClickListener { onSpeak(item.word) }
                btnDelete.setOnClickListener { onDelete(item.word) }
            }
        }
    }
}
