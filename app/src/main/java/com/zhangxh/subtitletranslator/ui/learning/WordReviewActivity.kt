package com.zhangxh.subtitletranslator.ui.learning

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.ui.BaseActivity

/**
 * 单词卡片复习
 *
 * 先看单词回忆，再翻面确认释义；认识/不认识只更新本地熟悉度。
 */
class WordReviewActivity : BaseActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
    }

    private val mode: ReviewMode by lazy {
        ReviewMode.fromName(intent.getStringExtra(EXTRA_MODE))
    }

    private val viewModel: WordReviewViewModel by viewModels {
        WordReviewViewModel.factory(application, mode)
    }

    private lateinit var reviewContent: View
    private lateinit var emptyGroup: View
    private lateinit var finishedGroup: View
    private lateinit var cardScroll: ScrollView
    private lateinit var cardWord: View
    private lateinit var tvProgress: TextView
    private lateinit var tvScore: TextView
    private lateinit var reviewProgress: LinearProgressIndicator
    private lateinit var tvWord: TextView
    private lateinit var tvPhonetic: TextView
    private lateinit var tvRevealHint: TextView
    private lateinit var tvMeanings: TextView
    private lateinit var contextSection: View
    private lateinit var tvContextSource: TextView
    private lateinit var tvContextTranslation: TextView
    private lateinit var judgementGroup: View
    private lateinit var btnShowContext: MaterialButton
    private lateinit var btnReveal: MaterialButton
    private lateinit var btnKnown: MaterialButton
    private lateinit var btnUnknown: MaterialButton
    private lateinit var tvFinishedSummary: TextView

    /** 当前卡片对应的词；换词时把滚动位置和上下文提示复位 */
    private var lastWord: String? = null
    private var contextWasVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word_review)
        bindViews()
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationContentDescription(R.string.action_close)
            setNavigationOnClickListener { finish() }
        }

        cardWord.setOnClickListener { viewModel.reveal() }
        btnShowContext.setOnClickListener { viewModel.toggleContext() }
        btnReveal.setOnClickListener { viewModel.reveal() }
        btnKnown.setOnClickListener { viewModel.markKnown() }
        btnUnknown.setOnClickListener { viewModel.markUnknown() }
        findViewById<ImageButton>(R.id.btnSpeak).setOnClickListener { viewModel.speak() }
        findViewById<MaterialButton>(R.id.btnReviewAgain).setOnClickListener {
            viewModel.restart()
        }
        findViewById<MaterialButton>(R.id.btnDone).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnEmptyBack).setOnClickListener { finish() }

        viewModel.uiState.observe(this) { state -> render(state) }
        viewModel.messages.observe(this) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message.resolve(this), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindViews() {
        reviewContent = findViewById(R.id.reviewContent)
        emptyGroup = findViewById(R.id.emptyGroup)
        finishedGroup = findViewById(R.id.finishedGroup)
        cardScroll = findViewById(R.id.cardScroll)
        cardWord = findViewById(R.id.cardWord)
        tvProgress = findViewById(R.id.tvProgress)
        tvScore = findViewById(R.id.tvScore)
        reviewProgress = findViewById(R.id.reviewProgress)
        tvWord = findViewById(R.id.tvWord)
        tvPhonetic = findViewById(R.id.tvPhonetic)
        tvRevealHint = findViewById(R.id.tvRevealHint)
        tvMeanings = findViewById(R.id.tvMeanings)
        contextSection = findViewById(R.id.contextSection)
        tvContextSource = findViewById(R.id.tvContextSource)
        tvContextTranslation = findViewById(R.id.tvContextTranslation)
        judgementGroup = findViewById(R.id.judgementGroup)
        btnShowContext = findViewById(R.id.btnShowContext)
        btnReveal = findViewById(R.id.btnReveal)
        btnKnown = findViewById(R.id.btnKnown)
        btnUnknown = findViewById(R.id.btnUnknown)
        tvFinishedSummary = findViewById(R.id.tvFinishedSummary)
    }

    private fun render(state: ReviewUiState) {
        val showReview = !state.empty && !state.finished
        reviewContent.isVisible = showReview
        emptyGroup.isVisible = state.empty
        finishedGroup.isVisible = state.finished && !state.empty

        if (state.finished && !state.empty) {
            tvFinishedSummary.text = getString(
                R.string.word_review_finished_summary,
                state.knownCount,
                state.unknownCount
            )
        }

        val word = state.current
        if (!showReview || word == null) return

        tvProgress.text = getString(
            R.string.word_review_progress,
            state.index + 1,
            state.total
        )
        tvScore.text = getString(
            R.string.word_review_score,
            state.knownCount,
            state.unknownCount
        )
        reviewProgress.max = state.total.coerceAtLeast(1)
        reviewProgress.setProgressCompat(state.index + 1, true)

        if (word.word != lastWord) {
            lastWord = word.word
            contextWasVisible = false
            cardScroll.scrollTo(0, 0)
        }

        tvWord.text = word.word
        tvPhonetic.text = word.phonetic
        tvPhonetic.isVisible = word.phonetic.isNotBlank()

        val hasContext = word.context?.isValid() == true
        btnShowContext.isVisible = hasContext
        btnShowContext.setText(
            if (state.contextVisible) {
                R.string.word_review_hide_context
            } else {
                R.string.word_review_show_context
            }
        )

        tvRevealHint.isVisible = !state.revealed
        tvMeanings.isVisible = state.revealed
        btnReveal.isVisible = !state.revealed
        judgementGroup.isVisible = state.revealed
        if (state.revealed) tvMeanings.text = buildMeanings(word)
        // 释义没翻开时只给原文，避免翻译直接把答案说出来
        bindContext(word, visible = hasContext && state.contextVisible, showTranslation = state.revealed)

        if (state.contextVisible && !contextWasVisible) {
            cardScroll.post {
                cardScroll.smoothScrollTo(0, cardWord.height)
            }
        }
        contextWasVisible = state.contextVisible
    }

    private fun buildMeanings(word: SavedWord): String =
        word.meanings.joinToString("\n\n") { meaning ->
            buildString {
                if (meaning.partOfSpeech.isNotBlank()) append("[${meaning.partOfSpeech}] ")
                if (meaning.chineseDefinition.isNotBlank()) append(meaning.chineseDefinition)
                if (meaning.definition.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(meaning.definition)
                }
            }
        }.ifBlank { getString(R.string.word_detail_no_meaning) }

    private fun bindContext(word: SavedWord, visible: Boolean, showTranslation: Boolean) {
        val source = word.context?.source?.takeIf { it.isNotBlank() }
        val translation = word.context?.translation?.takeIf { it.isNotBlank() }
        val hasContext = source != null || translation != null
        contextSection.isVisible = visible && hasContext
        tvContextSource.isVisible = source != null
        tvContextSource.text = source?.let { "“$it”" }.orEmpty()
        // 只有原文时翻译永远显示；翻开释义后两者都给
        val translationVisible = translation != null && (showTranslation || source == null)
        tvContextTranslation.isVisible = translationVisible
        tvContextTranslation.text = if (translationVisible) translation.orEmpty() else ""
    }
}
