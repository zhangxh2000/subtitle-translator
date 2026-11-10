package com.zhangxh.subtitletranslator.ui.home

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.ui.MainViewModel
import com.zhangxh.subtitletranslator.ui.common.HelpBottomSheet
import com.zhangxh.subtitletranslator.ui.learning.LearningViewModel

/**
 * 首页
 *
 * 一个主操作：开始字幕翻译；下方只保留今日学习概览。
 * 翻译历史已移除，学习数据全部来自本地词库。
 */
class HomeFragment : Fragment() {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val learningViewModel: LearningViewModel by viewModels()

    private var btnStartService: Button? = null
    private var tvServiceState: TextView? = null
    private var tvCollectedCount: TextView? = null
    private var tvTodayReview: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        btnStartService = view.findViewById(R.id.btnStartService)
        tvServiceState = view.findViewById(R.id.tvServiceState)
        tvCollectedCount = view.findViewById(R.id.tvCollectedCount)
        tvTodayReview = view.findViewById(R.id.tvTodayReview)

        btnStartService?.setOnClickListener { mainViewModel.onStartStopClicked() }
        view.findViewById<View>(R.id.btnProfileAvatar).setOnClickListener {
            findNavController().navigate(R.id.profileFragment)
        }
        view.findViewById<View>(R.id.btnViewPlan).setOnClickListener {
            findNavController().navigate(R.id.learningFragment)
        }
        view.findViewById<View>(R.id.btnHelp).setOnClickListener {
            HelpBottomSheet().show(parentFragmentManager, "help")
        }

        mainViewModel.serviceRunning.observe(viewLifecycleOwner) { running ->
            renderServiceState(running)
        }
        learningViewModel.stats.observe(viewLifecycleOwner) { stats ->
            tvCollectedCount?.text = stats.total.toString()
            tvTodayReview?.text = getString(
                R.string.learning_progress_value,
                stats.reviewedToday,
                stats.dailyGoal
            )
        }
    }

    override fun onResume() {
        super.onResume()
        learningViewModel.refresh()
    }

    private fun renderServiceState(running: Boolean) {
        val context = context ?: return
        val button = btnStartService ?: return
        tvServiceState?.visibility = if (running) View.VISIBLE else View.GONE
        button.text = getString(
            if (running) R.string.main_stop_service else R.string.main_start_service
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
}
