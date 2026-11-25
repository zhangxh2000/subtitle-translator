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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.zhangxh.subtitletranslator.domain.screenshot.IScreenCaptureManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 屏幕截图管理器实现
 *
 * 使用 MediaProjection + 单个 VirtualDisplay + 单个 ImageReader 捕获屏幕。
 *
 * 设计原则（都是踩过的坑）：
 * 1. 按需取帧，不常驻监听、不持有 Image。截图时先 acquireLatestImage，
 *    没有新帧再轮询等待；MAX_IMAGES 保持 2，任何时候最多只持有一帧，
 *    不会触发 "Unable to acquire a buffer item" 的缓冲耗尽警告。
 * 2. 一次截图最多走三级恢复：等待（中途补一次轻量刷新）→ 换新 Surface →
 *    重建 VirtualDisplay。每级只做一次，不跨调用累积计数状态。
 * 3. 换 Surface 时先创建新 reader、attach、确认收到首帧后才提交尺寸并关闭
 *    旧 reader；失败或取消则回滚旧输出。直接关闭正在使用的 reader 会让
 *    VirtualDisplay 输出永久失效。
 * 4. 尺寸变化（旋转 / 分屏）在系统回调里预先换绑：API 34+ 用
 *    onCapturedContentResize，低版本用 DisplayManager.DisplayListener。
 *    预换绑与截图都在 operationMutex 内执行，换绑时不可能有截图协程持有
 *    旧 reader 的 Image，因此可以立即关闭旧 reader，不需要等首帧。
 * 5. 不返回缓存帧。拿不到帧就是失败，由调用方决定是提示用户还是跳过该步。
 */
class ScreenCaptureManagerImpl : IScreenCaptureManager {

    private companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val MAX_IMAGES = 2

        /** 正常截图等待新帧的时间 */
        private const val CAPTURE_WAIT_TIMEOUT_MS = 900L

        /** 等待帧超过这个时间后补一次轻量刷新，促使静止画面出帧 */
        private const val RENUDGE_AT_MS = 450L

        /** 换绑 Surface 后等待首帧的时间 */
        private const val REBIND_FIRST_FRAME_TIMEOUT_MS = 1200L

        /** 重建 VirtualDisplay 后等待首帧的时间 */
        private const val RECREATE_FIRST_FRAME_TIMEOUT_MS = 1500L

        /** 尺寸变化预换绑的防抖时间，合并旋转/分屏产生的连续回调 */
        private const val PRE_REBIND_DEBOUNCE_MS = 150L

