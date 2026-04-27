package com.zhangxh.subtitletranslator.data.screenshot

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private var mMediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0
    private var onProjectionStoppedListener: (() -> Unit)? = null

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
            // 🔴 系统停止录屏（用户取消 / 权限失效 / 系统回收 / 锁屏）
            // 👉 必须在这里释放资源
            release()
            // 通知外部 MediaProjection 已停止
            onProjectionStoppedListener?.invoke()
        }
    }

    override fun initialize(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        density: Int
    ) {
        this.mMediaProjection = mediaProjection
        this.screenWidth = width
        this.screenHeight = height
        this.screenDensity = density

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

    override suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        if (!isInitialized()) {
            Log.e(TAG, "未初始化")
            return@withContext null
        }

        try {
            // 等待一帧图像
            delay(100)

            val image = imageReader?.acquireLatestImage()
                ?: return@withContext null

            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                // 创建 Bitmap
                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // 裁剪到实际屏幕大小
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
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
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mMediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败", e)
        } finally {
            virtualDisplay = null
            imageReader = null
            mMediaProjection = null
        }
    }

    override fun isInitialized(): Boolean {
        return mMediaProjection != null && imageReader != null
    }
}