package com.zhangxh.subtitletranslator.ui.home

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.ui.MainViewModel
import com.zhangxh.subtitletranslator.ui.LanguageSettingsActivity
import com.zhangxh.subtitletranslator.ui.SettingsActivity
import com.zhangxh.subtitletranslator.ui.common.HelpBottomSheet
import com.zhangxh.subtitletranslator.ui.common.shortLabelRes
import com.zhangxh.subtitletranslator.ui.history.HighlightedText
import com.zhangxh.subtitletranslator.ui.history.TranslationHistoryActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页
 *
 * 服务开关、翻译/复习统计和近三天翻译记录。
 * 记录卡片默认展示当天最新一条，左右箭头切换同一天的记录。
 */
class HomeFragment : Fragment() {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by viewModels()

    private lateinit var switchService: MaterialSwitch
    private lateinit var tvServiceTitle: TextView
    private lateinit var ivServiceDot: ImageView
    private lateinit var ivServiceStatus: ImageView
    private lateinit var tvLanguagePair: TextView
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvTodayCount: TextView
    private lateinit var tvTodayDelta: TextView
    private lateinit var tvReviewCount: TextView
    private lateinit var tvReviewDesc: TextView
    private lateinit var tvReviewPercent: TextView
    private lateinit var reviewProgress: CircularProgressIndicator
    private lateinit var dayTabs: View
    private lateinit var tabs: List<TextView>
    private lateinit var recordContent: View
    private lateinit var tvRecordEmpty: TextView
    private lateinit var tvRecordOriginal: TextView
    private lateinit var tvRecordTranslation: TextView
    private lateinit var tvRecordTime: TextView
    private lateinit var btnPrevRecord: View
    private lateinit var btnNextRecord: View

    /** 渲染开关/日期页签时忽略用户回调，避免程序设置状态触发再次启停服务 */
    private var isBinding = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupActions(view)

