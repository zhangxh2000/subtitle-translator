package com.zhangxh.subtitletranslator.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.translator.Language

/**
 * 设置界面
 * 支持选择翻译语言对
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "subtitle_translator_prefs"
        private const val KEY_SOURCE_LANG = "source_lang"
        private const val KEY_TARGET_LANG = "target_lang"

        fun getSourceLang(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SOURCE_LANG, "en") ?: "en"
        }

        fun getTargetLang(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TARGET_LANG, "zh") ?: "zh"
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

    private fun saveLanguage(key: String, value: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }
}
