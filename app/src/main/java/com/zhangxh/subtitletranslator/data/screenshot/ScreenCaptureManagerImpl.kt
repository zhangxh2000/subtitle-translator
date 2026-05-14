package com.zhangxh.subtitletranslator.data.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.zhangxh.subtitletranslator.domain.screenshot.IScreenCaptureManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 屏幕截图管理器实现
 * 使用 MediaProjection API 捕获屏幕
 */
class ScreenCaptureManagerImpl : IScreenCaptureManager {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val MAX_IMAGES = 2
    }

    private var context: Context? = null
    private var mMediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0
    private var onProjectionStoppedListener: (() -> Unit)? = null
    private var isReleased = false

    /**
     * 设置 MediaProjection 停止监听回调
     */
    fun setOnProjectionStoppedListener(listener: () -> Unit) {
        onProjectionStoppedListener = listener
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.d(TAG, "MediaProjection Callback onStop")
            // 系统停止录屏（用户取消 / 权限失效 / 系统回收 / 锁屏）
            // 释放本地资源，但不 stop MediaProjection（它已经停了）
            releaseInternal()
            // 通知外部 MediaProjection 已停止
            onProjectionStoppedListener?.invoke()
        }
    }

    override fun initialize(
        context: Context,
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        density: Int
    ) {
        this.context = context.applicationContext
        this.mMediaProjection = mediaProjection
        this.screenWidth = width
        this.screenHeight = height
        this.screenDensity = density
        this.isReleased = false

        // 创建 ImageReader 用于接收屏幕图像
        imageReader = ImageReader.newInstance(
            width, height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES
        )

        mediaProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        // 创建 VirtualDisplay
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            Handler(Looper.getMainLooper())
        )

        Log.d(TAG, "屏幕截图初始化完成: ${width}x${height}")
    }

    /**
     * 获取当前屏幕实际尺寸
     */
    private fun getCurrentScreenSize(): Pair<Int, Int> {
        val windowManager = context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = android.util.DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * 检查屏幕尺寸是否变化，如果变化则更新 VirtualDisplay 和 ImageReader
     */
    private fun updateScreenSizeIfNeeded() {
        val (currentWidth, currentHeight) = getCurrentScreenSize()

        // 如果尺寸没有变化，不需要更新
        if (currentWidth == screenWidth && currentHeight == screenHeight) {
            return
        }

        Log.d(TAG, "屏幕尺寸变化: ${screenWidth}x${screenHeight} -> ${currentWidth}x${currentHeight}")

        // 更新尺寸
        screenWidth = currentWidth
        screenHeight = currentHeight

        // 关闭旧的 ImageReader
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭旧 ImageReader 失败", e)
        }

        // 创建新的 ImageReader
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888,
            MAX_IMAGES
        )

        // 调整 VirtualDisplay 尺寸并设置新的 Surface
        virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)
        virtualDisplay?.setSurface(imageReader?.surface)

        Log.d(TAG, "VirtualDisplay 已更新: ${screenWidth}x${screenHeight}")
    }

    override suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        if (!isInitialized()) {
            Log.e(TAG, "未初始化")
            return@withContext null
        }

        try {
            // 检查屏幕尺寸是否变化，必要时更新 VirtualDisplay
            updateScreenSizeIfNeeded()

            // 等待一帧图像
            delay(100)

            val image = imageReader?.acquireLatestImage()
                ?: return@withContext null

            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride

                // 创建 Bitmap（宽度使用 rowStride / pixelStride 包含行填充）
                val bitmapWidth = rowStride / pixelStride
                val bitmap = Bitmap.createBitmap(
                    bitmapWidth,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)

                // 裁剪到实际屏幕大小（创建独立副本，避免共享像素数据被回收后失效）
                val croppedBitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(croppedBitmap).drawBitmap(bitmap, 0f, 0f, null)
                bitmap.recycle()

                Log.d(TAG, "截图成功: ${croppedBitmap.width}x${croppedBitmap.height}")
                return@withContext croppedBitmap
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "截图失败", e)
            null
        }
    }

    override fun release() {
        releaseInternal()
        // 注意：这里不调用 mMediaProjection?.stop()
        // MediaProjection 的生命周期由外部（FloatingButtonService）管理
    }

    /**
     * 内部释放资源，不操作 MediaProjection
     */
    private fun releaseInternal() {
        if (isReleased) return
        isReleased = true

        try {
            virtualDisplay?.release()
            imageReader?.close()
            mMediaProjection?.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败", e)
        } finally {
            virtualDisplay = null
            imageReader = null
            mMediaProjection = null
        }
    }

    override fun isInitialized(): Boolean {
        return !isReleased && mMediaProjection != null && imageReader != null
    }
}
