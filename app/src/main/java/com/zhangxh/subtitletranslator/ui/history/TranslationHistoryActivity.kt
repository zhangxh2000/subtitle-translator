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

/**
 * 翻译记录详情
 *
 * 带 [EXTRA_DAY_START] 时展示某一天的全部记录，标题为「今天/日期翻译 N 次」；
 * 不带参数时（首页「查看全部」）展示所有记录，按时间倒序。
 */
class TranslationHistoryActivity : BaseActivity() {

    companion object {
        const val EXTRA_DAY_START = "extra_day_start"
    }

    private val dayStart: Long? by lazy {
        val currentIntent = intent ?: return@lazy null
        if (currentIntent.hasExtra(EXTRA_DAY_START)) {
            currentIntent.getLongExtra(EXTRA_DAY_START, 0L).takeIf { it > 0L }
        } else {
            null
        }
    }

    private val viewModel: TranslationHistoryDetailViewModel by viewModels {
        TranslationHistoryDetailViewModel.factory(application, dayStart)
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var emptyState: View

    private val adapter: TranslationRecordAdapter by lazy {
        TranslationRecordAdapter(showDate = dayStart == null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translation_history)

        toolbar = findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationContentDescription(R.string.action_close)
            setNavigationOnClickListener { finish() }
            if (dayStart == null) setTitle(R.string.history_all_title)
        }
        emptyState = findViewById(R.id.historyEmptyState)
        list = findViewById<RecyclerView>(R.id.historyList).apply {
            layoutManager = LinearLayoutManager(this@TranslationHistoryActivity)
            adapter = this@TranslationHistoryActivity.adapter
        }

        viewModel.uiState.observe(this) { state ->
            adapter.submit(state.records)
            emptyState.isVisible = !state.loading && state.records.isEmpty()
            list.isVisible = state.records.isNotEmpty()
            updateTitle(state)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun updateTitle(state: HistoryDetailUiState) {
        if (state.loading) return
        val day = state.dayStart ?: run {
            toolbar.setTitle(R.string.history_all_title)
            return
        }
        toolbar.title = TranslationHistoryRow.formatDetailTitle(
            context = this,
            dayStart = day,
            count = state.records.size
        )
    }
}
