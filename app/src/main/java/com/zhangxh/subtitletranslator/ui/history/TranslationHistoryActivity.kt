package com.zhangxh.subtitletranslator.ui.history

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.ui.BaseActivity

/** 完整翻译历史：每天一条聚合记录，最近的一天在最前 */
class TranslationHistoryActivity : BaseActivity() {

    private val viewModel: TranslationHistoryViewModel by viewModels()
    private val adapter = TranslationHistoryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translation_history)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationContentDescription(R.string.action_close)
            setNavigationOnClickListener { finish() }
        }

        val emptyState = findViewById<View>(R.id.historyEmptyState)
        val list = findViewById<RecyclerView>(R.id.historyList).apply {
            layoutManager = LinearLayoutManager(this@TranslationHistoryActivity)
            adapter = this@TranslationHistoryActivity.adapter
        }

        viewModel.days.observe(this) { days ->
            adapter.submit(days)
            emptyState.isVisible = days.isEmpty()
            list.isVisible = days.isNotEmpty()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}
