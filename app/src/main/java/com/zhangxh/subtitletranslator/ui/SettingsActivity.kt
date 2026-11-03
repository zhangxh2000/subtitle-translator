package com.zhangxh.subtitletranslator.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import com.google.android.material.switchmaterial.SwitchMaterial
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

    private lateinit var spinnerSource: Spinner
    private lateinit var spinnerTarget: Spinner
    private lateinit var spinnerDifficulty: Spinner
    private lateinit var spinnerEngine: Spinner
    private lateinit var spinnerPreprocess: Spinner
    private lateinit var switchDebugCapture: SwitchMaterial
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

        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }
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
        val enabled = AccessibilityAccess.isEnabled(this)
        tvA11yStatus.text = getString(
            if (enabled) R.string.settings_accessibility_granted
            else R.string.settings_accessibility_missing
        )
        tvA11yStatus.setTextColor(
            getColor(if (enabled) R.color.accent else R.color.text_secondary)
        )
    }

    private fun bindViews() {
        spinnerSource = findViewById(R.id.spinnerSource)
        spinnerTarget = findViewById(R.id.spinnerTarget)
        spinnerDifficulty = findViewById(R.id.spinnerDifficulty)
        spinnerEngine = findViewById(R.id.spinnerEngine)
        spinnerPreprocess = findViewById(R.id.spinnerPreprocess)
        switchDebugCapture = findViewById(R.id.switchDebugCapture)
        tvEngineHint = findViewById(R.id.tvEngineHint)
        tvPreprocessHint = findViewById(R.id.tvPreprocessHint)
        tvMediaAccessStatus = findViewById(R.id.tvMediaAccessStatus)
        tvA11yStatus = findViewById(R.id.tvA11yStatus)
    }

    private fun setupAdapters() {
        val languageAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages.map { language ->
                getString(
                    R.string.language_option,
                    getString(language.displayNameRes()),
                    language.name
                )
            }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerSource.adapter = languageAdapter
        spinnerTarget.adapter = languageAdapter

        spinnerDifficulty.adapter = spinnerAdapter(
            difficultyFilters.map { getString(it.labelRes()) }
        )
        spinnerEngine.adapter = spinnerAdapter(engines.map { getString(it.labelRes()) })
        spinnerPreprocess.adapter = spinnerAdapter(
            preprocessModes.map { getString(it.labelRes()) }
        )
    }

    private fun spinnerAdapter(labels: List<String>) = ArrayAdapter(
        this,
        android.R.layout.simple_spinner_item,
        labels
    ).apply {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    private fun setupListeners() {
        spinnerSource.onItemSelectedListener = spinnerListener { position ->
            val language = languages[position]
            if (language.code != viewModel.uiState.value?.sourceLang?.code) {
                viewModel.setSourceLang(language)
            }
        }
        spinnerTarget.onItemSelectedListener = spinnerListener { position ->
            val language = languages[position]
            if (language.code != viewModel.uiState.value?.targetLang?.code) {
                viewModel.setTargetLang(language)
            }
        }
        spinnerDifficulty.onItemSelectedListener = spinnerListener { position ->
            val filter = difficultyFilters[position]
            if (filter != viewModel.uiState.value?.difficultyFilter) {
                viewModel.setDifficultyFilter(filter)
            }
        }
        spinnerEngine.onItemSelectedListener = spinnerListener { position ->
            val engine = engines[position]
            if (engine != viewModel.uiState.value?.translationEngine) {
                viewModel.setTranslationEngine(engine)
            }
        }
        spinnerPreprocess.onItemSelectedListener = spinnerListener { position ->
            val mode = preprocessModes[position]
            if (mode != viewModel.uiState.value?.preprocessMode) {
                viewModel.setPreprocessMode(mode)
            }
        }

        switchDebugCapture.setOnCheckedChangeListener { _, checked ->
            if (!isBinding && checked != viewModel.uiState.value?.debugCaptureEnabled) {
                viewModel.setDebugCaptureEnabled(checked)
            }
        }
    }

    private fun spinnerListener(onSelected: (Int) -> Unit) =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (!isBinding) onSelected(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

    private fun render(state: SettingsUiState) {
        isBinding = true
        try {
            spinnerSource.selectIfNeeded(languages.indexOf(state.sourceLang))
            spinnerTarget.selectIfNeeded(languages.indexOf(state.targetLang))
            spinnerDifficulty.selectIfNeeded(difficultyFilters.indexOf(state.difficultyFilter))
            spinnerEngine.selectIfNeeded(engines.indexOf(state.translationEngine))
            spinnerPreprocess.selectIfNeeded(preprocessModes.indexOf(state.preprocessMode))

            switchDebugCapture.isChecked = state.debugCaptureEnabled

            tvEngineHint.text = getString(state.engineHintRes)
            tvPreprocessHint.text = getString(state.preprocessHintRes)
        } finally {
            isBinding = false
        }
    }

    private fun Spinner.selectIfNeeded(index: Int) {
        val target = index.coerceAtLeast(0)
        if (selectedItemPosition != target) setSelection(target)
    }
}
