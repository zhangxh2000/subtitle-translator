package com.zhangxh.subtitletranslator.ui.common

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import com.zhangxh.subtitletranslator.R

/**
 * 应用内轻提示
 *
 * Android 13+ 未授予通知权限（本应用刻意不申请），或用户关闭了应用通知后，
 * system_server 会以“Suppressing toast ... by user request”为由丢弃系统 Toast。
 * 系统 Toast 可用时保持原生观感，被抑制时用悬浮窗自绘一条，避免提示随通知权限一起消失。
 */
object AppToast {

    private const val TAG = "AppToast"
    private const val SHORT_DURATION_MS = 2000L
    private const val LONG_DURATION_MS = 3500L
    private const val TOP_MARGIN_DP = 16
    private const val SIDE_MARGIN_RATIO = 0.1f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { removeOverlay() }
    private var overlayView: View? = null
    private var overlayWindowManager: WindowManager? = null

    fun show(context: Context, @StringRes textRes: Int, long: Boolean = false) {
        show(context, context.getString(textRes), long)
    }

    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(context, text, long) }
            return
        }
        val appContext = context.applicationContext
        if (NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            Toast.makeText(
                appContext,
                text,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
            return
        }
        Log.i(TAG, "通知不可用，系统会丢弃 Toast，改用悬浮窗提示")
        showOverlay(appContext, text, long)
    }

    private fun showOverlay(context: Context, text: CharSequence, long: Boolean) {
        removeOverlay()
        val density = context.resources.displayMetrics.density
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val screenWidthPx = DisplayMetrics().also {
            windowManager.defaultDisplay.getRealMetrics(it)
        }.widthPixels
        val view = TextView(context).apply {
            setText(text)
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(
                (20 * density).toInt(),
                (12 * density).toInt(),
                (20 * density).toInt(),
                (12 * density).toInt()
            )
            background = context.getDrawable(R.drawable.bg_navigation_status)
            elevation = 6 * density
            maxWidth = (screenWidthPx * (1 - 2 * SIDE_MARGIN_RATIO)).toInt()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (TOP_MARGIN_DP * density).toInt()
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
        mainHandler.postDelayed(dismissRunnable, if (long) LONG_DURATION_MS else SHORT_DURATION_MS)
        Log.d(TAG, "悬浮提示已显示: long=$long")
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacks(dismissRunnable)
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
