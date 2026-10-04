package com.zhangxh.subtitletranslator.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.switchmaterial.SwitchMaterial
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.translator.Language
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import com.zhangxh.subtitletranslator.domain.translator.BaiduCredentials
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficultyFilter

/**
 * 设置界面
 * 支持选择翻译语言对
 */
class SettingsActivity : BaseActivity() {

    companion object {
        private const val PREFS_NAME = "subtitle_translator_prefs"
        private const val KEY_SOURCE_LANG = "source_lang"
        private const val KEY_TARGET_LANG = "target_lang"
        private const val KEY_DIFFICULTY_FILTER = "difficulty_filter"
        private const val KEY_DEBUG_CAPTURE = "debug_capture_enabled"
        private const val KEY_PREPROCESS_MODE = "ocr_preprocess_mode"
        private const val KEY_TRANSLATION_ENGINE = "translation_engine"
        private const val KEY_BAIDU_APP_ID = "baidu_app_id"
        private const val KEY_BAIDU_SECRET = "baidu_secret"

        fun getSourceLang(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SOURCE_LANG, "en") ?: "en"
        }

        fun getTargetLang(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TARGET_LANG, "zh") ?: "zh"
        }

        /** 生词筛选强度，决定「重点词汇」里显示多少词 */
        fun getDifficultyFilter(context: Context): WordDifficultyFilter {
            val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DIFFICULTY_FILTER, null)
            return WordDifficultyFilter.fromName(name)
        }

        private fun saveDifficultyFilter(context: Context, filter: WordDifficultyFilter) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DIFFICULTY_FILTER, filter.name)
                .apply()
        }

        /**
         * 是否保存调试截图（原始截图 / 裁剪区域 / 预处理图）
         *
         * 每次识别都会读一次，所以打开开关后立刻生效，不需要重启服务。
         */
        fun isDebugCaptureEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DEBUG_CAPTURE, false)
        }

        /**
         * OCR 预处理方式
         *
         * 字幕颜色与背景接近时，固定阈值二值化会把两者处理成同色导致文字消失，
         * 这时需要换用灰度或自适应阈值。
         */
        fun getPreprocessMode(context: Context): OcrPreprocessMode {
            val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PREPROCESS_MODE, null)
            return OcrPreprocessMode.fromName(name)
        }

        /**
         * 翻译引擎
         *
         * 端侧 ML Kit 质量一般（字幕口语化时偏直译），在线引擎质量明显更好，
         * 但需要联网，失败时会自动回退到 ML Kit。
         */
        fun getTranslationEngine(context: Context): TranslationEngine {
            val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TRANSLATION_ENGINE, null)
            return TranslationEngine.fromName(name)
        }

        private fun saveTranslationEngine(context: Context, engine: TranslationEngine) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TRANSLATION_ENGINE, engine.name)
                .apply()
        }

        /**
         * 百度翻译凭据
         *
         * 由用户自己申请填写：免费额度按账号计算，内置到包里会随包泄露并被他人耗尽。
         */
        fun getBaiduCredentials(context: Context): BaiduCredentials {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return BaiduCredentials(
                appId = prefs.getString(KEY_BAIDU_APP_ID, "").orEmpty(),
                secret = prefs.getString(KEY_BAIDU_SECRET, "").orEmpty()
            )
        }

        private fun saveBaiduCredentials(context: Context, credentials: BaiduCredentials) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BAIDU_APP_ID, credentials.appId)
                .putString(KEY_BAIDU_SECRET, credentials.secret)
                .apply()
        }

        private fun savePreprocessMode(context: Context, mode: OcrPreprocessMode) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PREPROCESS_MODE, mode.name)
                .apply()
        }

        private fun saveDebugCaptureEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DEBUG_CAPTURE, enabled)
                .apply()
        }
    }

    private lateinit var spinnerSource: Spinner
    private lateinit var spinnerTarget: Spinner
    private val languages = Language.SUPPORTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        spinnerSource = findViewById(R.id.spinnerSource)
        spinnerTarget = findViewById(R.id.spinnerTarget)

        setupSpinners()
        setupDifficultySpinner()
        setupEngineSpinner()
        setupPreprocessSpinner()
        setupDebugCapture()

        findViewById<View>(R.id.btnBack)?.setOnClickListener {
            finish()
        }
    }

    private fun setupSpinners() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages.map { "${it.localName} (${it.name})" }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerSource.adapter = adapter
        spinnerTarget.adapter = adapter

        val currentSource = getSourceLang(this)
        val currentTarget = getTargetLang(this)

        spinnerSource.setSelection(languages.indexOfFirst { it.code == currentSource }.coerceAtLeast(0))
        spinnerTarget.setSelection(languages.indexOfFirst { it.code == currentTarget }.coerceAtLeast(0))

        spinnerSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = languages[position].code
                if (selected == getTargetLang(this@SettingsActivity)) {
                    Toast.makeText(this@SettingsActivity, "源语言和目标语言不能相同", Toast.LENGTH_SHORT).show()
                    return
                }
                saveLanguage(KEY_SOURCE_LANG, selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = languages[position].code
                if (selected == getSourceLang(this@SettingsActivity)) {
                    Toast.makeText(this@SettingsActivity, "源语言和目标语言不能相同", Toast.LENGTH_SHORT).show()
                    return
                }
                saveLanguage(KEY_TARGET_LANG, selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * 生词筛选强度
     *
     * 决定「重点词汇」区显示多少词：宽松多显示一些，严格只显示难词。
     */
    private fun setupDifficultySpinner() {
        val filters = WordDifficultyFilter.entries
        val spinner = findViewById<Spinner>(R.id.spinnerDifficulty)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            filters.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(filters.indexOf(getDifficultyFilter(this)).coerceAtLeast(0))

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveDifficultyFilter(this@SettingsActivity, filters[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * 翻译引擎选择
     *
     * 选中百度时才展开密钥输入框；改完立即生效（引擎与凭据都是每次翻译时读取），
     * 不需要重启服务。
     */
    private fun setupEngineSpinner() {
        val engines = TranslationEngine.entries
        val spinner = findViewById<Spinner>(R.id.spinnerEngine)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, engines.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(engines.indexOf(getTranslationEngine(this)).coerceAtLeast(0))

        val hint = findViewById<TextView>(R.id.tvEngineHint)
        val baiduGroup = findViewById<View>(R.id.baiduConfigGroup)

        fun applyToUi(engine: TranslationEngine) {
            hint.text = describeEngine(engine)
            baiduGroup.visibility = if (engine == TranslationEngine.BAIDU) View.VISIBLE else View.GONE
        }
        applyToUi(getTranslationEngine(this))

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val engine = engines[position]
                saveTranslationEngine(this@SettingsActivity, engine)
                applyToUi(engine)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        setupBaiduCredentials()
    }

    private fun describeEngine(engine: TranslationEngine): String = when (engine) {
        TranslationEngine.ML_KIT ->
            "完全离线、无需任何配置，但质量一般，字幕口语化时容易直译。"
        TranslationEngine.BAIDU ->
            "国内访问快、质量好，需要填自己的 APP ID 与密钥（免费额度按账号计算）。"
        TranslationEngine.GOOGLE ->
            "无需配置、质量好；国内网络可能访问不了，失败时会自动回退到离线引擎。"
    }

    private fun setupBaiduCredentials() {
        val appIdField = findViewById<EditText>(R.id.etBaiduAppId)
        val secretField = findViewById<EditText>(R.id.etBaiduSecret)
        val saved = getBaiduCredentials(this)

        appIdField.setText(saved.appId)
        secretField.setText(saved.secret)

        appIdField.doAfterTextChanged {
            saveBaiduCredentials(this, getBaiduCredentials(this).copy(appId = it?.toString().orEmpty()))
        }
        secretField.doAfterTextChanged {
            saveBaiduCredentials(this, getBaiduCredentials(this).copy(secret = it?.toString().orEmpty()))
        }
    }

    /**
     * OCR 预处理方式
     *
     * 与调试截图配套使用：开启调试截图后能看到"预处理图"，
     * 换一种方式再识别一次就能直接对比哪种效果更好。
     */
    private fun setupPreprocessSpinner() {
        val modes = OcrPreprocessMode.entries
        val spinner = findViewById<Spinner>(R.id.spinnerPreprocess)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            modes.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(modes.indexOf(getPreprocessMode(this)).coerceAtLeast(0))

        val hint = findViewById<TextView>(R.id.tvPreprocessHint)
        hint.text = modes.first { it == getPreprocessMode(this) }.description

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                savePreprocessMode(this@SettingsActivity, modes[position])
                hint.text = modes[position].description
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * 调试截图开关与查看入口
     *
     * 开关立即生效（每次识别时读取），不需要重启服务。
     */
    private fun setupDebugCapture() {
        val switch = findViewById<SwitchMaterial>(R.id.switchDebugCapture)
        switch.isChecked = isDebugCaptureEnabled(this)
        switch.setOnCheckedChangeListener { _, isChecked ->
            saveDebugCaptureEnabled(this, isChecked)
        }

        findViewById<View>(R.id.btnViewDebugCaptures)?.setOnClickListener {
            startActivity(Intent(this, DebugCaptureActivity::class.java))
        }
    }

    private fun saveLanguage(key: String, value: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }
}