        private const val POLL_INTERVAL_MS = 20L
    }

    private data class CaptureTarget(
        val width: Int,
        val height: Int,
        val density: Int,
        val source: String
    )

    private data class CreatedOutput(
        val display: VirtualDisplay,
        val reader: ImageReader
    )

    /** 保护所有可变状态；主线程回调与截图协程都会访问 */
    private val stateLock = Any()

    /** 串行化截图、换绑和重建，避免两个协程同时操作 Surface */
    private val operationMutex = Mutex()

    private var context: Context? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    /** 系统上报的捕获内容尺寸（API 34+），比 getRealMetrics 更权威 */
    private var contentWidth: Int = 0
    private var contentHeight: Int = 0

    private var isReleased = true

    /** 低版本（API < 34）尺寸变化兜底监听 */
    private var displayManager: DisplayManager? = null

    /** 预换绑协程作用域；与 initialize/release 生命周期一致 */
    private var preRebindScope: CoroutineScope? = null
    private var preRebindJob: Job? = null

    private var onProjectionStoppedListener: (() -> Unit)? = null
    private var onDisplayResizedListener: (() -> Unit)? = null

    /**
     * 设置 MediaProjection 停止监听回调
     */
    fun setOnProjectionStoppedListener(listener: () -> Unit) {
        onProjectionStoppedListener = listener
    }

    /**
     * 输出 Surface 换绑/重建后回调
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
            schedulePreRebind(width, height, "内容上报")
        }
    }

    /**
     * API < 34 没有 onCapturedContentResize，用显示变化监听兜底
     *
     * API 34+ 以捕获内容尺寸为准，避免单应用采集时两个来源打架。
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            val metrics = realDisplayMetrics() ?: return
            schedulePreRebind(metrics.widthPixels, metrics.heightPixels, "屏幕变化")
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
        synchronized(stateLock) {
            this.context = context.applicationContext
            this.mediaProjection = mediaProjection
            this.screenWidth = width
            this.screenHeight = height
            this.screenDensity = density
            this.contentWidth = 0
            this.contentHeight = 0
            this.virtualDisplay = null
            this.imageReader = null
            this.isReleased = false
            this.preRebindScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        try {
            mediaProjection.registerCallback(
                projectionCallback,
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.e(TAG, "注册 MediaProjection 回调失败", e)
            synchronized(stateLock) {
                this.mediaProjection = null
                this.isReleased = true
                preRebindScope?.cancel()
                preRebindScope = null
            }
            throw e
        }

        val manager = context.getSystemService(DisplayManager::class.java)
        if (manager != null) {
            try {
                manager.registerDisplayListener(
                    displayListener,
                    Handler(Looper.getMainLooper())
                )
                synchronized(stateLock) {
                    this.displayManager = manager
                }
            } catch (e: Exception) {
                Log.w(TAG, "注册显示变化监听失败，低版本旋转只能靠截图时检测", e)
            }
        }

        val target = CaptureTarget(width, height, density, "初始化")
        if (createDisplayAndReader(mediaProjection, target) == null) {
            Log.e(TAG, "初始化未创建出截图输出，后续截图会再次尝试")
        }
    }

    override suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                captureWithRecovery()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "截图失败", e)
                null
            }
        }
    }

    /**
     * 捕获流程：尺寸换绑 → 等待帧 → 换 Surface → 重建 VirtualDisplay
     *
     * 调用方已持有 operationMutex。
     */
    private suspend fun captureWithRecovery(): Bitmap? {
        val startedAt = SystemClock.elapsedRealtime()
        if (isReleasedNow()) {
            Log.e(TAG, "截图失败: 未初始化或已释放")
            return null
        }

        val target = resolveTarget()
        val (attachedWidth, attachedHeight) = attachedSize()
        Log.d(
            TAG,
            "开始截图: 当前输出=${attachedWidth}x${attachedHeight}, " +
                "目标=${target.width}x${target.height}(来源=${target.source})"
        )
        if (target.width <= 0 || target.height <= 0) {
            Log.e(TAG, "截图失败: 目标尺寸非法 ${target.width}x${target.height}")
            return null
        }
        if (!ensureDisplay(target)) {
            Log.e(TAG, "截图失败: 截图输出不可用")
            return null
        }

        // 级别 1：尺寸变化 → 换绑，新 Surface 的首帧就是本次截图结果
        val (currentWidth, currentHeight) = attachedSize()
        if (target.width != currentWidth || target.height != currentHeight) {
            rebindOutput(target, "尺寸变化")?.let {
                return finish(it, startedAt, "尺寸换绑首帧")
            }
            Log.w(TAG, "尺寸换绑未成功，继续使用旧输出")
        }

        // 级别 2a：等待新帧，中途补一次轻量刷新
        awaitFrame(CAPTURE_WAIT_TIMEOUT_MS, RENUDGE_AT_MS)?.let {
            return finish(it, startedAt, "正常帧")
        }

        // 级别 2b：换一个新 Surface 重建输出
        Log.w(TAG, "等待 ${CAPTURE_WAIT_TIMEOUT_MS}ms 没有新帧，换绑新 Surface 重建输出")
        rebindOutput(target, "空帧自愈")?.let {
            return finish(it, startedAt, "自愈首帧")
        }

        // 级别 3：换 Surface 也不行，重建整个 VirtualDisplay
        Log.w(TAG, "换新 Surface 仍无首帧，重建 VirtualDisplay")
        recreateDisplay(target)?.let {
            return finish(it, startedAt, "会话重建首帧")
        }

        Log.w(
            TAG,
            "截图失败: 三级恢复后仍无屏幕帧, " +
                "耗时=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
        return null
    }

    /** 转换并关闭帧，返回给调用方的 Bitmap 由调用方负责回收 */
    private fun finish(image: Image, startedAt: Long, source: String): Bitmap? {
        val bitmap = try {
            convertImage(image)
        } finally {
            image.close()
        }
        if (bitmap == null) {
            Log.w(
                TAG,
                "屏幕帧转换 Bitmap 失败（$source）, " +
                    "耗时=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
            return null
        }
        Log.d(
            TAG,
            "截图成功（$source）: ${bitmap.width}x${bitmap.height}, " +
                "耗时=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
        return bitmap
    }

    /**
     * 换绑输出 Surface
     *
     * 返回新 Surface 收到的首帧；没有换绑或失败时返回 null。
     * 只有真正收到首帧才提交新尺寸并关闭旧 reader，否则回滚，保证旧输出还能用。
     */
    private suspend fun rebindOutput(target: CaptureTarget, reason: String): Image? {
        val display: VirtualDisplay
        val oldReader: ImageReader?
        val oldWidth: Int
        val oldHeight: Int
        val oldDensity: Int
        val newReader: ImageReader
        synchronized(stateLock) {
            if (isReleased) return null
            display = virtualDisplay ?: run {
                Log.w(TAG, "换绑跳过: VirtualDisplay 不存在")
                return null
            }
            oldReader = imageReader
            oldWidth = screenWidth
            oldHeight = screenHeight
            oldDensity = screenDensity
            newReader = ImageReader.newInstance(
                target.width,
                target.height,
                PixelFormat.RGBA_8888,
                MAX_IMAGES
            )
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
            Log.e(TAG, "换绑输出 Surface 失败，恢复旧输出", e)
            restoreOutput(display, oldReader, oldWidth, oldHeight, oldDensity)
            closeQuietly(newReader)
            return null
        }
        // 新 Surface 通常要等下一次合成为止；闪一下悬浮球促使静止画面出帧
        onDisplayResizedListener?.invoke()

        // 换绑中途被取消也要先回滚，否则新 Surface 已挂上、状态还指向旧 reader
        val firstFrame = try {
            awaitFrameOn(newReader, REBIND_FIRST_FRAME_TIMEOUT_MS)
        } catch (e: CancellationException) {
            Log.w(TAG, "换绑等待首帧时被取消，恢复旧输出")
            restoreOutput(display, oldReader, oldWidth, oldHeight, oldDensity)
            closeQuietly(newReader)
            throw e
        }
        if (firstFrame == null) {
            val waitedMs = SystemClock.elapsedRealtime() - startedAt
            Log.w(TAG, "换绑后 ${waitedMs}ms 内没有收到首帧，恢复旧输出")
            restoreOutput(display, oldReader, oldWidth, oldHeight, oldDensity)
            closeQuietly(newReader)
            return null
        }

        val releasedNow = synchronized(stateLock) {
            if (isReleased) {
                true
            } else {
                imageReader = newReader
                screenWidth = target.width
                screenHeight = target.height
                screenDensity = target.density
                false
            }
        }
        if (releasedNow) {
            Log.d(TAG, "换绑提交前会话已释放，丢弃新输出")
            firstFrame.close()
            closeQuietly(newReader)
            return null
        }

        closeQuietly(oldReader)
        Log.d(
            TAG,
            "换绑成功: ${target.width}x${target.height}, " +
                "等待首帧=${SystemClock.elapsedRealtime() - startedAt}ms, " +
                "reader=${newReader.hashCode()}"
        )
        return firstFrame
    }

    /**
     * 换绑失败时恢复到旧 Surface
     *
     * 旧 ImageReader 一直保持打开，所以可以安全地重新挂回 VirtualDisplay。
     */
    private fun restoreOutput(
        display: VirtualDisplay,
        oldReader: ImageReader?,
        width: Int,
        height: Int,
        density: Int
    ) {
        if (isReleasedNow()) {
            Log.d(TAG, "会话已释放，跳过恢复旧输出")
            return
        }
        if (oldReader == null) {
            Log.w(TAG, "没有旧 reader，无法恢复旧输出")
            return
        }
        try {
            display.resize(width, height, density)
            display.setSurface(oldReader.surface)
        } catch (e: Exception) {
            Log.e(TAG, "恢复旧输出失败: ${width}x${height}", e)
            return
        }
        Log.d(TAG, "已恢复旧输出: ${width}x$height, reader=${oldReader.hashCode()}")
        onDisplayResizedListener?.invoke()
    }

    /**
     * 调度一次预换绑
     *
     * 旋转/分屏可能连续上报多次尺寸，这里做短防抖合并，避免反复创建 reader。
     * 真正的换绑在 [preRebindOutput] 里、持有 operationMutex 时执行。
     */
    private fun schedulePreRebind(width: Int, height: Int, reason: String) {
        if (width <= 0 || height <= 0) return
        val scope = synchronized(stateLock) {
            if (isReleased) return
            if (width == screenWidth && height == screenHeight) return
            preRebindScope ?: return
        }
        Log.d(
            TAG,
            "调度预换绑: ${width}x$height, 原因=$reason, 防抖=${PRE_REBIND_DEBOUNCE_MS}ms"
        )
        val job = scope.launch {
            delay(PRE_REBIND_DEBOUNCE_MS)
            operationMutex.withLock {
                preRebindOutput(width, height, reason)
            }
        }
        synchronized(stateLock) {
            preRebindJob?.cancel()
            preRebindJob = job
        }
    }

    /**
     * 尺寸变化时预先换绑输出（在系统回调里触发，不在截图路径上）
     *
     * 调用方已持有 operationMutex：截图协程按需取帧、取到即转 Bitmap 并关闭，
     * 整个截图过程都在同一把锁内，所以这里关闭旧 reader 时不可能有未释放的
     * Image，可以像官方示例一样直接换绑、不用等首帧。
     *
     * 新输出早期的过渡合成帧会被之后的正常帧覆盖；这里补一次轻量刷新，
     * 促使新 Surface 立即出帧，用户下一次截图时队列里已经是最新画面。
     */
    private fun preRebindOutput(width: Int, height: Int, reason: String) {
        val display: VirtualDisplay
        val oldReader: ImageReader?
        val oldWidth: Int
        val oldHeight: Int
        val density: Int
        synchronized(stateLock) {
            if (isReleased) return
            if (width == screenWidth && height == screenHeight) {
                Log.d(TAG, "预换绑跳过: 尺寸未变 ${width}x$height")
                return
            }
            display = virtualDisplay ?: run {
                Log.w(TAG, "预换绑跳过: VirtualDisplay 不存在")
                return
            }
            oldReader = imageReader
            oldWidth = screenWidth
            oldHeight = screenHeight
            density = screenDensity
        }
        if (density <= 0) {
            Log.w(TAG, "预换绑跳过: density 非法 $density")
            return
        }

        val newReader = try {
            ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                MAX_IMAGES
            )
        } catch (e: Exception) {
            Log.e(TAG, "预换绑创建 ImageReader 失败: ${width}x$height", e)
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "开始预换绑: ${oldWidth}x$oldHeight -> ${width}x$height, " +
                "原因=$reason, oldReader=${oldReader?.hashCode()}, " +
                "newReader=${newReader.hashCode()}"
        )
        try {
            display.resize(width, height, density)
            display.setSurface(newReader.surface)
        } catch (e: Exception) {
            Log.e(TAG, "预换绑失败，恢复旧输出", e)
            restoreOutput(display, oldReader, oldWidth, oldHeight, density)
            closeQuietly(newReader)
            return
        }

        val committed = synchronized(stateLock) {
            if (isReleased) {
                false
            } else {
                imageReader = newReader
                screenWidth = width
                screenHeight = height
                true
            }
        }
        if (!committed) {
            Log.d(TAG, "预换绑提交前会话已释放，丢弃新输出")
            closeQuietly(newReader)
            return
        }

        closeQuietly(oldReader)
        Log.d(
            TAG,
            "预换绑成功: ${width}x$height, 耗时=${SystemClock.elapsedRealtime() - startedAt}ms, " +
                "reader=${newReader.hashCode()}"
        )
        onDisplayResizedListener?.invoke()
    }

    /**
     * 重建整个 VirtualDisplay
     *
     * 换新 Surface 也收不到帧时，说明显示管线本身已经失效；释放旧 display，
     * 在同一个 MediaProjection 上重新创建。创建失败时保持 display 为空，
     * 下一次截图会通过 ensureDisplay 再次尝试。
     */
    private suspend fun recreateDisplay(target: CaptureTarget): Image? {
        val projection: MediaProjection
        var oldDisplay: VirtualDisplay? = null
        var oldReader: ImageReader? = null
        synchronized(stateLock) {
            if (isReleased) return null
            projection = mediaProjection ?: run {
                Log.w(TAG, "重建 VirtualDisplay 跳过: MediaProjection 不存在")
                return null
            }
            oldDisplay = virtualDisplay
            oldReader = imageReader
            virtualDisplay = null
            imageReader = null
        }
        Log.d(TAG, "释放旧输出，重建 VirtualDisplay: ${target.width}x${target.height}")
        releaseQuietly(oldDisplay)
        closeQuietly(oldReader)

        val created = createDisplayAndReader(projection, target)
        if (created == null) {
            Log.e(TAG, "重建 VirtualDisplay 失败，下次截图会再次尝试")
            return null
        }
        onDisplayResizedListener?.invoke()
        val firstFrame = awaitFrameOn(created.reader, RECREATE_FIRST_FRAME_TIMEOUT_MS)
        if (firstFrame == null) {
            Log.w(TAG, "重建 VirtualDisplay 后 ${RECREATE_FIRST_FRAME_TIMEOUT_MS}ms 内没有收到首帧")
        }
        return firstFrame
    }

    /**
     * 确保 VirtualDisplay/ImageReader 存在
     *
     * 重建失败或释放竞态可能让输出为空；这里负责补建，避免一次失败后
     * 永远停在“未初始化”。
     */
    private fun ensureDisplay(target: CaptureTarget): Boolean {
        val projection: MediaProjection
        var orphanDisplay: VirtualDisplay? = null
        var orphanReader: ImageReader? = null
        synchronized(stateLock) {
            if (isReleased) return false
            if (virtualDisplay != null && imageReader != null) return true
            orphanDisplay = virtualDisplay
            orphanReader = imageReader
            virtualDisplay = null
            imageReader = null
            projection = mediaProjection ?: return false
        }
        releaseQuietly(orphanDisplay)
        closeQuietly(orphanReader)
        Log.w(TAG, "截图输出缺失，尝试重建: ${target.width}x${target.height}")
        return createDisplayAndReader(projection, target) != null
    }

    /**
     * 创建 ImageReader + VirtualDisplay 并提交到状态
     */
    private fun createDisplayAndReader(
        projection: MediaProjection,
        target: CaptureTarget
    ): CreatedOutput? {
        if (target.width <= 0 || target.height <= 0) {
            Log.e(TAG, "创建输出失败: 尺寸非法 ${target.width}x${target.height}")
            return null
        }
        val reader = try {
            ImageReader.newInstance(
                target.width,
                target.height,
                PixelFormat.RGBA_8888,
                MAX_IMAGES
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建 ImageReader 失败: ${target.width}x${target.height}", e)
            return null
        }

        val display = try {
            projection.createVirtualDisplay(
                "ScreenCapture",
                target.width,
                target.height,
                target.density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建 VirtualDisplay 失败: ${target.width}x${target.height}", e)
            closeQuietly(reader)
            return null
        }
        if (display == null) {
            Log.e(TAG, "创建 VirtualDisplay 返回 null: ${target.width}x${target.height}")
            closeQuietly(reader)
            return null
        }

        val releasedNow = synchronized(stateLock) {
            if (isReleased) {
                true
            } else {
                virtualDisplay = display
                imageReader = reader
                screenWidth = target.width
                screenHeight = target.height
                screenDensity = target.density
                false
            }
        }
        if (releasedNow) {
            Log.d(TAG, "创建输出期间会话已释放，立即回收")
            releaseQuietly(display)
            closeQuietly(reader)
            return null
        }
        Log.d(
            TAG,
            "创建截图输出: ${target.width}x${target.height}, density=${target.density}, " +
                "来源=${target.source}, reader=${reader.hashCode()}, display=${display.hashCode()}"
        )
        return CreatedOutput(display, reader)
    }

    /**
     * 等待并取走一帧
     *
     * 先立即 acquire（队列里可能有帧），没有就轮询；超过 renudgeAtMs 仍没有帧
     * 时补一次轻量刷新，促使静止画面重新合成。
     */
    private suspend fun awaitFrame(timeoutMs: Long, renudgeAtMs: Long): Image? {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + timeoutMs
        var renudgeAt = if (renudgeAtMs > 0) startedAt + renudgeAtMs else Long.MAX_VALUE
        while (true) {
            acquireCurrent()?.let { return it }
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline) return null
            if (now >= renudgeAt) {
                renudgeAt = Long.MAX_VALUE
                Log.d(TAG, "等待帧超过 ${renudgeAtMs}ms，补一次轻量刷新")
                onDisplayResizedListener?.invoke()
            }
            delay(minOf(POLL_INTERVAL_MS, deadline - now))
        }
    }

    /** 在指定 reader 上等待首帧（换绑/重建后的新输出） */
    private suspend fun awaitFrameOn(reader: ImageReader, timeoutMs: Long): Image? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            acquireLatest(reader)?.let { return it }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            delay(minOf(POLL_INTERVAL_MS, remaining))
        }
    }

    private fun acquireCurrent(): Image? {
        val reader = synchronized(stateLock) {
            if (isReleased) null else imageReader
        }
        return reader?.let(::acquireLatest)
    }

    private fun acquireLatest(reader: ImageReader): Image? = try {
        reader.acquireLatestImage()
    } catch (e: IllegalStateException) {
        Log.w(TAG, "读取屏幕帧失败", e)
        null
    } catch (e: Exception) {
        Log.e(TAG, "读取屏幕帧异常", e)
        null
    }

    /**
     * 解析本次截图应使用的尺寸
     *
     * 优先使用系统上报的捕获内容尺寸（API 34+），其次是真实屏幕尺寸，
     * 最后退回已提交的尺寸。
     */
    private fun resolveTarget(): CaptureTarget {
        val fallbackWidth: Int
        val fallbackHeight: Int
        val fallbackDensity: Int
        val reportedWidth: Int
        val reportedHeight: Int
        synchronized(stateLock) {
            fallbackWidth = screenWidth
            fallbackHeight = screenHeight
            fallbackDensity = screenDensity
            reportedWidth = contentWidth
            reportedHeight = contentHeight
        }
        val metrics = realDisplayMetrics()
        val density = metrics?.densityDpi?.takeIf { it > 0 } ?: fallbackDensity
        return when {
            reportedWidth > 0 && reportedHeight > 0 ->
                CaptureTarget(reportedWidth, reportedHeight, density, "内容上报")
            metrics != null ->
                CaptureTarget(metrics.widthPixels, metrics.heightPixels, density, "真实屏幕")
            else ->
                CaptureTarget(fallbackWidth, fallbackHeight, density, "已提交尺寸")
        }
    }

    /** 默认显示屏的真实尺寸；服务在后台时也以系统显示为准 */
    private fun realDisplayMetrics(): DisplayMetrics? {
        val ctx = synchronized(stateLock) { context } ?: return null
        val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return null
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.takeIf { it.widthPixels > 0 && it.heightPixels > 0 }
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
        val manager: DisplayManager?
        val job: Job?
        val scope: CoroutineScope?
        synchronized(stateLock) {
            if (isReleased && imageReader == null && virtualDisplay == null && mediaProjection == null) {
                Log.d(TAG, "截图资源已释放，无需重复释放")
                return
            }
            isReleased = true
            reader = imageReader
            display = virtualDisplay
            projection = mediaProjection
            manager = displayManager
            job = preRebindJob
            scope = preRebindScope
            imageReader = null
            virtualDisplay = null
            mediaProjection = null
            displayManager = null
            preRebindJob = null
            preRebindScope = null
            context = null
            contentWidth = 0
            contentHeight = 0
        }
        job?.cancel()
        scope?.cancel()
        releaseQuietly(display)
        closeQuietly(reader)
        try {
            manager?.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
            Log.w(TAG, "注销显示变化监听失败", e)
        }
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (e: Exception) {
            Log.e(TAG, "注销 MediaProjection 回调失败", e)
        }
        Log.d(TAG, "截图资源已释放")
    }

    override fun isInitialized(): Boolean = synchronized(stateLock) {
        !isReleased &&
            mediaProjection != null &&
            virtualDisplay != null &&
            imageReader != null
    }

    private fun isReleasedNow(): Boolean = synchronized(stateLock) { isReleased }

    private fun attachedSize(): Pair<Int, Int> = synchronized(stateLock) {
        screenWidth to screenHeight
    }

    private fun closeQuietly(reader: ImageReader?) {
        if (reader == null) return
        try {
            reader.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 ImageReader 失败", e)
        }
    }

    private fun releaseQuietly(display: VirtualDisplay?) {
        if (display == null) return
        try {
            display.release()
        } catch (e: Exception) {
            Log.w(TAG, "释放 VirtualDisplay 失败", e)
        }
    }
}
