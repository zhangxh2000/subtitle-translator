package com.zhangxh.subtitletranslator.ui.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.TranslationResult
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

    private var onCloseListener: (() -> Unit)? = null
    private var onCopyListener: ((String) -> Unit)? = null

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
                setTextColor(context.getColor(android.R.color.darker_gray))
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

            // 释义
            val meaningsText = wordEntry.meanings.joinToString("\n") { meaning ->
                val pos = if (meaning.partOfSpeech.isNotEmpty()) "[${meaning.partOfSpeech}] " else ""
                "$pos${meaning.chineseDefinition}"
            }
            findViewById<TextView>(R.id.tvMeanings)?.text = meaningsText

            // 难度标识
            val difficultyView = findViewById<TextView>(R.id.tvDifficulty)
            when (wordEntry.difficulty) {
                WordDifficulty.HARD -> {
                    difficultyView?.text = "难"
                    difficultyView?.setBackgroundColor(context.getColor(android.R.color.holo_red_light))
                }
                WordDifficulty.MEDIUM -> {
                    difficultyView?.text = "中"
                    difficultyView?.setBackgroundColor(context.getColor(android.R.color.holo_orange_light))
                }
                else -> {
                    difficultyView?.text = "易"
                    difficultyView?.setBackgroundColor(context.getColor(android.R.color.holo_green_light))
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
