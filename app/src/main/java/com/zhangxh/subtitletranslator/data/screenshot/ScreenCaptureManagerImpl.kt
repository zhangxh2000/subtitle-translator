package com.zhangxh.subtitletranslator.data.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.zhangxh.subtitletranslator.domain.screenshot.IScreenCaptureManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 屏幕截图管理器实现
 *
 * 使用 MediaProjection + VirtualDisplay + ImageReader 捕获屏幕。
 *
 * 设计要点（都是踩过的坑）：
 * 1. ImageReader 由 OnImageAvailableListener 持续消费，始终保留最新一帧；
 *    截图时直接取缓存帧，不再依赖“截图那一刻刚好有新帧”。
 * 2. 横竖屏切换时先创建新 ImageReader、resize、attach 新 Surface，
 *    确认新 Surface 真的出帧后才关闭旧 ImageReader；失败则回滚旧 Surface。
 *    直接 close 正在使用的 ImageReader 会让 VirtualDisplay 输出失效，
 *    之后所有截图都拿不到帧，只有再次换尺寸才会恢复。
 * 3. 尺寸状态只在换绑成功（收到首帧）后才提交；失败保留旧尺寸/旧输出。
 * 4. 连续多次拿不到帧时主动重新挂载 Surface、重建输出，避免永久卡死。
 */
class ScreenCaptureManagerImpl : IScreenCaptureManager {

    private companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val MAX_IMAGES = 2

        /** 正常截图等待新帧的时间；有缓存帧时通常不需要等满 */
        private const val CAPTURE_WAIT_TIMEOUT_MS = 900L

        /** 换绑 Surface 后等待首帧的时间 */
        private const val REBIND_FIRST_FRAME_TIMEOUT_MS = 1200L

        /** 重新挂载当前 Surface 后等待帧的时间 */
        private const val HEAL_WAIT_TIMEOUT_MS = 600L

        private const val POLL_INTERVAL_MS = 20L

        /** 连续多少次截图拿不到帧后强制重建输出 */
        private const val FORCED_REBUILD_EMPTY_THRESHOLD = 2
        private const val FORCED_REBUILD_MIN_INTERVAL_MS = 1500L

        /** 重新挂载同一 Surface 的最小间隔，避免每次截图都折腾显示管线 */
        private const val SURFACE_HEAL_MIN_INTERVAL_MS = 400L

