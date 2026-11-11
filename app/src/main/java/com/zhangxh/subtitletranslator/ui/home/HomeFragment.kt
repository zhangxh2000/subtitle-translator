package com.zhangxh.subtitletranslator.ui.home

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.history.TranslationDayHistory
import com.zhangxh.subtitletranslator.ui.MainViewModel
import com.zhangxh.subtitletranslator.ui.common.HelpBottomSheet
import com.zhangxh.subtitletranslator.ui.history.TranslationHistoryActivity
import com.zhangxh.subtitletranslator.ui.history.TranslationHistoryRow
import com.zhangxh.subtitletranslator.ui.history.TranslationHistoryViewModel

/**
 * 首页
 *
 * 一个主操作：开始字幕翻译；下方展示按天聚合的最近翻译记录。
 * 每天取最后一条成功翻译的字幕原文作为标题，完整记录进入历史页查看。
 */
class HomeFragment : Fragment() {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val historyViewModel: TranslationHistoryViewModel by viewModels()

    private var btnStartService: Button? = null
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyEmpty: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        btnStartService = view.findViewById(R.id.btnStartService)
        historyContainer = view.findViewById(R.id.historyContainer)
        historyEmpty = view.findViewById(R.id.historyEmpty)

        btnStartService?.setOnClickListener { mainViewModel.onStartStopClicked() }
        view.findViewById<View>(R.id.btnProfileAvatar).setOnClickListener {
            findNavController().navigate(R.id.profileFragment)
        }
        view.findViewById<View>(R.id.btnViewHistory).setOnClickListener {
            openHistory()
        }
        view.findViewById<View>(R.id.btnHelp).setOnClickListener {
            HelpBottomSheet().show(parentFragmentManager, "help")
        }

        mainViewModel.serviceRunning.observe(viewLifecycleOwner) { running ->
            renderServiceState(running)
        }
        historyViewModel.days.observe(viewLifecycleOwner) { days ->
            renderHistory(days)
        }
    }

    override fun onResume() {
        super.onResume()
        historyViewModel.refresh()
    }

    private fun renderHistory(days: List<TranslationDayHistory>) {
        historyContainer.removeAllViews()
        val recent = days.take(RECENT_HISTORY_DAYS)
        historyEmpty.isVisible = recent.isEmpty()
        recent.forEachIndexed { index, day ->
            val row = layoutInflater.inflate(
                R.layout.item_translation_day,
                historyContainer,
                false
            )
            TranslationHistoryRow.bind(
                view = row,
                item = day,
                showChevron = true,
                showDivider = index != recent.lastIndex
            ) {
                openHistory(day.dayStart)
            }
            historyContainer.addView(row)
        }
    }

    private fun openHistory(dayStart: Long? = null) {
        val intent = Intent(requireContext(), TranslationHistoryActivity::class.java)
        if (dayStart != null) {
            intent.putExtra(TranslationHistoryActivity.EXTRA_DAY_START, dayStart)
        }
        startActivity(intent)
    }

    private fun renderServiceState(running: Boolean) {
        val context = context ?: return
        val button = btnStartService ?: return
        button.text = getString(
            if (running) R.string.home_stop_translation else R.string.home_start_translation
        )
        button.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                context,
                if (running) R.color.stop_button else R.color.accent
            )
        )
        button.setTextColor(
            ContextCompat.getColor(
                context,
                if (running) R.color.text_primary else R.color.on_accent
            )
        )
    }

    private companion object {
        /** 首页只展示最近几天，完整列表在历史页 */
        const val RECENT_HISTORY_DAYS = 2
    }
}
