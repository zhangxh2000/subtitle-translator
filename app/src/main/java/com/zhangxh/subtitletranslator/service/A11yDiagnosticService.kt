package com.zhangxh.subtitletranslator.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 无障碍诊断服务
 *
 * 只做一件事：在用户主动触发时，把当前前台应用的窗口/节点树打印到 logcat（tag=A11yDump），
 * 用来确认播放器的进度条是否暴露为可操作的节点（RangeInfo / ACTION_SET_PROGRESS）。
 * 不读取、不上传、不自动处理任何屏幕内容。
 */
class A11yDiagnosticService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yDump"
        private const val MAX_NODES = 2_000
        private val ACTION_SET_PROGRESS_ID =
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id

        @Volatile
        private var instance: A11yDiagnosticService? = null

        /** 从悬浮窗服务触发；无障碍服务未连接时返回 false */
        fun requestDump(): Boolean {
            val service = instance ?: return false
            service.dumpAsync()
            return true
        }
    }

    private var dumpedNodes = 0
    private val seekCandidates = mutableListOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍诊断服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun dumpAsync() {
        Thread {
            try {
                dumpWindows()
            } catch (e: Exception) {
                Log.e(TAG, "导出无障碍节点失败", e)
            }
        }.start()
    }

    private fun dumpWindows() {
        dumpedNodes = 0
        seekCandidates.clear()
        Log.i(TAG, "===== dump start =====")

        val allWindows = windows.orEmpty()
        allWindows.forEachIndexed { index, window ->
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            val root = window.root
            Log.i(
                TAG,
                "window[$index] pkg=${root?.packageName}, type=${window.type}, " +
                    "layer=${window.layer}, active=${window.isActive}, focused=${window.isFocused}, " +
                    "bounds=$bounds"
            )
        }

        // 排除自己的悬浮窗/菜单，优先取当前活动且层级最高的外部窗口
        val candidates = allWindows.filter {
            it.root != null && it.root?.packageName != packageName
        }
        val target = candidates.maxByOrNull {
            (if (it.isActive) 1_000_000 else 0) + it.layer
        } ?: candidates.firstOrNull()

        val root = target?.root ?: rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "没有可导出的窗口")
            Log.i(TAG, "===== dump end =====")
            return
        }

        Log.i(TAG, "target pkg=${root.packageName}, nodes 上限=$MAX_NODES")
        dumpNode(root, depth = 0)

        Log.i(TAG, "===== seek candidates=${seekCandidates.size} =====")
        seekCandidates.forEach { Log.i(TAG, "SEEK_CANDIDATE $it") }
        if (seekCandidates.isEmpty()) {
            Log.w(
                TAG,
                "没有发现 SeekBar/ProgressBar/RangeInfo/ACTION_SET_PROGRESS 节点；" +
                    "如果导出时播放控件已显示，说明进度条是自绘控件，只能考虑坐标手势"
            )
        }
        Log.i(TAG, "===== dump end nodes=$dumpedNodes =====")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        if (dumpedNodes >= MAX_NODES) return
        dumpedNodes++

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val className = node.className?.toString().orEmpty()
        val text = node.text?.toString()?.replace('\n', ' ')?.take(80).orEmpty()
        val description = node.contentDescription?.toString()
            ?.replace('\n', ' ')
            ?.take(80)
            .orEmpty()
        val viewId = node.viewIdResourceName.orEmpty()
        val range = node.rangeInfo
        val rangeText = range?.let {
            " range(type=${it.type},min=${it.min},max=${it.max},current=${it.current})"
        }.orEmpty()
        val actions = node.actions

        val line = "  ".repeat(depth.coerceAtMost(12)) +
            "class=$className text=\"$text\" desc=\"$description\" id=$viewId " +
            "bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] " +
            "clickable=${node.isClickable} focusable=${node.isFocusable} " +
            "scrollable=${node.isScrollable} enabled=${node.isEnabled} " +
            "visible=${node.isVisibleToUser} actions=0x${actions.toString(16)}$rangeText"
        Log.i(TAG, line)

        // ACTION_SET_PROGRESS 是 AccessibilityAction 的 id，不是 actions 位掩码里的位，
        // 必须查 actionList；直接和 actions 做与运算会把普通节点全部误判成候选。
        val supportsSetProgress = node.actionList.any { it.id == ACTION_SET_PROGRESS_ID }
        if (range != null ||
            className.contains("SeekBar", ignoreCase = true) ||
            className.contains("ProgressBar", ignoreCase = true) ||
            supportsSetProgress
        ) {
            seekCandidates += line
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            dumpNode(child, depth + 1)
        }
    }
}
