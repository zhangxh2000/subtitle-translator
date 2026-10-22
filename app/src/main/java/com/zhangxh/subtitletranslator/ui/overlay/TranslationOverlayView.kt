package com.zhangxh.subtitletranslator.ui.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.TranslationResult
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyKey
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry

/**
 * 翻译结果覆盖层视图
 * 显示翻译结果和难词释义
 */
class TranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        /**
         * 覆盖层高度上限占屏幕高度的比例
         *
         * 取值偏大：面板是查看释义用的，内容看不全要频繁滚动比多挡一点画面更难受。
         * 留出的顶部空间只够露出状态栏即可，面板随时可以点悬浮球收起。
         * （这里曾取 0.6，实测内容稍多就要一直滑，已调大。）
         */
        const val MAX_HEIGHT_RATIO = 0.85f

        /**
         * 计算悬浮窗高度
         *
         * 内容不多时按内容撑开；内容过多（单词释义很长时很常见）时按屏幕高度封顶，
         * 超出的部分靠面板内滚动查看 —— 否则窗口会超出屏幕，顶部内容被裁掉。
         */
        fun windowHeightFor(contentHeight: Int, screenHeight: Int): Int =
            minOf(contentHeight, (screenHeight * MAX_HEIGHT_RATIO).toInt())
    }

    private var onCloseListener: (() -> Unit)? = null
    private var onCopyListener: ((String) -> Unit)? = null
    private var onPronounceListener: ((String) -> Unit)? = null
    private var onSaveToggleListener: ((WordEntry) -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.overlay_translation, this, true)
        setupCloseButton()
        setupCopyButtons()
    }

    /**
     * 设置翻译结果
     */
    fun setTranslationResult(result: TranslationResult) {
        // 原文
        findViewById<TextView>(R.id.tvOriginalText)?.text = result.originalText

        // 译文
        findViewById<TextView>(R.id.tvTranslatedText)?.text = result.translatedText

        // 词数：没有生词时不显示
        findViewById<TextView>(R.id.tvWordCount)?.apply {
            text = "${result.difficultWords.size} 个词"
            visibility = if (result.difficultWords.isEmpty()) View.GONE else View.VISIBLE
        }

        // 难词列表
        val wordsContainer = findViewById<LinearLayout>(R.id.wordsContainer)
        wordsContainer?.removeAllViews()

        if (result.difficultWords.isNotEmpty()) {
            result.difficultWords.forEach { wordEntry ->
                val wordView = createWordView(wordEntry)
                wordsContainer?.addView(wordView)
            }
        } else {
            val noWordsView = TextView(context).apply {
                text = "未检测到难词"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(context.getColor(R.color.text_secondary))
                val vertical = (24 * resources.displayMetrics.density).toInt()
                setPadding(0, vertical, 0, vertical)
            }
            wordsContainer?.addView(noWordsView)
        }
    }

    /**
     * 设置关闭监听
     */
    fun setOnCloseListener(listener: () -> Unit) {
        onCloseListener = listener
    }

    /**
     * 设置复制监听
     */
    fun setOnCopyListener(listener: (String) -> Unit) {
        onCopyListener = listener
    }

    /**
     * 设置朗读监听
     */
    fun setOnPronounceListener(listener: (String) -> Unit) {
        onPronounceListener = listener
    }

    /**
     * 设置收藏切换监听
     *
     * 回调传整条 [WordEntry]：收藏到生词库需要词、音标、释义和难度。
     */
    fun setOnSaveToggleListener(listener: (WordEntry) -> Unit) {
        onSaveToggleListener = listener
    }

    /**
     * 更新生词收藏状态
     *
     * 收藏状态存在数据库里，服务异步查回来后再调用这里点亮图标。
     * 同一个词可能在一个结果中出现多次，所以按归一化后的键遍历所有行。
     */
    fun setWordSaved(word: String, saved: Boolean) {
        val key = VocabularyKey.of(word) ?: return
        val container = findViewById<LinearLayout>(R.id.wordsContainer) ?: return
        for (index in 0 until container.childCount) {
            val button = container.getChildAt(index)
                .findViewById<android.widget.ImageButton>(R.id.btnSaveWord)
                ?: continue
            if (button.tag == key) bindSavedState(button, saved)
        }
    }

    private fun bindSavedState(button: android.widget.ImageButton, saved: Boolean) {
        button.setImageResource(if (saved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        button.contentDescription = if (saved) "取消收藏" else "收藏"
        button.isSelected = saved
    }

    /**
     * 设置关闭按钮
     */
    private fun setupCloseButton() {
        findViewById<android.widget.ImageButton>(R.id.btnClose)?.setOnClickListener {
            onCloseListener?.invoke()
        }
    }

    /**
     * 设置复制按钮
     */
    private fun setupCopyButtons() {
        findViewById<TextView>(R.id.tvOriginalText)?.setOnLongClickListener {
            val text = (it as TextView).text.toString()
            if (text.isNotBlank()) {
                onCopyListener?.invoke(text)
            }
            true
        }
        findViewById<TextView>(R.id.tvTranslatedText)?.setOnLongClickListener {
            val text = (it as TextView).text.toString()
            if (text.isNotBlank()) {
                onCopyListener?.invoke(text)
            }
            true
        }
    }

    /**
     * 创建单词释义视图
     */
    private fun createWordView(wordEntry: WordEntry): View {
        return LayoutInflater.from(context).inflate(R.layout.item_word_entry, null).apply {
            findViewById<TextView>(R.id.tvWord)?.text = wordEntry.word
            findViewById<TextView>(R.id.tvPhonetic)?.text = wordEntry.phonetic

            // 释义：词性用强调色，释义本身保持中性色
            val meaningsText = SpannableStringBuilder()
            wordEntry.meanings.forEachIndexed { index, meaning ->
                if (index > 0) meaningsText.append("\n")
                if (meaning.partOfSpeech.isNotEmpty()) {
                    val start = meaningsText.length
                    meaningsText.append("[${meaning.partOfSpeech}] ")
                    meaningsText.setSpan(
                        ForegroundColorSpan(context.getColor(R.color.accent)),
                        start,
                        meaningsText.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                meaningsText.append(meaning.chineseDefinition)
            }
            findViewById<TextView>(R.id.tvMeanings)?.text = meaningsText

            // 难度标签：低饱和底色 + 同色系文字
            val difficultyView = findViewById<TextView>(R.id.tvDifficulty)
            val (backgroundRes, textRes, label) = when (wordEntry.difficulty) {
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
            difficultyView?.text = label
            difficultyView?.backgroundTintList =
                ColorStateList.valueOf(context.getColor(backgroundRes))
            difficultyView?.setTextColor(context.getColor(textRes))

            // 点喇叭朗读单词。图标可点之后会吃掉自己的按下事件，
            // 所以图标区域内长按不再冒泡到下面那个复制监听 —— 属预期行为。
            findViewById<android.widget.ImageButton>(R.id.btnPronounce)?.setOnClickListener {
                onPronounceListener?.invoke(wordEntry.word)
            }

            // 收藏到生词库：tag 存归一化后的词键，服务异步查回收藏状态时按它匹配行
            findViewById<android.widget.ImageButton>(R.id.btnSaveWord)?.apply {
                tag = VocabularyKey.of(wordEntry.word)
                bindSavedState(this, false)
                setOnClickListener {
                    onSaveToggleListener?.invoke(wordEntry)
                }
            }

            // 长按复制单词
            setOnLongClickListener {
                onCopyListener?.invoke(wordEntry.word)
                true
            }
        }
    }
}
