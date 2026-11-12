package com.zhangxh.subtitletranslator.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.translator.Language
import com.zhangxh.subtitletranslator.ui.common.displayNameRes

/**
 * 语言设置界面
 *
 * 只负责源语言/目标语言的控件绑定与渲染；读取、写入与校验由
 * [LanguageSettingsViewModel] 完成。与偏好设置分开，避免两个入口打开同一页面。
 */
class LanguageSettingsActivity : BaseActivity() {

    private val viewModel: LanguageSettingsViewModel by viewModels()

    private val languages = Language.SUPPORTED

    private lateinit var inputSource: MaterialAutoCompleteTextView
    private lateinit var inputTarget: MaterialAutoCompleteTextView
    private lateinit var languageLabels: List<String>

    /** 渲染状态期间忽略控件回调，避免初始化触发写盘 */
    private var isBinding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_settings)

        inputSource = findViewById(R.id.inputSource)
        inputTarget = findViewById(R.id.inputTarget)
        setupAdapters()
        setupListeners()

        viewModel.uiState.observe(this) { state -> render(state) }
        viewModel.messages.observe(this) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message.resolve(this), Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationContentDescription(R.string.action_back)
            setNavigationOnClickListener { finish() }
        }
    }

    private fun setupAdapters() {
        languageLabels = languages.map { language ->
            getString(
                R.string.language_option,
                getString(language.displayNameRes()),
                language.name
            )
        }
        inputSource.setSimpleItems(languageLabels.toTypedArray())
        inputTarget.setSimpleItems(languageLabels.toTypedArray())
    }

    private fun setupListeners() {
        inputSource.setOnItemClickListener { _, _, position, _ ->
            val language = languages.getOrNull(position) ?: return@setOnItemClickListener
            if (!isBinding && language.code != viewModel.uiState.value?.sourceLang?.code) {
                viewModel.setSourceLang(language)
            }
        }
        inputTarget.setOnItemClickListener { _, _, position, _ ->
            val language = languages.getOrNull(position) ?: return@setOnItemClickListener
            if (!isBinding && language.code != viewModel.uiState.value?.targetLang?.code) {
                viewModel.setTargetLang(language)
            }
        }
    }

    private fun render(state: LanguageSettingsUiState) {
        isBinding = true
        try {
            inputSource.setTextSilently(
                languageLabels.getOrNull(languages.indexOf(state.sourceLang))
            )
            inputTarget.setTextSilently(
                languageLabels.getOrNull(languages.indexOf(state.targetLang))
            )
        } finally {
            isBinding = false
        }
    }

    private fun MaterialAutoCompleteTextView.setTextSilently(text: String?) {
        val value = text.orEmpty()
        if (this.text?.toString() != value) setText(value, false)
    }
}