        mainViewModel.serviceRunning.observe(viewLifecycleOwner) { running ->
            renderServiceState(running)
        }
        homeViewModel.uiState.observe(viewLifecycleOwner) { state ->
            render(state)
        }
    }

    override fun onResume() {
        super.onResume()
        homeViewModel.refresh()
        // 从权限页返回或服务状态变化后，把开关同步到真实状态
        renderServiceState(mainViewModel.serviceRunning.value == true)
    }

    private fun bindViews(view: View) {
        switchService = view.findViewById(R.id.switchService)
        tvServiceTitle = view.findViewById(R.id.tvServiceTitle)
        ivServiceDot = view.findViewById(R.id.ivServiceDot)
        ivServiceStatus = view.findViewById(R.id.ivServiceStatus)
        tvLanguagePair = view.findViewById(R.id.tvLanguagePair)
        tvPermissionStatus = view.findViewById(R.id.tvPermissionStatus)
        tvTodayCount = view.findViewById(R.id.tvTodayCount)
        tvTodayDelta = view.findViewById(R.id.tvTodayDelta)
        tvReviewCount = view.findViewById(R.id.tvReviewCount)
        tvReviewDesc = view.findViewById(R.id.tvReviewDesc)
        tvReviewPercent = view.findViewById(R.id.tvReviewPercent)
        reviewProgress = view.findViewById(R.id.reviewProgress)
        dayTabs = view.findViewById(R.id.dayTabs)
        tabs = listOf(
            view.findViewById(R.id.tabDay0),
            view.findViewById(R.id.tabDay1),
            view.findViewById(R.id.tabDay2)
        )
        recordContent = view.findViewById(R.id.recordContent)
        tvRecordEmpty = view.findViewById(R.id.tvRecordEmpty)
        tvRecordOriginal = view.findViewById(R.id.tvRecordOriginal)
        tvRecordTranslation = view.findViewById(R.id.tvRecordTranslation)
        tvRecordTime = view.findViewById(R.id.tvRecordTime)
        btnPrevRecord = view.findViewById(R.id.btnPrevRecord)
        btnNextRecord = view.findViewById(R.id.btnNextRecord)
    }

    private fun setupActions(view: View) {
        switchService.setOnCheckedChangeListener { _, _ ->
            if (!isBinding) mainViewModel.onStartStopClicked()
        }
        view.findViewById<View>(R.id.btnLanguage).setOnClickListener {
            startActivity(Intent(requireContext(), LanguageSettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.btnCheckPermissions).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.btnHelp).setOnClickListener {
            HelpBottomSheet().show(parentFragmentManager, "help")
        }
        view.findViewById<View>(R.id.btnViewHistory).setOnClickListener {
            openHistory(null)
        }
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { homeViewModel.selectDay(index) }
        }
        btnPrevRecord.setOnClickListener { homeViewModel.showOlderRecord() }
        btnNextRecord.setOnClickListener { homeViewModel.showNewerRecord() }
        view.findViewById<View>(R.id.btnOpenDayDetail).setOnClickListener {
            openCurrentDay()
        }
    }

    private fun renderServiceState(running: Boolean) {
        isBinding = true
        switchService.isChecked = running
        isBinding = false

        tvServiceTitle.setText(
            if (running) R.string.home_service_on else R.string.home_service_off
        )
        ivServiceDot.alpha = if (running) 1f else 0.35f
        val statusColor = ContextCompat.getColor(
            requireContext(),
            if (running) R.color.accent else R.color.text_secondary
        )
        ImageViewCompat.setImageTintList(
            ivServiceStatus,
            ColorStateList.valueOf(statusColor)
        )
        ivServiceStatus.contentDescription = getString(
            if (running) R.string.home_service_on else R.string.home_service_off
        )
    }

    private fun render(state: HomeUiState) {
        tvLanguagePair.text = getString(
            R.string.home_language_pair,
            getString(state.sourceLang.shortLabelRes()),
            getString(state.targetLang.shortLabelRes())
        )

        val permissionsGranted = state.notificationAccess && state.accessibilityEnabled
        tvPermissionStatus.setText(
            if (permissionsGranted) R.string.home_permission_granted
            else R.string.home_permission_missing
        )
        tvPermissionStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (permissionsGranted) R.color.accent else R.color.difficulty_medium_text
            )
        )

        renderStats(state)
        renderDayTabs(state)
        renderRecord(state)
    }

    private fun renderStats(state: HomeUiState) {
        tvTodayCount.text = state.todayTranslated.toString()

        val delta = state.todayDelta
        when {
            delta > 0 -> {
                tvTodayDelta.text = getString(R.string.home_delta_up, delta)
                tvTodayDelta.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.accent)
                )
            }
            delta < 0 -> {
                tvTodayDelta.text = getString(R.string.home_delta_down, -delta)
                tvTodayDelta.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.difficulty_medium_text)
                )
            }
            else -> {
                tvTodayDelta.setText(R.string.home_delta_same)
                tvTodayDelta.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.text_secondary)
                )
            }
        }

        tvReviewCount.text = getString(
            R.string.home_review_count,
            state.reviewedToday,
            state.dailyGoal
        )
        tvReviewPercent.text = getString(R.string.learning_percent_value, state.reviewPercent)
        reviewProgress.setProgressCompat(state.reviewPercent, true)
        tvReviewDesc.setText(
            if (state.reviewRemaining > 0) {
                getString(R.string.home_review_remaining, state.reviewRemaining)
            } else {
                getString(R.string.home_review_done)
            }
        )
    }

    private fun renderDayTabs(state: HomeUiState) {
        val visible = state.dayStarts.size == tabs.size
        dayTabs.isVisible = visible
        if (!visible) return

        val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
        tabs.forEachIndexed { index, tab ->
            val dayStart = state.dayStarts[index]
            val date = dateFormat.format(Date(dayStart))
            val labelRes = when (index) {
                0 -> R.string.home_day_today
                1 -> R.string.home_day_yesterday
                else -> R.string.home_day_before
            }
            tab.text = getString(labelRes, date)
            val selected = index == state.selectedDayIndex
            tab.setBackgroundResource(
                if (selected) R.drawable.bg_day_tab_selected
                else R.drawable.bg_day_tab_unselected
            )
            tab.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (selected) R.color.on_accent else R.color.text_secondary
                )
            )
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun renderRecord(state: HomeUiState) {
        val record = state.selectedRecord
        val hasRecords = state.loading.not() && record != null
        recordContent.isVisible = hasRecords
        tvRecordEmpty.isVisible = state.loading.not() && record == null
        if (!hasRecords || record == null) return

        tvRecordOriginal.text = HighlightedText.build(
            requireContext(),
            record.originalText,
            record.highlightRanges
        )
        tvRecordTranslation.text = record.translatedText
        tvRecordTranslation.isVisible = record.translatedText.isNotBlank()
        tvRecordTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(record.translatedAt))

        val canShowOlder = state.selectedRecordIndex < state.records.lastIndex
        val canShowNewer = state.selectedRecordIndex > 0
        btnPrevRecord.isEnabled = canShowOlder
        btnPrevRecord.alpha = if (canShowOlder) 1f else 0.3f
        btnNextRecord.isEnabled = canShowNewer
        btnNextRecord.alpha = if (canShowNewer) 1f else 0.3f
    }

    private fun openCurrentDay() {
        val state = homeViewModel.uiState.value ?: return
        val dayStart = state.dayStarts.getOrNull(state.selectedDayIndex) ?: return
        openHistory(dayStart)
    }

    private fun openHistory(dayStart: Long?) {
        val intent = Intent(requireContext(), TranslationHistoryActivity::class.java)
        if (dayStart != null) {
            intent.putExtra(TranslationHistoryActivity.EXTRA_DAY_START, dayStart)
        }
        startActivity(intent)
    }
}
