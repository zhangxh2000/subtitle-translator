package com.zhangxh.subtitletranslator.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.zhangxh.subtitletranslator.R

/**
 * 应用内轻提示
 *
 * 系统只在通知关闭且应用不在前台时丢弃 Toast（NotificationManagerService.checkCanEnqueueToast），
 * 悬浮球场景的提示都由后台 Service 发出，正好命中这条限制。
 * 因此按调用方主动分流：Activity 上下文走系统 Toast，其余场景用悬浮窗自绘提示。
 *
 * 自绘提示按 SystemUI 的 text_toast 复刻：布局、配色、应用图标、进出场动画都与系统 Toast 一致；
 * 配色依赖深色 DeviceDefault 主题解析，与 SystemUI 渲染 Toast 的主题相同。
 */
object AppToast {

    private const val TAG = "AppToast"
    private const val SHORT_DURATION_MS = 2000L
    private const val LONG_DURATION_MS = 3500L

    /** 系统 Toast 距底部系统栏的偏移（frameworks/base 的 toast_y_offset） */
    private const val BOTTOM_OFFSET_DP = 48

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { dismissWithAnimation() }
    private var overlayView: View? = null
    private var overlayWindowManager: WindowManager? = null
    private var overlayAnimator: AnimatorSet? = null

    fun show(context: Context, @StringRes textRes: Int, long: Boolean = false) {
        show(context, context.getString(textRes), long)
    }

    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(context, text, long) }
            return
        }
        val activity = context.findActivity()
        if (activity != null) {
            Toast.makeText(
                activity,
                text,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
            return
        }
        showOverlay(context.applicationContext, text, long)
    }

    /** 沿 ContextWrapper 链找宿主 Activity；Service / Application 上下文返回 null */
    private fun Context.findActivity(): Activity? {
        var current: Context = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun showOverlay(context: Context, text: CharSequence, long: Boolean) {
        removeOverlay()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val themedContext = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_toast, null)
        val textView = view.findViewById<TextView>(R.id.toastText)
        val iconView = view.findViewById<ImageView>(R.id.toastIcon)
        textView.text = text
        bindAppIcon(context, iconView)
        // 与 ToastPresenter 的窗口参数一致：底部居中、系统栏内侧、不抢焦点和触摸
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (BOTTOM_OFFSET_DP * context.resources.displayMetrics.density).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(WindowInsets.Type.systemBars())
                setFitInsetsIgnoringVisibility(true)
            }
        }
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "悬浮提示显示失败: canDrawOverlays=${Settings.canDrawOverlays(context)}", e)
            Toast.makeText(
                context,
                text,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
            return
        }
        overlayView = view
        overlayWindowManager = windowManager
        startInAnimation(view, textView, iconView)
        mainHandler.postDelayed(dismissRunnable, if (long) LONG_DURATION_MS else SHORT_DURATION_MS)
        Log.d(TAG, "悬浮提示已显示: long=$long, textLength=${text.length}")
    }

    /** Android 12 起系统 Toast 才对 targetSdk 31+ 的应用显示带徽章的图标 */
    private fun bindAppIcon(context: Context, iconView: ImageView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            iconView.isVisible = false
            return
        }
        runCatching {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(context.packageName, 0)
            iconView.setImageDrawable(
                packageManager.getUserBadgedIcon(
                    packageManager.getApplicationIcon(appInfo),
                    Process.myUserHandle()
                )
            )
            iconView.contentDescription = appInfo.loadLabel(packageManager)
        }.onFailure {
            Log.w(TAG, "加载应用图标失败，隐藏图标", it)
            iconView.isVisible = false
        }
    }

    /** 进场动画参数与 SystemUI 的 ToastDefaultAnimation.toastIn 一致 */
    private fun startInAnimation(view: View, textView: TextView, iconView: ImageView) {
        val linear = LinearInterpolator()
        val scaleInterpolator = PathInterpolator(0f, 0f, 0f, 1f)
        // 先清零，避免动画首帧闪出完整内容
        textView.alpha = 0f
        iconView.alpha = 0f
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.9f, 1f).apply {
                    interpolator = scaleInterpolator
                    duration = 333
                },
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.9f, 1f).apply {
                    interpolator = scaleInterpolator
                    duration = 333
                },
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply {
                    interpolator = linear
                    duration = 66
                },
                ObjectAnimator.ofFloat(textView, View.ALPHA, 0f, 1f).apply {
                    interpolator = linear
                    duration = 283
                    startDelay = 50
                },
                ObjectAnimator.ofFloat(iconView, View.ALPHA, 0f, 1f).apply {
                    interpolator = linear
                    duration = 283
                    startDelay = 50
                }
            )
        }
        overlayAnimator = set
        set.start()
    }

    /** 退场动画参数与 SystemUI 的 ToastDefaultAnimation.toastOut 一致，结束后再移除窗口 */
    private fun dismissWithAnimation() {
        val view = overlayView ?: return
        val textView = view.findViewById<TextView>(R.id.toastText)
        val iconView = view.findViewById<ImageView>(R.id.toastIcon)
        val linear = LinearInterpolator()
        val scaleInterpolator = PathInterpolator(0.3f, 0f, 1f, 1f)
        val set = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.9f).apply {
                    interpolator = scaleInterpolator
                    duration = 250
                },
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.9f).apply {
                    interpolator = scaleInterpolator
                    duration = 250
                },
                ObjectAnimator.ofFloat(view, "elevation", view.elevation, 0f).apply {
                    interpolator = linear
                    duration = 40
                    startDelay = 150
                },
                ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f).apply {
                    interpolator = linear
                    duration = 100
                    startDelay = 150
                },
                ObjectAnimator.ofFloat(textView, View.ALPHA, 1f, 0f).apply {
                    interpolator = linear
                    duration = 166
                },
                ObjectAnimator.ofFloat(iconView, View.ALPHA, 1f, 0f).apply {
                    interpolator = linear
                    duration = 166
                }
            )
        }
        overlayAnimator?.cancel()
        overlayAnimator = set
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (overlayAnimator === animation) {
                    overlayAnimator = null
                    removeOverlayView()
                }
            }
        })
        set.start()
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacks(dismissRunnable)
        overlayAnimator?.let {
            overlayAnimator = null
            it.cancel()
        }
        removeOverlayView()
    }

    private fun removeOverlayView() {
        val view = overlayView ?: return
        val windowManager = overlayWindowManager
        overlayView = null
        overlayWindowManager = null
        try {
            windowManager?.removeView(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "移除悬浮提示失败: 视图可能已被移除", e)
        }
    }
}
