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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.speech.AndroidTtsPronouncer
import com.zhangxh.subtitletranslator.data.vocabulary.SqliteVocabularyRepository
import com.zhangxh.subtitletranslator.domain.speech.PronouncerError
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生词库界面
 *
 * 列出在翻译结果里收藏的生词，支持朗读、单条删除和清空。
 */
class VocabularyActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private val adapter = VocabularyAdapter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: SqliteVocabularyRepository
    private lateinit var pronouncer: AndroidTtsPronouncer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vocabulary)

        repository = SqliteVocabularyRepository(this)
        pronouncer = AndroidTtsPronouncer(this) { error ->
            val message = when (error) {
                PronouncerError.ENGINE_UNAVAILABLE -> "语音引擎不可用"
                PronouncerError.ENGLISH_VOICE_UNAVAILABLE -> "缺少英语语音数据，请在系统设置中下载"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty = findViewById(R.id.tvEmpty)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btnClear)?.setOnClickListener { clearAll() }

        loadWords()
    }

    override fun onDestroy() {
        scope.cancel()
        pronouncer.release()
        repository.close()
        super.onDestroy()
    }

    private fun loadWords() {
        scope.launch {
            val words = repository.list()
            adapter.setData(words)
            val isEmpty = words.isEmpty()
            tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun clearAll() {
        scope.launch {
            if (repository.clear()) {
                loadWords()
                Toast.makeText(this@VocabularyActivity, "生词库已清空", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@VocabularyActivity, "清空失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteWord(item: SavedWord) {
        scope.launch {
            if (repository.remove(item.word)) {
                loadWords()
                Toast.makeText(this@VocabularyActivity, "已删除“${item.word}”", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@VocabularyActivity, "删除失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class VocabularyAdapter : RecyclerView.Adapter<VocabularyAdapter.ViewHolder>() {

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
                val (backgroundRes, textRes, label) = when (item.difficulty) {
                    WordDifficulty.HARD -> Triple(
                        R.color.difficulty_hard_bg, R.color.difficulty_hard_text, "难"
                    )
                    WordDifficulty.MEDIUM -> Triple(
                        R.color.difficulty_medium_bg, R.color.difficulty_medium_text, "中"
                    )
                    else -> Triple(
                        R.color.difficulty_easy_bg, R.color.difficulty_easy_text, "易"
                    )
                }
                tvDifficulty.text = label
                tvDifficulty.backgroundTintList = ColorStateList.valueOf(
                    tvDifficulty.context.getColor(backgroundRes)
                )
                tvDifficulty.setTextColor(tvDifficulty.context.getColor(textRes))

                tvAddedAt.text = "收藏于 ${dateFormat.format(Date(item.createdAt))}"

                btnPronounce.setOnClickListener { pronouncer.speak(item.word) }
                btnDelete.setOnClickListener { deleteWord(item) }
            }
        }
    }
}
