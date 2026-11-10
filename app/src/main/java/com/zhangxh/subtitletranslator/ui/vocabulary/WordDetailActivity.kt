package com.zhangxh.subtitletranslator.ui.vocabulary

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStatus
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.ui.BaseActivity
import com.zhangxh.subtitletranslator.ui.common.labelRes

/**
 * 词汇详情
 *
 * 重点展示“字幕中的用法”：这是普通词典没有的上下文。
 * 使用独立 Activity，方便后续扩展例句、相关表达和学习状态操作。
 */
class WordDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_WORD = "extra_word"
    }

    private val viewModel: WordDetailViewModel by viewModels {
        WordDetailViewModel.factory(
            application,
            intent.getStringExtra(EXTRA_WORD).orEmpty()
        )
    }

    private lateinit var loading: View
    private lateinit var scroll: View
    private lateinit var tvWord: TextView
    private lateinit var tvPhonetic: TextView
    private lateinit var tvLearningStatus: TextView
    private lateinit var tvMeanings: TextView
    private lateinit var contextSection: View
    private lateinit var tvContextSource: TextView
    private lateinit var tvContextTranslation: TextView
    private lateinit var examplesSection: View
    private lateinit var tvExamples: TextView

    private var deleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word_detail)
        bindViews()
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationContentDescription(R.string.action_back)
            setNavigationOnClickListener { finish() }
        }
        findViewById<View>(R.id.btnSpeak).setOnClickListener { viewModel.speak() }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener { confirmDelete() }

        viewModel.uiState.observe(this) { state ->
            loading.isVisible = state.loading
            scroll.isVisible = !state.loading && state.word != null
            state.word?.let(::render)
            if (state.deleted) {
                deleted = true
                setResult(Activity.RESULT_OK)
                finish()
            } else if (!state.loading && state.word == null) {
                Toast.makeText(this, R.string.word_detail_not_found, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        viewModel.messages.observe(this) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message.resolve(this), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindViews() {
        loading = findViewById(R.id.detailLoading)
        scroll = findViewById(R.id.detailScroll)
        tvWord = findViewById(R.id.tvWord)
        tvPhonetic = findViewById(R.id.tvPhonetic)
        tvLearningStatus = findViewById(R.id.tvLearningStatus)
        tvMeanings = findViewById(R.id.tvMeanings)
        contextSection = findViewById(R.id.contextSection)
        tvContextSource = findViewById(R.id.tvContextSource)
        tvContextTranslation = findViewById(R.id.tvContextTranslation)
        examplesSection = findViewById(R.id.examplesSection)
        tvExamples = findViewById(R.id.tvExamples)
    }

    private fun render(word: SavedWord) {
        tvWord.text = word.word
        tvPhonetic.text = word.phonetic
        tvPhonetic.isVisible = word.phonetic.isNotBlank()
        tvLearningStatus.setText(word.learningStatus.labelRes())
        tvLearningStatus.setTextColor(
            getColor(
                if (word.learningStatus == LearningStatus.MASTERED) {
                    R.color.accent
                } else {
                    R.color.text_secondary
                }
            )
        )

        tvMeanings.text = word.meanings.joinToString("\n\n") { meaning ->
            buildString {
                if (meaning.partOfSpeech.isNotBlank()) append("[${meaning.partOfSpeech}] ")
                if (meaning.chineseDefinition.isNotBlank()) append(meaning.chineseDefinition)
                if (meaning.definition.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(meaning.definition)
                }
            }
        }.ifBlank { getString(R.string.word_detail_no_meaning) }

        val context = word.context
        val source = context?.source?.takeIf { it.isNotBlank() }
        val translation = context?.translation?.takeIf { it.isNotBlank() }
        contextSection.isVisible = source != null || translation != null
        tvContextSource.isVisible = source != null
        tvContextSource.text = source?.let { "“$it”" }.orEmpty()
        tvContextTranslation.isVisible = translation != null
        tvContextTranslation.text = translation.orEmpty()

        val examples = word.meanings.flatMap { meaning -> meaning.examples }
            .filter { it.isNotBlank() }
        examplesSection.isVisible = examples.isNotEmpty()
        tvExamples.text = examples.joinToString("\n") { "• $it" }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.word_detail_delete_confirm_title)
            .setMessage(R.string.word_detail_delete_confirm_message)
            .setNegativeButton(R.string.profile_cancel, null)
            .setPositiveButton(R.string.word_detail_delete) { _, _ -> viewModel.delete() }
            .show()
    }

    override fun finish() {
        setResult(if (deleted) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        super.finish()
    }
}
