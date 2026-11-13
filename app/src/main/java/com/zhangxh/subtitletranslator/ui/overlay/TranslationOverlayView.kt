package com.zhangxh.subtitletranslator.ui.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.TranslationResult
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyKey
import com.zhangxh.subtitletranslator.ui.common.labelRes
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

        /**
         * 窗口水平偏移量
         *
         * 系统会先扣掉刘海、侧边导航栏等区域再给窗口分配可布局区域，
         * `Gravity.CENTER_HORIZONTAL` 居中的是这个区域的中心，而不是整块屏幕的中心。
         * 横屏时两侧 inset 常常不对称，面板看起来就会偏右（或偏左）。
         * 按两侧 inset 的差值补偿，面板才会相对屏幕真正居中。
         *
         * 面板已经占满可用宽度时返回 0：这时再平移会压到系统栏。
         */
        fun horizontalOffsetFor(
            overlayWidthPx: Int,
            availableWidthPx: Int,
            leftInsetPx: Int,
            rightInsetPx: Int
        ): Int = if (overlayWidthPx < availableWidthPx) {
            (rightInsetPx - leftInsetPx) / 2
        } else {
            0
        }
    }

    private var onCloseListener: (() -> Unit)? = null
    private var onCopyListener: ((String) -> Unit)? = null
    private var onPronounceListener: ((String) -> Unit)? = null
    private var onSaveToggleListener: ((WordEntry) -> Unit)? = null
    private var onSentenceSaveToggleListener: ((String, String?) -> Unit)? = null
    private var onPreviousSubtitleListener: (() -> Unit)? = null
    private var onNextSubtitleListener: (() -> Unit)? = null
    private var onAiTranslateListener: (() -> Unit)? = null

    /** 当前译文是否来自 AI 精译（决定 AI 图标颜色） */
    private var aiTranslated = false

    /** AI 请求进行中（禁用重复点击） */
    private var aiLoading = false

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.overlay_translation, this, true)
        // 窗口高度是精确值；面板必须 match_parent，否则滚动区的 weight
        // 在 AT_MOST 约束下会吃掉底部操作栏的位置，导致两者重叠。
        (getChildAt(0)?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            getChildAt(0).layoutParams = lp
        }
        setupCloseButton()
        setupCollapseButton()
        setupCopyButtons()
        setupNavigationButtons()
        setupSentenceSaveButton()
        setupAiButton()
    }

    /**
     * 按自然内容高度测量（不受窗口封顶影响）
     *
     * 布局里的 ScrollView 使用 0dp + weight=1，保证窗口封顶后底部收起入口
     * 始终留在窗口内；但「内容不多时窗口该多高」仍要按内容本身计算。
     * 这里临时把 ScrollView 还原成 wrap_content 测量一次，测完立即恢复，
     * 之后 WindowManager 按本方法返回的高度布局时，weight 会重新分配空间。
     */
    fun measureNaturalHeight(widthPx: Int): Int {
        val scroll = findViewById<ScrollView>(R.id.contentScroll) ?: return 0
        val lp = scroll.layoutParams as? LinearLayout.LayoutParams ?: return 0
        val savedHeight = lp.height
        val savedWeight = lp.weight
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.weight = 0f
        scroll.layoutParams = lp

        // 自然测量时面板不能是 match_parent，否则会按可用高度撑满
        val panel = getChildAt(0)
        val panelLp = panel?.layoutParams as? LinearLayout.LayoutParams
        val savedPanelHeight = panelLp?.height
        if (panelLp != null) {
            panelLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            panel.layoutParams = panelLp
        }

        measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val naturalHeight = measuredHeight

        if (panelLp != null) {
            panelLp.height = savedPanelHeight ?: ViewGroup.LayoutParams.MATCH_PARENT
            panel.layoutParams = panelLp
        }
        lp.height = savedHeight
        lp.weight = savedWeight
        scroll.layoutParams = lp
        return naturalHeight
    }

    /**
     * 设置翻译结果
     *
     * @param aiTranslated 恢复已显示过 AI 译文的面板时传 true，图标保持高亮
     */
    fun setTranslationResult(result: TranslationResult, aiTranslated: Boolean = false) {
        // 原文
        findViewById<TextView>(R.id.tvOriginalText)?.text = result.originalText

        // 整句收藏：没有原文时没有可收藏的对象
        findViewById<android.widget.ImageButton>(R.id.btnSaveSentence)?.apply {
            visibility = if (result.originalText.isBlank()) View.GONE else View.VISIBLE
        }

        // 译文
        findViewById<TextView>(R.id.tvTranslatedText)?.text = result.translatedText

        // AI 状态：新一轮基础翻译默认回到暗色
        setAiTranslated(aiTranslated)

        // 词数：没有生词时不显示
        findViewById<TextView>(R.id.tvWordCount)?.apply {
            val count = result.difficultWords.size
            text = resources.getQuantityString(R.plurals.overlay_word_count, count, count)
            visibility = if (count == 0) View.GONE else View.VISIBLE
        }

        // 难词列表
        val wordsContainer = findViewById<LinearLayout>(R.id.wordsContainer) ?: return
        wordsContainer.removeAllViews()

        if (result.difficultWords.isNotEmpty()) {
            result.difficultWords.forEach { wordEntry ->
                wordsContainer.addView(createWordView(wordEntry, wordsContainer))
            }
        } else {
            val noWordsView = TextView(context).apply {
                text = context.getString(R.string.overlay_no_difficult_words)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(context.getColor(R.color.text_secondary))
                val vertical = (24 * resources.displayMetrics.density).toInt()
                setPadding(0, vertical, 0, vertical)
            }
            wordsContainer.addView(noWordsView)
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
     * 设置整句收藏监听
     *
     * 回调带原文和译文：句子收藏不依赖句中是否有关键词。
     */
    fun setOnSentenceSaveToggleListener(listener: (String, String?) -> Unit) {
        onSentenceSaveToggleListener = listener
    }

    /** 跳到视频的上一条字幕 */
    fun setOnPreviousSubtitleListener(listener: () -> Unit) {
        onPreviousSubtitleListener = listener
    }

    /** 跳到视频的下一条字幕 */
    fun setOnNextSubtitleListener(listener: () -> Unit) {
        onNextSubtitleListener = listener
    }

    /** 点击 AI 图标，用云端 AI 精译更新当前译文 */
    fun setOnAiTranslateListener(listener: () -> Unit) {
        onAiTranslateListener = listener
    }

    /**
     * 更新 AI 精译状态
     *
     * 高亮表示当前译文来自 AI；切换字幕或基础翻译失败回退后重新置为暗色。
     */
    fun setAiTranslated(translated: Boolean) {
        aiTranslated = translated
        refreshAiButton()
    }

    /** AI 请求进行中：禁用重复点击，图标保持当前状态 */
    fun setAiLoading(loading: Boolean) {
        aiLoading = loading
        refreshAiButton()
    }

    /**
     * 用 AI 返回的译文更新面板
     *
     * 只替换译文文本，不改原文和生词列表；整句收藏/复制会读取更新后的译文。
     */
    fun updateTranslatedText(text: String, aiTranslated: Boolean) {
        findViewById<TextView>(R.id.tvTranslatedText)?.text = text
        setAiTranslated(aiTranslated)
    }

    /**
     * AI 图标：暗色表示普通译文，强调色表示当前译文来自 AI
     */
    private fun setupAiButton() {
        findViewById<ImageButton>(R.id.btnAiTranslate)?.setOnClickListener {
            if (!aiTranslated && !aiLoading) {
                onAiTranslateListener?.invoke()
            }
        }
        refreshAiButton()
    }

    private fun refreshAiButton() {
        val button = findViewById<ImageButton>(R.id.btnAiTranslate) ?: return
        button.isEnabled = !aiLoading
        button.alpha = if (aiLoading) 0.5f else 1f
        button.imageTintList = ColorStateList.valueOf(
            context.getColor(if (aiTranslated) R.color.accent else R.color.text_secondary)
        )
        button.contentDescription = context.getString(
            when {
                aiLoading -> R.string.overlay_ai_translating
                aiTranslated -> R.string.overlay_ai_applied
                else -> R.string.overlay_ai_translate
            }
        )
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
        button.contentDescription = context.getString(
            if (saved) R.string.action_unsave_word else R.string.action_save_word
        )
        button.isSelected = saved
    }

    /**
     * 更新整句收藏状态
     *
     * 收藏状态存在数据库里，服务异步查回来后再调用这里点亮图标。
     */
    fun setSentenceSaved(saved: Boolean) {
        findViewById<android.widget.ImageButton>(R.id.btnSaveSentence)?.apply {
            setImageResource(if (saved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
            contentDescription = context.getString(
                if (saved) R.string.action_unsave_sentence else R.string.action_save_sentence
            )
            isSelected = saved
        }
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
     * 底部「收起面板后继续观看」与关闭按钮行为一致
     */
    private fun setupCollapseButton() {
        findViewById<View>(R.id.btnCollapse)?.setOnClickListener {
            onCloseListener?.invoke()
        }
    }

    /**
     * 上一句 / 下一句
     */
    private fun setupNavigationButtons() {
        findViewById<View>(R.id.btnPreviousSubtitle)?.setOnClickListener {
            onPreviousSubtitleListener?.invoke()
        }
        findViewById<View>(R.id.btnNextSubtitle)?.setOnClickListener {
            onNextSubtitleListener?.invoke()
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
     * 整句收藏按钮：收藏/取消由服务决定，视图只负责把当前原文和译文带出去
     */
    private fun setupSentenceSaveButton() {
        findViewById<android.widget.ImageButton>(R.id.btnSaveSentence)?.setOnClickListener {
            val source = findViewById<TextView>(R.id.tvOriginalText)
                ?.text?.toString()?.trim().orEmpty()
            if (source.isEmpty()) return@setOnClickListener
            val translation = findViewById<TextView>(R.id.tvTranslatedText)
                ?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            onSentenceSaveToggleListener?.invoke(source, translation)
        }
    }

    /**
     * 创建单词释义视图
     *
     * 必须带上 [parent] 且 attachToRoot=false 地解析布局：
     * 传 null 根节点会让 inflater 丢掉根布局的 layout_* 参数（包括卡片间距和 match_parent），
     * 词卡就会挤在一起、宽度也撑不满。
     */
    private fun createWordView(wordEntry: WordEntry, parent: LinearLayout): View {
        return LayoutInflater.from(context)
            .inflate(R.layout.item_word_entry, parent, false)
            .apply {
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
                val difficulty = wordEntry.difficulty
                val (backgroundRes, textRes) = when (difficulty) {
                    WordDifficulty.HARD ->
                        R.color.difficulty_hard_bg to R.color.difficulty_hard_text
                    WordDifficulty.MEDIUM ->
                        R.color.difficulty_medium_bg to R.color.difficulty_medium_text
                    else ->
                        R.color.difficulty_easy_bg to R.color.difficulty_easy_text
                }
                difficultyView?.text = context.getString(difficulty.labelRes())
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
