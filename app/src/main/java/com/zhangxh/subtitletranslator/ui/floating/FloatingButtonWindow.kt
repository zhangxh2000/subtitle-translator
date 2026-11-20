package com.zhangxh.subtitletranslator.ui.floating

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.ui.overlay.OverlayMetricsProvider
import com.zhangxh.subtitletranslator.ui.overlay.OverlayWindowSpec

/**
 * 悬浮球窗口
 *
 * 负责拖动、点击、长按判断和位置持久化；业务动作通过回调交给 Service/Presenter。
 */
class FloatingButtonWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val metricsProvider: OverlayMetricsProvider,
    private val onClick: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onDragStarted: () -> Unit
) {

    companion object {
        private const val TAG = "FloatingButtonWindow"
        private const val PREFS_NAME = "floating_button_prefs"
        private const val KEY_LAST_X = "last_x"
        private const val KEY_LAST_Y = "last_y"

        /** 悬浮球尺寸，与 floating_button.xml 保持一致（快捷菜单定位需要） */
        const val SIZE_DP = 56

        private const val NUDGE_PULSE_HOLD_MS = 40L
        private const val NUDGE_SECOND_PULSE_DELAY_MS = 180L
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    @Volatile
    private var view: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var lastX: Int = prefs.getInt(KEY_LAST_X, 0)
    private var lastY: Int = prefs.getInt(KEY_LAST_Y, 200)

    private var downX = 0f
    private var downY = 0f
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f
    private var isDragging = false
    private var longPressTriggered = false
    private var longPressRunnable: Runnable? = null

    private val clickThreshold = 10
    private val longPressTimeout = 500L
    private val pressScale = 0.88f
    private val pressAnimDuration = 100L

    val x: Int get() = lastX
    val y: Int get() = lastY

    val heightPx: Int
        get() = view?.height?.takeIf { it > 0 }
            ?: (SIZE_DP * metricsProvider.realDisplayMetrics().density).toInt()

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowSpec.WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = lastX
            y = lastY
        }
        val button = LayoutInflater.from(context).inflate(R.layout.floating_button, null)
        setupTouchListener(button, params)
        try {
            windowManager.addView(button, params)
            view = button
            layoutParams = params
            Log.d(TAG, "悬浮球已显示: x=$lastX, y=$lastY")
        } catch (e: Exception) {
            Log.e(TAG, "显示悬浮球失败", e)
        }
    }

    fun hide() {
        val current = view ?: return
        view = null
        layoutParams = null
        try {
            windowManager.removeView(current)
        } catch (e: Exception) {
            Log.w(TAG, "移除悬浮球失败", e)
        }
    }

    /** 保存位置并移除窗口，服务销毁时调用 */
    fun release() {
        hide()
        prefs.edit()
            .putInt(KEY_LAST_X, lastX)
            .putInt(KEY_LAST_Y, lastY)
            .apply()
    }

    fun relayout() {
        val button = view ?: return
        val params = layoutParams ?: return
        val metrics = metricsProvider.realDisplayMetrics()
        val width = button.width.takeIf { it > 0 } ?: (SIZE_DP * metrics.density).toInt()
        val height = button.height.takeIf { it > 0 } ?: (SIZE_DP * metrics.density).toInt()
        params.x = params.x.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        lastX = params.x
        lastY = params.y
        runCatching { windowManager.updateViewLayout(button, params) }
            .onFailure { Log.w(TAG, "旋转后更新悬浮球位置失败", it) }
    }

    /**
     * 截图前轻量刷新，促使静止画面产生新帧
     *
     * VirtualDisplay 的 Surface 换绑/重建是异步合成的，只闪一次可能在
     * 新 Surface 生效前就结束；隔一段时间再闪一次，覆盖这段空窗。
     */
    fun nudge() {
        val button = view ?: return
        button.post {
            pulse(button, 0L)
            pulse(button, NUDGE_SECOND_PULSE_DELAY_MS)
        }
    }

    private fun pulse(button: View, delayMs: Long) {
        button.postDelayed({
            if (view !== button) return@postDelayed
            button.animate().cancel()
            button.alpha = 0.95f
            button.postDelayed({
                if (view === button) button.alpha = 1f
            }, NUDGE_PULSE_HOLD_MS)
        }, delayMs)
    }

    private fun setupTouchListener(button: View, params: WindowManager.LayoutParams) {
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    longPressTriggered = false
                    downX = event.rawX
                    downY = event.rawY
                    touchOffsetX = event.rawX - params.x
                    touchOffsetY = event.rawY - params.y
                    setPressed(button, true)
                    scheduleLongPress(button)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!isDragging &&
                        (kotlin.math.abs(dx) > clickThreshold || kotlin.math.abs(dy) > clickThreshold)
                    ) {
                        isDragging = true
                        cancelLongPress(button)
                        onDragStarted()
                        setPressed(button, false)
                    }
                    if (isDragging) {
                        params.x = (event.rawX - touchOffsetX).toInt()
                        params.y = (event.rawY - touchOffsetY).toInt()
                        lastX = params.x
                        lastY = params.y
                        windowManager.updateViewLayout(button, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    cancelLongPress(button)
                    setPressed(button, false)
                    if (!isDragging && !longPressTriggered) onClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress(button)
                    setPressed(button, false)
                    true
                }

                else -> false
            }
        }
    }

    private fun scheduleLongPress(button: View) {
        val runnable = Runnable {
            if (!isDragging) {
                longPressTriggered = true
                setPressed(button, false)
                onLongPress()
            }
        }
        longPressRunnable = runnable
        button.postDelayed(runnable, longPressTimeout)
    }

    private fun cancelLongPress(button: View) {
        longPressRunnable?.let { button.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun setPressed(button: View, pressed: Boolean) {
        button.isPressed = pressed
        button.animate()
            .scaleX(if (pressed) pressScale else 1f)
            .scaleY(if (pressed) pressScale else 1f)
            .setDuration(pressAnimDuration)
            .start()
    }
}
