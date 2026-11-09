package com.zhangxh.subtitletranslator.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.data.accessibility.AccessibilityAccess
import com.zhangxh.subtitletranslator.data.media.NotificationAccess
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import com.zhangxh.subtitletranslator.domain.translator.Language
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficultyFilter
import com.zhangxh.subtitletranslator.ui.common.displayNameRes
import com.zhangxh.subtitletranslator.ui.common.labelRes

/**
 * 设置界面
 *
 * 只负责控件绑定与状态渲染；读取/写入与校验由 [SettingsViewModel] 完成。
 */
class SettingsActivity : BaseActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    private val languages = Language.SUPPORTED
    private val difficultyFilters = WordDifficultyFilter.entries
    private val engines = TranslationEngine.entries
    private val preprocessModes = OcrPreprocessMode.entries

    private lateinit var inputSource: MaterialAutoCompleteTextView
    private lateinit var inputTarget: MaterialAutoCompleteTextView
    private lateinit var inputDifficulty: MaterialAutoCompleteTextView
    private lateinit var inputEngine: MaterialAutoCompleteTextView
    private lateinit var inputPreprocess: MaterialAutoCompleteTextView
    private lateinit var switchDebugCapture: MaterialSwitch
    private lateinit var languageLabels: List<String>
    private lateinit var difficultyLabels: List<String>
    private lateinit var engineLabels: List<String>
    private lateinit var preprocessLabels: List<String>
    private lateinit var tvEngineHint: TextView
    private lateinit var tvPreprocessHint: TextView
    private lateinit var tvMediaAccessStatus: TextView
    private lateinit var tvA11yStatus: TextView

    /** 渲染状态期间忽略控件回调，避免初始化触发写盘 */
    private var isBinding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bindViews()
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
        findViewById<View>(R.id.btnViewDebugCaptures)?.setOnClickListener {
            startActivity(Intent(this, DebugCaptureActivity::class.java))
        }
        findViewById<View>(R.id.btnMediaAccess)?.setOnClickListener {
            NotificationAccess.openSettings(this)
        }
        findViewById<View>(R.id.btnA11ySettings)?.setOnClickListener {
            AccessibilityAccess.openSettings(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updateMediaAccessStatus()
        updateAccessibilityStatus()
    }

    /** 权限可能在系统设置页被修改，每次回到界面都刷新一次 */
    private fun updateMediaAccessStatus() {
        val granted = NotificationAccess.isGranted(this)
        tvMediaAccessStatus.text = getString(
            if (granted) R.string.settings_media_access_granted
            else R.string.settings_media_access_missing
        )
        tvMediaAccessStatus.setTextColor(
            getColor(if (granted) R.color.accent else R.color.text_secondary)
        )
    }

    private fun updateAccessibilityStatus() {
        val enabled = AccessibilityAccess.isUsable(this)
        tvA11yStatus.text = getString(
            if (enabled) R.string.settings_accessibility_granted
            else R.string.settings_accessibility_missing
        )
        tvA11yStatus.setTextColor(
            getColor(if (enabled) R.color.accent else R.color.text_secondary)
        )
    }

    private fun bindViews() {
        inputSource = findViewById(R.id.inputSource)
        inputTarget = findViewById(R.id.inputTarget)
        inputDifficulty = findViewById(R.id.inputDifficulty)
        inputEngine = findViewById(R.id.inputEngine)
        inputPreprocess = findViewById(R.id.inputPreprocess)
        switchDebugCapture = findViewById(R.id.switchDebugCapture)
        tvEngineHint = findViewById(R.id.tvEngineHint)
        tvPreprocessHint = findViewById(R.id.tvPreprocessHint)
        tvMediaAccessStatus = findViewById(R.id.tvMediaAccessStatus)
        tvA11yStatus = findViewById(R.id.tvA11yStatus)
    }

    private fun setupAdapters() {
        languageLabels = languages.map { language ->
            getString(
                R.string.language_option,
                getString(language.displayNameRes()),
                language.name
            )
        }
        difficultyLabels = difficultyFilters.map { getString(it.labelRes()) }
        engineLabels = engines.map { getString(it.labelRes()) }
        preprocessLabels = preprocessModes.map { getString(it.labelRes()) }

        inputSource.setSimpleItems(languageLabels.toTypedArray())
        inputTarget.setSimpleItems(languageLabels.toTypedArray())
        inputDifficulty.setSimpleItems(difficultyLabels.toTypedArray())
        inputEngine.setSimpleItems(engineLabels.toTypedArray())
        inputPreprocess.setSimpleItems(preprocessLabels.toTypedArray())
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
        inputDifficulty.setOnItemClickListener { _, _, position, _ ->
            val filter = difficultyFilters.getOrNull(position) ?: return@setOnItemClickListener
            if (!isBinding && filter != viewModel.uiState.value?.difficultyFilter) {
                viewModel.setDifficultyFilter(filter)
            }
        }
        inputEngine.setOnItemClickListener { _, _, position, _ ->
            val engine = engines.getOrNull(position) ?: return@setOnItemClickListener
            if (!isBinding && engine != viewModel.uiState.value?.translationEngine) {
                viewModel.setTranslationEngine(engine)
            }
        }
        inputPreprocess.setOnItemClickListener { _, _, position, _ ->
            val mode = preprocessModes.getOrNull(position) ?: return@setOnItemClickListener
            if (!isBinding && mode != viewModel.uiState.value?.preprocessMode) {
                viewModel.setPreprocessMode(mode)
            }
        }

        switchDebugCapture.setOnCheckedChangeListener { _, checked ->
            if (!isBinding && checked != viewModel.uiState.value?.debugCaptureEnabled) {
                viewModel.setDebugCaptureEnabled(checked)
            }
        }
    }

    private fun render(state: SettingsUiState) {
        isBinding = true
        try {
            inputSource.setTextSilently(
                languageLabels.getOrNull(languages.indexOf(state.sourceLang))
            )
            inputTarget.setTextSilently(
                languageLabels.getOrNull(languages.indexOf(state.targetLang))
            )
            inputDifficulty.setTextSilently(
                difficultyLabels.getOrNull(difficultyFilters.indexOf(state.difficultyFilter))
            )
            inputEngine.setTextSilently(
                engineLabels.getOrNull(engines.indexOf(state.translationEngine))
            )
            inputPreprocess.setTextSilently(
                preprocessLabels.getOrNull(preprocessModes.indexOf(state.preprocessMode))
            )

            switchDebugCapture.isChecked = state.debugCaptureEnabled

            tvEngineHint.text = getString(state.engineHintRes)
            tvPreprocessHint.text = getString(state.preprocessHintRes)
        } finally {
            isBinding = false
        }
    }

    private fun MaterialAutoCompleteTextView.setTextSilently(text: String?) {
        val value = text.orEmpty()
        if (this.text?.toString() != value) setText(value, false)
    }
}
