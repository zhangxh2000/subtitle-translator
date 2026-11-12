package com.zhangxh.subtitletranslator.data.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        private const val CAPTURE_WAIT_TIMEOUT_MS = 800L
        private const val CAPTURE_POLL_INTERVAL_MS = 40L
    }

    private var context: Context? = null
    private var mMediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0
    private var onProjectionStoppedListener: (() -> Unit)? = null
    private var onDisplayResizedListener: (() -> Unit)? = null
    private var isReleased = false
    private var lastFrameCache: LastFrameCache? = null

    /**
     * 设置 MediaProjection 停止监听回调
     */
    fun setOnProjectionStoppedListener(listener: () -> Unit) {
        onProjectionStoppedListener = listener
    }

    /**
     * 屏幕方向/尺寸变化后回调
     *
     * 新 Surface 通常要等屏幕内容下一次变化才会出帧；画面静止时刚换完
     * Surface 就截图会一直拿不到帧。外部可以在回调里触发一次轻量重绘。
     */
    fun setOnDisplayResizedListener(listener: () -> Unit) {
        onDisplayResizedListener = listener
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
        lastFrameCache?.clear()
        lastFrameCache = LastFrameCache(width, height)

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
        lastFrameCache?.clear()
        lastFrameCache = LastFrameCache(currentWidth, currentHeight)

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
        // 换 Surface 后旧帧缓存已失效；请求外部刷新一次画面，避免静止画面等不到首帧
        onDisplayResizedListener?.invoke()
    }

    override suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        if (!isInitialized()) {
            Log.e(TAG, "未初始化")
            return@withContext null
        }

        try {
            // 检查屏幕尺寸是否变化，必要时更新 VirtualDisplay
            updateScreenSizeIfNeeded()

            // 完全静止的画面可能不会持续产生新帧；在短时间内轮询等待，
            // 避免偶发 acquireLatestImage() 为空导致 CAPTURE_FAILED。
            val deadline = SystemClock.elapsedRealtime() + CAPTURE_WAIT_TIMEOUT_MS
            var image: Image? = null
            while (image == null && SystemClock.elapsedRealtime() < deadline) {
                delay(CAPTURE_POLL_INTERVAL_MS)
                image = imageReader?.acquireLatestImage()
            }
            if (image == null) {
                // 没有新帧说明画面和上一帧完全一样，直接复用缓存，
                // 不能把它当成截图失败——否则暂停状态下逐秒导航会直接中断。
                val cached = lastFrameCache?.copy()
                if (cached != null) {
                    Log.d(TAG, "没有新屏幕帧，复用上一帧: ${cached.width}x${cached.height}")
                    return@withContext cached
                }
                Log.w(TAG, "等待屏幕帧超时（画面静止且尚未缓存任何帧）")
                return@withContext null
            }

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

                // 原始帧交给缓存持有，静止画面时可直接复用
                val cache = lastFrameCache
                if (cache != null) {
                    cache.update(bitmap)
                } else {
                    bitmap.recycle()
                }

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
            lastFrameCache?.clear()
            lastFrameCache = null
        }
    }

    override fun isInitialized(): Boolean {
        return !isReleased && mMediaProjection != null && imageReader != null
    }
}
