package com.zhangxh.subtitletranslator.ui.learning

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStats

/**
 * 学习 Tab
 *
 * 学习数据完全来自本地词库；本页只展示每日目标与入口，
 * 具体复习流程交给独立的 WordReviewActivity。
 */
class LearningFragment : Fragment() {

    private val viewModel: LearningViewModel by viewModels()

    private lateinit var tvGoalState: TextView
    private lateinit var tvTodayProgress: TextView
    private lateinit var tvProgressPercent: TextView
    private lateinit var goalProgress: LinearProgressIndicator
    private lateinit var tvDueCount: TextView
    private lateinit var tvMasteredCount: TextView
    private lateinit var tvEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_learning, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvGoalState = view.findViewById(R.id.tvGoalState)
        tvTodayProgress = view.findViewById(R.id.tvTodayProgress)
        tvProgressPercent = view.findViewById(R.id.tvProgressPercent)
        goalProgress = view.findViewById(R.id.goalProgress)
        tvDueCount = view.findViewById(R.id.tvDueCount)
        tvMasteredCount = view.findViewById(R.id.tvMasteredCount)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        view.findViewById<View>(R.id.btnContinueReview).setOnClickListener {
            openReview(ReviewMode.DUE)
        }
        view.findViewById<View>(R.id.btnWordCards).setOnClickListener {
            openReview(ReviewMode.ALL)
        }
        view.findViewById<View>(R.id.btnDueWords).setOnClickListener {
            openReview(ReviewMode.DUE)
        }

        viewModel.stats.observe(viewLifecycleOwner) { stats -> render(stats) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun openReview(mode: ReviewMode) {
        startActivity(
            Intent(requireContext(), WordReviewActivity::class.java)
                .putExtra(WordReviewActivity.EXTRA_MODE, mode.name)
        )
    }

    private fun render(stats: LearningStats) {
        val goal = stats.dailyGoal.coerceAtLeast(1)
        val reviewed = stats.reviewedToday.coerceAtMost(goal)
        val percent = (reviewed * 100 / goal).coerceIn(0, 100)

        tvTodayProgress.text = getString(R.string.learning_progress_value, reviewed, goal)
        tvProgressPercent.text = getString(R.string.learning_percent_value, percent)
        goalProgress.setProgressCompat(percent, true)
        tvGoalState.setText(
            if (stats.reviewedToday >= goal) {
                R.string.learning_goal_done
            } else {
                R.string.learning_in_progress
            }
        )
        tvDueCount.text = stats.due.toString()
        tvMasteredCount.text = stats.mastered.toString()
        tvEmpty.isVisible = stats.total == 0
    }
}