        /** 屏幕配置变化后延迟重建，避开旋转动画/窗口过渡 */
        private const val CONFIG_REBUILD_DEBOUNCE_MS = 350L
    }

    private data class CaptureTarget(
        val width: Int,
        val height: Int,
        val density: Int,
        val source: String
    )

    /** 保护所有可变状态；回调、截图线程、主线程都会访问 */
    private val stateLock = Any()

    /** 串行化截图与换绑，避免两个协程同时动 ImageReader/VirtualDisplay */
    private val operationMutex = Mutex()

    private var implScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scheduledRebuild: Job? = null

    private var context: Context? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null

    /** 最新一帧，由 OnImageAvailableListener 持续更新；截图时取走并关闭 */
    private var pendingImage: Image? = null

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    /** 系统上报的捕获内容尺寸（API 34+），比 getRealMetrics 更权威 */
    private var contentWidth: Int = 0
    private var contentHeight: Int = 0

    private var isReleased = true
    private var emptyCaptureCount = 0
    private var lastSurfaceHealAt = 0L
    private var lastForcedRebuildAt = 0L
    private var firstFrameLogged = false
    private var lastFrameCache: LastFrameCache? = null

    private var onProjectionStoppedListener: (() -> Unit)? = null
    private var onDisplayResizedListener: (() -> Unit)? = null

    /**
     * 设置 MediaProjection 停止监听回调
     */
    fun setOnProjectionStoppedListener(listener: () -> Unit) {
        onProjectionStoppedListener = listener
    }

    /**
     * 屏幕方向/尺寸变化后回调
     *
     * 新 Surface 通常要等屏幕内容下一次变化才会出帧；外部可以在回调里
     * 触发一次轻量重绘（悬浮球闪一下），促使系统合成一帧。
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

        override fun onCapturedContentResize(width: Int, height: Int) {
            val attachedWidth: Int
            val attachedHeight: Int
            synchronized(stateLock) {
                contentWidth = width
                contentHeight = height
                attachedWidth = screenWidth
                attachedHeight = screenHeight
            }
            Log.d(
                TAG,
                "系统上报捕获内容尺寸: ${width}x${height}, 当前输出=${attachedWidth}x${attachedHeight}"
            )
            if (width > 0 && height > 0 &&
                (width != attachedWidth || height != attachedHeight)
            ) {
                scheduleSizeRebuild("onCapturedContentResize")
            }
        }
    }

    override fun initialize(
        context: Context,
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        density: Int
    ) {
        Log.d(TAG, "初始化屏幕截图: ${width}x${height}, density=$density")
        if (isInitialized()) {
            Log.w(TAG, "重复初始化，先释放旧截图资源")
            releaseInternal()
        }
        implScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val thread = HandlerThread("ScreenCaptureFrames").apply { start() }
        val handler = Handler(thread.looper)
        val reader: ImageReader
        val display: VirtualDisplay?
        try {
            reader = createReader(width, height, handler)
            synchronized(stateLock) {
                this.context = context.applicationContext
                this.mediaProjection = mediaProjection
                this.frameThread = thread
                this.frameHandler = handler
                this.lastFrameCache = LastFrameCache(width, height)
                this.screenWidth = width
                this.screenHeight = height
                this.screenDensity = density
                this.contentWidth = 0
                this.contentHeight = 0
                this.isReleased = false
                this.emptyCaptureCount = 0
                this.lastSurfaceHealAt = 0L
                this.lastForcedRebuildAt = 0L
                this.firstFrameLogged = false
                this.pendingImage?.close()
                this.pendingImage = null
                this.imageReader = reader
            }

            mediaProjection.registerCallback(
                projectionCallback,
                Handler(Looper.getMainLooper())
            )
            display = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(Looper.getMainLooper())
            )
            synchronized(stateLock) { virtualDisplay = display }
        } catch (e: Exception) {
            Log.e(TAG, "初始化截图失败: ${width}x${height}", e)
            thread.quitSafely()
            throw e
        }

        if (display == null) {
            Log.e(TAG, "创建 VirtualDisplay 返回 null，截图会话不可用")
        } else {
            Log.d(
                TAG,
                "截图初始化完成: 输出=${width}x${height}, " +
                    "reader=${reader.hashCode()}, display=${display.hashCode()}"
            )
        }
    }

    /**
     * 持续消费 ImageReader，始终保留最新一帧
     *
     * 保留而不是立刻关闭，是为了静止画面时截图也能拿到刚出过的那一帧。
     * listener 绑定到自己的 reader：reader 被替换后，旧 listener 的帧直接丢弃。
     */
    private fun createReader(width: Int, height: Int, handler: Handler): ImageReader {
        val reader = ImageReader.newInstance(
            width, height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES
        )
        reader.setOnImageAvailableListener({ source ->
            val image = try {
                source.acquireLatestImage()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "读取屏幕帧失败", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "读取屏幕帧异常", e)
                null
            } ?: return@setOnImageAvailableListener

            synchronized(stateLock) {
                if (isReleased || source !== imageReader) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                pendingImage?.close()
                pendingImage = image
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    Log.d(
                        TAG,
                        "新输出收到首帧: ${image.width}x${image.height}, " +
                            "reader=${source.hashCode()}"
                    )
                }
            }
        }, handler)
        return reader
    }

    /**
     * 获取当前屏幕实际尺寸
     */
    private fun resolveTargetLocked(): CaptureTarget {
        // density 仍然取实时值：显示大小设置、折叠屏切换都可能只变 density
        val windowManager = context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        val density = if (metrics.densityDpi > 0) metrics.densityDpi else screenDensity

        val reportedWidth = contentWidth
        val reportedHeight = contentHeight
        if (reportedWidth > 0 && reportedHeight > 0) {
            return CaptureTarget(reportedWidth, reportedHeight, density, "内容上报")
        }
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return CaptureTarget(metrics.widthPixels, metrics.heightPixels, density, "真实屏幕")
        }
        return CaptureTarget(screenWidth, screenHeight, density, "缓存")
    }

    /**
     * 尺寸变化时换绑输出 Surface
     *
     * 返回换绑后新 Surface 收到的首帧；没有换绑或失败时返回 null。
     * 只有真正收到首帧才提交新尺寸并关闭旧 reader，否则回滚，保证旧输出还能用。
     */
    private suspend fun swapOutputSurface(target: CaptureTarget, reason: String): Image? {
        val display: VirtualDisplay
        val oldReader: ImageReader?
        val oldCache: LastFrameCache?
        val oldWidth: Int
        val oldHeight: Int
        val newReader: ImageReader
        synchronized(stateLock) {
            if (isReleased) return null
            display = virtualDisplay ?: run {
                Log.w(TAG, "换绑跳过: VirtualDisplay 不存在")
                return null
            }
            val handler = frameHandler ?: run {
                Log.w(TAG, "换绑跳过: 帧线程未就绪")
                return null
            }
            oldReader = imageReader
            oldCache = lastFrameCache
            oldWidth = screenWidth
            oldHeight = screenHeight
            // 先清掉旧 Surface 上残留的帧，避免把旧尺寸帧当成新输出首帧
            pendingImage?.close()
            pendingImage = null
            newReader = createReader(target.width, target.height, handler)
            imageReader = newReader
            emptyCaptureCount = 0
            firstFrameLogged = false
        }

        val startedAt = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "开始换绑输出: ${oldWidth}x${oldHeight} -> ${target.width}x${target.height}, " +
                "原因=$reason, 来源=${target.source}, " +
                "oldReader=${oldReader?.hashCode()}, newReader=${newReader.hashCode()}"
        )
        try {
            display.resize(target.width, target.height, target.density)
            display.setSurface(newReader.surface)
        } catch (e: Exception) {
            Log.e(TAG, "换绑输出 Surface 失败，回滚旧输出", e)
            rollbackSurfaceSwap(display, oldReader, oldWidth, oldHeight, newReader)
            return null
        }
        // 新 Surface 通常要等下一次合成为止；闪一下悬浮球促使静止画面出帧
        onDisplayResizedListener?.invoke()

        // 换绑中途被取消也要把状态收拾干净：要么拿到首帧提交，要么回滚。
        // 否则新 reader 已经挂上、旧 reader 还没关，状态会错位。
        val firstFrame = withContext(NonCancellable) {
            takeFrame(REBIND_FIRST_FRAME_TIMEOUT_MS)
        }
        val waitedMs = SystemClock.elapsedRealtime() - startedAt
        if (firstFrame == null) {
            Log.w(TAG, "换绑后 ${waitedMs}ms 内没有收到首帧，回滚旧输出")
            rollbackSurfaceSwap(display, oldReader, oldWidth, oldHeight, newReader)
            return null
        }

        synchronized(stateLock) {
            if (!isReleased) {
                screenWidth = target.width
                screenHeight = target.height
                screenDensity = target.density
                lastFrameCache = LastFrameCache(target.width, target.height)
                lastForcedRebuildAt = SystemClock.elapsedRealtime()
            }
        }
        closeQuietly(oldReader)
        oldCache?.clear()
        Log.d(
            TAG,
            "换绑成功: ${target.width}x${target.height}, 等待首帧=${waitedMs}ms, " +
                "reader=${newReader.hashCode()}"
        )
        return firstFrame
    }

    /**
     * 换绑失败时恢复到旧 Surface
     *
     * 旧 ImageReader 一直保持打开，所以可以安全地重新挂回 VirtualDisplay。
     */
    private fun rollbackSurfaceSwap(
        display: VirtualDisplay,
        oldReader: ImageReader?,
        oldWidth: Int,
        oldHeight: Int,
        newReader: ImageReader
    ) {
        val density = synchronized(stateLock) { screenDensity }
        try {
            display.resize(oldWidth, oldHeight, density)
            display.setSurface(oldReader?.surface)
        } catch (e: Exception) {
            Log.e(TAG, "回滚输出到 ${oldWidth}x${oldHeight} 失败", e)
        }
        val released = synchronized(stateLock) {
            if (!isReleased) {
                imageReader = oldReader
                screenWidth = oldWidth
                screenHeight = oldHeight
                pendingImage?.close()
                pendingImage = null
                firstFrameLogged = false
            }
            isReleased
        }
        closeQuietly(newReader)
        if (released) {
            // 会话已释放：oldReader 不可能是当前输出，必须一起关掉，否则泄漏
            closeQuietly(oldReader)
        }
        Log.d(TAG, "已回滚输出: ${oldWidth}x${oldHeight}")
        onDisplayResizedListener?.invoke()
    }

    /**
     * 检查尺寸变化，必要时安全换绑
     */
    private suspend fun ensureScreenSize(): Image? {
        val target: CaptureTarget
        val currentWidth: Int
        val currentHeight: Int
        synchronized(stateLock) {
            if (isReleased) return null
            target = resolveTargetLocked()
            currentWidth = screenWidth
            currentHeight = screenHeight
        }
        if (target.width == currentWidth && target.height == currentHeight) {
            return null
        }
        Log.d(
            TAG,
            "检测到屏幕尺寸变化: ${currentWidth}x${currentHeight} -> " +
                "${target.width}x${target.height} (来源=${target.source})"
        )
        return swapOutputSurface(target, "尺寸变化")
    }

    /**
     * 连续拿不到帧时重新挂载当前 Surface
     *
     * setSurface(null) + setSurface(surface) 会让显示管线重新配置输出，
     * 用来修复“Surface 还在但 producer 已经不再写入”的状态。
     */
    private suspend fun healSurfaceAndWait(): Image? {
        val canHeal = synchronized(stateLock) {
            val now = SystemClock.elapsedRealtime()
            if (isReleased || virtualDisplay == null || imageReader == null) {
                false
            } else if (now - lastSurfaceHealAt < SURFACE_HEAL_MIN_INTERVAL_MS) {
                false
            } else {
                lastSurfaceHealAt = now
                true
            }
        }
        if (!canHeal) return null

        val display: VirtualDisplay
        val reader: ImageReader
        val width: Int
        val height: Int
        val density: Int
        synchronized(stateLock) {
            if (isReleased) return null
            display = virtualDisplay ?: return null
            reader = imageReader ?: return null
            width = screenWidth
            height = screenHeight
            density = screenDensity
            pendingImage?.close()
            pendingImage = null
            firstFrameLogged = false
        }
        Log.w(TAG, "连续无帧，尝试重新挂载输出 Surface: ${width}x${height}, reader=${reader.hashCode()}")
        try {
            display.setSurface(null)
            display.resize(width, height, density)
            display.setSurface(reader.surface)
        } catch (e: Exception) {
            Log.e(TAG, "重新挂载输出 Surface 失败", e)
            return null
        }
        onDisplayResizedListener?.invoke()
        val frame = takeFrame(HEAL_WAIT_TIMEOUT_MS)
        if (frame == null) {
            Log.w(TAG, "重新挂载后 ${HEAL_WAIT_TIMEOUT_MS}ms 内仍未收到帧")
        }
        return frame
    }

    /**
     * 等待并取走最新帧
     */
    private suspend fun takeFrame(timeoutMs: Long): Image? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            synchronized(stateLock) {
                if (isReleased) return null
                pendingImage?.let { image ->
                    pendingImage = null
                    return image
                }
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            delay(minOf(POLL_INTERVAL_MS, remaining))
        }
    }

    private fun shouldForceRebuild(): Boolean = synchronized(stateLock) {
        val now = SystemClock.elapsedRealtime()
        if (emptyCaptureCount < FORCED_REBUILD_EMPTY_THRESHOLD) return false
        if (now - lastForcedRebuildAt < FORCED_REBUILD_MIN_INTERVAL_MS) return false
        lastForcedRebuildAt = now
        true
    }

    override suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        if (!isInitialized()) {
            Log.e(TAG, "截图失败: 未初始化")
            return@withContext null
        }

        operationMutex.lock()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val attachedWidth: Int
            val attachedHeight: Int
            val emptyCount: Int
            synchronized(stateLock) {
                if (isReleased) return@withContext null
                attachedWidth = screenWidth
                attachedHeight = screenHeight
                emptyCount = emptyCaptureCount
            }
            Log.d(TAG, "开始截图: 当前输出=${attachedWidth}x${attachedHeight}, 连续无帧=$emptyCount")

            // 1. 尺寸变化优先处理；换绑成功会直接带回新 Surface 的首帧
            var image = ensureScreenSize()

            // 2. 取持续消费缓存的最新帧
            if (image == null) {
                image = takeFrame(CAPTURE_WAIT_TIMEOUT_MS)
            }

            // 3. 没有帧：重新挂载 Surface，再等一轮
            if (image == null) {
                image = healSurfaceAndWait()
            }

            // 4. 仍然没有帧且连续失败：重建输出（同尺寸也可以）
            if (image == null && shouldForceRebuild()) {
                val target = synchronized(stateLock) { resolveTargetLocked() }
                Log.w(TAG, "连续多次无帧，强制重建输出: ${target.width}x${target.height}")
                image = swapOutputSurface(target, "连续空帧自愈")
                if (image == null) {
                    image = takeFrame(HEAL_WAIT_TIMEOUT_MS)
                }
            }

            if (image == null) {
                val attempts = synchronized(stateLock) { ++emptyCaptureCount }
                val cached = synchronized(stateLock) {
                    if (isReleased) null else lastFrameCache?.copy()
                }
                if (cached != null) {
                    Log.d(
                        TAG,
                        "没有新屏幕帧，复用缓存帧: ${cached.width}x${cached.height}, " +
                            "连续无帧=$attempts"
                    )
                    return@withContext cached
                }
                Log.w(
                    TAG,
                    "等待屏幕帧超时: 连续无帧=$attempts, " +
                        "耗时=${SystemClock.elapsedRealtime() - startedAt}ms, 未缓存任何帧"
                )
                return@withContext null
            }

            synchronized(stateLock) { emptyCaptureCount = 0 }
            val bitmap = try {
                convertImage(image)
            } finally {
                image.close()
            }
            if (bitmap == null) {
                Log.w(TAG, "屏幕帧转换 Bitmap 失败, 耗时=${SystemClock.elapsedRealtime() - startedAt}ms")
                return@withContext null
            }

            val cache = synchronized(stateLock) {
                if (isReleased) null else lastFrameCache
            }
            if (cache != null) {
                val copy = cache.updateAndCopy(bitmap)
                Log.d(
                    TAG,
                    "截图成功: ${bitmap.width}x${bitmap.height}, " +
                        "耗时=${SystemClock.elapsedRealtime() - startedAt}ms"
                )
                return@withContext copy ?: bitmap
            }
            Log.d(
                TAG,
                "截图成功(未缓存): ${bitmap.width}x${bitmap.height}, " +
                    "耗时=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
            return@withContext bitmap
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "截图失败", e)
            return@withContext null
        } finally {
            operationMutex.unlock()
        }
    }

    /**
     * 把 Image 转成 Bitmap
     *
     * 宽度可能包含行填充（rowStride / pixelStride），需要裁剪到实际宽度；
     * 高度用 Image 自己的高度，避免尺寸切换瞬间用错 screenHeight。
     */
    private fun convertImage(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: run {
            Log.w(TAG, "屏幕帧没有图像平面")
            return null
        }
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0 || image.height <= 0) {
            Log.w(
                TAG,
                "屏幕帧参数异常: size=${image.width}x${image.height}, " +
                    "rowStride=$rowStride, pixelStride=$pixelStride"
            )
            return null
        }
        val rawWidth = rowStride / pixelStride
        return try {
            val raw = Bitmap.createBitmap(rawWidth, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            raw.copyPixelsFromBuffer(buffer)
            if (rawWidth == image.width) {
                raw
            } else {
                val trimmed = Bitmap.createBitmap(
                    image.width,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                Canvas(trimmed).drawBitmap(raw, 0f, 0f, null)
                raw.recycle()
                trimmed
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "屏幕帧转换失败: size=${image.width}x${image.height}, " +
                    "rowStride=$rowStride, pixelStride=$pixelStride",
                e
            )
            null
        }
    }

    override fun release() {
        releaseInternal()
        // 注意：这里不调用 mediaProjection?.stop()
        // MediaProjection 的生命周期由外部（FloatingButtonService）管理
    }

    /**
     * 释放截图资源，不操作 MediaProjection 本身
     */
    private fun releaseInternal() {
        val reader: ImageReader?
        val display: VirtualDisplay?
        val projection: MediaProjection?
        val thread: HandlerThread?
        val cache: LastFrameCache?
        synchronized(stateLock) {
            if (isReleased && imageReader == null && virtualDisplay == null) {
                scheduledRebuild?.cancel()
                scheduledRebuild = null
                implScope.cancel()
                return
            }
            isReleased = true
            reader = imageReader
            display = virtualDisplay
            projection = mediaProjection
            thread = frameThread
            cache = lastFrameCache

            imageReader = null
            virtualDisplay = null
            mediaProjection = null
            frameThread = null
            frameHandler = null
            lastFrameCache = null
            pendingImage?.close()
            pendingImage = null
            scheduledRebuild?.cancel()
            scheduledRebuild = null
        }
        try {
            display?.release()
        } catch (e: Exception) {
            Log.e(TAG, "释放 VirtualDisplay 失败", e)
        }
        try {
            reader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭 ImageReader 失败", e)
        }
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
            Log.e(TAG, "注销 MediaProjection 回调失败", e)
        }
        thread?.quitSafely()
        cache?.clear()
        implScope.cancel()
        Log.d(TAG, "截图资源已释放")
    }

    override fun isInitialized(): Boolean = synchronized(stateLock) {
        !isReleased &&
            mediaProjection != null &&
            virtualDisplay != null &&
            imageReader != null
    }

    /**
     * 屏幕配置变化（旋转、折叠等）时由外部通知
     *
     * 延迟到屏幕稳定后再换绑，避免在旋转动画中间动输出 Surface。
     */
    override fun onDisplayConfigurationChanged() {
        Log.d(TAG, "收到屏幕配置变化通知")
        scheduleSizeRebuild("onDisplayConfigurationChanged")
    }

    private fun scheduleSizeRebuild(reason: String) {
        synchronized(stateLock) {
            if (isReleased) return
        }
        scheduledRebuild?.cancel()
        scheduledRebuild = implScope.launch {
            delay(CONFIG_REBUILD_DEBOUNCE_MS)
            operationMutex.lock()
            try {
                if (isReleasedNow()) return@launch
                val target: CaptureTarget
                val currentWidth: Int
                val currentHeight: Int
                synchronized(stateLock) {
                    target = resolveTargetLocked()
                    currentWidth = screenWidth
                    currentHeight = screenHeight
                }
                if (target.width == currentWidth && target.height == currentHeight) {
                    Log.d(TAG, "预重建跳过: 尺寸未变化 ${currentWidth}x${currentHeight}, 原因=$reason")
                    return@launch
                }
                Log.d(
                    TAG,
                    "预重建开始: 原因=$reason, " +
                        "${currentWidth}x${currentHeight} -> ${target.width}x${target.height}"
                )
                // 预重建没有调用方消费首帧，必须归还缓冲区，否则会永久占住
                // MAX_IMAGES 中的一个槽位，后续 acquireLatestImage 会失败
                swapOutputSurface(target, "预重建:$reason")?.close()
            } finally {
                operationMutex.unlock()
            }
        }
    }

    private fun isReleasedNow(): Boolean = synchronized(stateLock) { isReleased }

    private fun closeQuietly(reader: ImageReader?) {
        if (reader == null) return
        try {
            reader.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 ImageReader 失败", e)
        }
    }
}
