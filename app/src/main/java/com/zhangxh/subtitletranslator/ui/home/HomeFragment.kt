package com.zhangxh.subtitletranslator.ui.home

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.ui.MainViewModel

/**
 * 首页
 *
 * 只保留核心入口：服务状态、启动/停止、简短使用说明。
 * 历史/词汇/设置由底部导航承载，不再在首页堆快捷入口。
 */
class HomeFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var statusContainer: View? = null
    private var btnStartService: Button? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusContainer = view.findViewById(R.id.statusContainer)
        btnStartService = view.findViewById(R.id.btnStartService)
        btnStartService?.setOnClickListener { viewModel.onStartStopClicked() }

        viewModel.serviceRunning.observe(viewLifecycleOwner) { running ->
            renderServiceState(running)
        }
    }

    private fun renderServiceState(running: Boolean) {
        val context = context ?: return
        statusContainer?.visibility = if (running) View.VISIBLE else View.GONE
        val button = btnStartService ?: return
        button.text = getString(
            if (running) R.string.main_stop_service else R.string.main_start_service
        )
        // MaterialButton 不能把 backgroundTintList 置空来“恢复默认”，否则会失去填充色
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
