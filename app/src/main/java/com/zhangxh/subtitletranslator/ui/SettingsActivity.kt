package com.zhangxh.subtitletranslator.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.switchmaterial.SwitchMaterial
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import com.zhangxh.subtitletranslator.domain.translator.Language
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficultyFilter

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
    private lateinit var etBaiduAppId: EditText
    private lateinit var etBaiduSecret: EditText
    private lateinit var switchDebugCapture: SwitchMaterial
    private lateinit var tvEngineHint: TextView
    private lateinit var tvPreprocessHint: TextView
    private lateinit var baiduConfigGroup: View

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
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btnViewDebugCaptures)?.setOnClickListener {
            startActivity(Intent(this, DebugCaptureActivity::class.java))
        }
    }

    private fun bindViews() {
        spinnerSource = findViewById(R.id.spinnerSource)
        spinnerTarget = findViewById(R.id.spinnerTarget)
        spinnerDifficulty = findViewById(R.id.spinnerDifficulty)
        spinnerEngine = findViewById(R.id.spinnerEngine)
        spinnerPreprocess = findViewById(R.id.spinnerPreprocess)
        etBaiduAppId = findViewById(R.id.etBaiduAppId)
        etBaiduSecret = findViewById(R.id.etBaiduSecret)
        switchDebugCapture = findViewById(R.id.switchDebugCapture)
        tvEngineHint = findViewById(R.id.tvEngineHint)
        tvPreprocessHint = findViewById(R.id.tvPreprocessHint)
        baiduConfigGroup = findViewById(R.id.baiduConfigGroup)
    }

    private fun setupAdapters() {
        val languageAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages.map { "${it.localName} (${it.name})" }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerSource.adapter = languageAdapter
        spinnerTarget.adapter = languageAdapter

        spinnerDifficulty.adapter = spinnerAdapter(difficultyFilters.map { it.label })
        spinnerEngine.adapter = spinnerAdapter(engines.map { it.label })
        spinnerPreprocess.adapter = spinnerAdapter(preprocessModes.map { it.label })
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

        etBaiduAppId.doAfterTextChanged { editable ->
            val text = editable?.toString().orEmpty()
            if (!isBinding && text != viewModel.uiState.value?.baiduCredentials?.appId) {
                viewModel.setBaiduAppId(text)
            }
        }
        etBaiduSecret.doAfterTextChanged { editable ->
            val text = editable?.toString().orEmpty()
            if (!isBinding && text != viewModel.uiState.value?.baiduCredentials?.secret) {
                viewModel.setBaiduSecret(text)
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

            etBaiduAppId.setTextIfChanged(state.baiduCredentials.appId)
            etBaiduSecret.setTextIfChanged(state.baiduCredentials.secret)
            switchDebugCapture.isChecked = state.debugCaptureEnabled

            tvEngineHint.text = state.engineHint
            baiduConfigGroup.visibility = if (state.showBaiduConfig) View.VISIBLE else View.GONE
            tvPreprocessHint.text = state.preprocessHint
        } finally {
            isBinding = false
        }
    }

    private fun Spinner.selectIfNeeded(index: Int) {
        val target = index.coerceAtLeast(0)
        if (selectedItemPosition != target) setSelection(target)
    }

    private fun EditText.setTextIfChanged(text: String) {
        if (this.text.toString() != text) {
            setText(text)
            setSelection(text.length)
        }
    }
}
