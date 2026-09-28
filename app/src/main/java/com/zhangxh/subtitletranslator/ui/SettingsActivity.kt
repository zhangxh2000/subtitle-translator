package com.zhangxh.subtitletranslator.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.translator.Language
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
