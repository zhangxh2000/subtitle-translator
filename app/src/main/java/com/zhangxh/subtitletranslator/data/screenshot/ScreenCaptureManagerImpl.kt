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
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.zhangxh.subtitletranslator.domain.screenshot.IScreenCaptureManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 屏幕截图管理器实现
 *
 * 使用 MediaProjection + 单个 VirtualDisplay + ImageReader 捕获屏幕。
 *
 * 设计原则（都是踩过的坑）：
 * 1. 常驻监听、持续消费：每个 ImageReader 创建时立即挂上 OnImageAvailableListener，
 *    没有截图请求时收到的帧马上 close。队列不会积压换绑瞬间的过渡帧，生产端也
 *    不会因为队列满而停住。
 * 2. 截图 = 等“请求之后新到达”的帧：截图时挂起等待下一次出帧，并拒绝年龄超过
 *    [MAX_FRAME_AGE_MS] 的残留帧。静止画面靠截图前的轻量刷新（悬浮球闪一下）
 *    触发出帧，等待中途还会补一次刷新。
 * 3. 换 Surface 时先创建新 reader 并挂好监听，再 setSurface / resize，失败则
 *    回滚旧输出。新 reader 从挂上开始就有人消费，不需要再等首帧来确认。
 * 4. 尺寸变化（旋转 / 分屏）在系统回调里预先换绑：API 34+ 用
 *    onCapturedContentResize，低版本用 DisplayManager.DisplayListener。
 *    换绑与截图都在 operationMutex 内执行，不会在截图等待期间换掉它的 reader。
 * 5. 整个 MediaProjection 会话只创建一次 VirtualDisplay（Android 14+ 的硬性
 *    要求），不再用“重建 VirtualDisplay”兜底；拿不到帧就返回失败。
 * 6. 不返回缓存帧。拿不到帧就是失败，由调用方决定是提示用户还是跳过该步。
 */
class ScreenCaptureManagerImpl : IScreenCaptureManager {

    private companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val MAX_IMAGES = 2

        /** 正常截图等待新帧的时间 */
        private const val CAPTURE_WAIT_TIMEOUT_MS = 900L

        /** 等待帧超过这个时间后补一次轻量刷新，促使静止画面出帧 */
        private const val RENUDGE_AT_MS = 450L

        /** 换新 Surface 后再次等待首帧的时间 */
        private const val RECOVERY_WAIT_TIMEOUT_MS = 1200L

        /** 尺寸变化预换绑的防抖时间，合并旋转/分屏产生的连续回调 */
        private const val PRE_REBIND_DEBOUNCE_MS = 150L

        /** 超过这个年龄的帧视为换绑/积压残留，不交付给截图 */
        private const val MAX_FRAME_AGE_MS = 1000L
    }

    private data class CaptureTarget(
        val width: Int,
        val height: Int,
        val density: Int,
        val source: String
    )

    /** 一次截图请求交付的帧 */
    private data class DeliveredFrame(
        val bitmap: Bitmap,
        val timestamp: Long
    )

    private class CaptureWaiter(val reader: ImageReader) {
        val result = CompletableDeferred<DeliveredFrame>()

        /** 已投递的帧；由 stateLock 保护，用于超时/取消竞争时回收 */
        var delivered: DeliveredFrame? = null
    }

    /** 保护所有可变状态；截图协程、系统回调与截图回调线程都会访问 */
    private val stateLock = Any()

    /** 串行化截图、换绑和预换绑，避免在截图等待期间换掉它的 reader */
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

    /** ImageReader 回调线程：空闲帧消费与截图帧转换都不占主线程 */
    private var captureHandlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    /** 当前等待帧的截图请求；同一时间最多一个 */
    private var pendingCapture: CaptureWaiter? = null

    /** 已经为哪个 MediaProjection 创建过 VirtualDisplay（每个会话只能创建一次） */
    private var displayCreatedFor: MediaProjection? = null

    private var onProjectionStoppedListener: (() -> Unit)? = null
    private var onDisplayResizedListener: (() -> Unit)? = null

    /**
     * 设置 MediaProjection 停止监听回调
     */
    fun setOnProjectionStoppedListener(listener: () -> Unit) {
        onProjectionStoppedListener = listener
    }

    /**
     * 输出 Surface 换绑后回调
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
                "系统上报捕获内容尺寸: ${width}x$height, 当前输出=${attachedWidth}x$attachedHeight"
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
        Log.d(TAG, "初始化屏幕截图: ${width}x$height, density=$density")
        if (hasActiveResources()) {
            Log.w(TAG, "重复初始化，先释放旧截图资源")
        }
        releaseInternal()

        val handlerThread = HandlerThread("ScreenCaptureCallback").apply {
            start()
            Log.d(TAG, "创建截图回调线程: $name")
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
            this.pendingCapture = null
            this.isReleased = false
            this.captureHandlerThread = handlerThread
            this.captureHandler = Handler(handlerThread.looper)
            this.preRebindScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        try {
            mediaProjection.registerCallback(
                projectionCallback,
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.e(TAG, "注册 MediaProjection 回调失败", e)
            releaseInternal()
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
        if (!createDisplayAndReader(mediaProjection, target)) {
            Log.e(TAG, "初始化未创建出截图输出，后续截图会再次尝试")
        }
    }

    override suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                captureWithFreshFrame()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "截图失败", e)
                null
            }
        }
    }

    /**
     * 捕获流程：尺寸换绑 → 等请求之后新到达的帧 → 换 Surface 再等一次
     *
     * 调用方已持有 operationMutex。
     */
    private suspend fun captureWithFreshFrame(): Bitmap? {
        val startedAt = SystemClock.elapsedRealtime()
        if (isReleasedNow()) {
            Log.e(TAG, "截图失败: 未初始化或已释放")
            return null
        }

        val target = resolveTarget()
        if (target.width <= 0 || target.height <= 0) {
            Log.e(TAG, "截图失败: 目标尺寸非法 ${target.width}x${target.height}")
            return null
        }
        if (!ensureDisplay(target)) {
            Log.e(TAG, "截图失败: 截图输出不可用")
            return null
        }

        val (attachedWidth, attachedHeight) = attachedSize()
        Log.d(
            TAG,
            "开始截图: 当前输出=${attachedWidth}x$attachedHeight, " +
                "目标=${target.width}x${target.height}(来源=${target.source})"
        )
        if (target.width != attachedWidth || target.height != attachedHeight) {
            if (!rebindOutput(target, "尺寸变化")) {
                Log.w(TAG, "尺寸换绑失败，继续使用旧输出")
            }
        }

        awaitCapture(CAPTURE_WAIT_TIMEOUT_MS, RENUDGE_AT_MS)?.let {
            return finishCapture(it, startedAt, "正常帧")
        }

        Log.w(TAG, "等待 ${CAPTURE_WAIT_TIMEOUT_MS}ms 没有新帧，换绑新 Surface 重试")
        val rebound = rebindOutput(resolveTarget(), "空帧自愈")
        Log.d(TAG, "自愈换绑结果: $rebound")
        awaitCapture(RECOVERY_WAIT_TIMEOUT_MS, RENUDGE_AT_MS)?.let {
            return finishCapture(it, startedAt, "自愈首帧")
        }

        Log.w(
            TAG,
            "截图失败: 等待新帧超时, 耗时=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
        return null
    }

    /**
     * 等待一次“截图请求之后”的帧
     *
     * 空闲帧已经被常驻监听消费掉，这里等到的回调必然来自请求之后的新合成；
     * 超过 [nudgeAtMs] 仍没有帧时补一次轻量刷新，促使静止画面出帧。
     */
    private suspend fun awaitCapture(timeoutMs: Long, nudgeAtMs: Long): DeliveredFrame? {
        val waiter = synchronized(stateLock) {
            if (isReleased) return null
            val reader = imageReader ?: return null
            if (pendingCapture != null) {
                Log.w(TAG, "已有截图请求在等待帧，本次直接失败")
                return null
            }
            CaptureWaiter(reader).also { pendingCapture = it }
        }
        val readerHash = waiter.reader.hashCode()
        val armedAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "开始等待新帧: 超时=${timeoutMs}ms, reader=$readerHash")

        var taken: DeliveredFrame? = null
        try {
            taken = withTimeoutOrNull(timeoutMs) {
                val nudgeJob = if (nudgeAtMs > 0 && nudgeAtMs < timeoutMs) {
                    launch {
                        delay(nudgeAtMs)
                        Log.d(TAG, "等待帧超过 ${nudgeAtMs}ms，补一次轻量刷新")
                        onDisplayResizedListener?.invoke()
                    }
                } else {
                    null
                }
                try {
                    waiter.result.await()
                } finally {
                    nudgeJob?.cancel()
                }
            }
            if (taken != null) {
                Log.d(
                    TAG,
                    "收到新帧: reader=$readerHash, " +
                        "等待=${SystemClock.elapsedRealtime() - armedAt}ms"
                )
            }
            return taken
        } finally {
            // 超时与到帧同时发生时，迟到的帧可能已经投递但没人取走，这里回收
            val leftover = synchronized(stateLock) {
                if (pendingCapture === waiter) pendingCapture = null
                if (taken == null) waiter.delivered else null
            }
            if (leftover != null) {
                Log.w(TAG, "截图已结束才收到帧，回收 reader=$readerHash")
                leftover.bitmap.recycle()
            }
        }
    }

    /** 图像可用回调（截图回调线程），空闲时立即丢弃，有请求时转换并交付 */
    private fun onFrameAvailable(reader: ImageReader) {
        val image = acquireLatest(reader) ?: return
        try {
            val waiter = currentWaiterFor(reader) ?: return

            val ageMs = frameAgeMs(image.timestamp)
            if (ageMs > MAX_FRAME_AGE_MS) {
                // 换绑/积压残留：不交付，继续等下一次合成
                Log.w(TAG, "丢弃陈旧屏幕帧: 年龄=${ageMs}ms, 继续等待新帧")
                return
            }

            val bitmap = convertImage(image)
            if (bitmap == null) {
                failWaiter(waiter, "屏幕帧转换 Bitmap 失败")
                return
            }

            val frame = DeliveredFrame(bitmap, image.timestamp)
            val accepted = synchronized(stateLock) {
                if (pendingCapture === waiter && !waiter.result.isCompleted) {
                    waiter.delivered = frame
                    if (waiter.result.complete(frame)) {
                        pendingCapture = null
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
            if (!accepted) {
                Log.d(TAG, "截图请求已结束，回收刚转换的帧")
                bitmap.recycle()
            }
        } finally {
            image.close()
        }
    }

    /**
     * 换绑输出 Surface
     *
     * 新 reader 先挂好常驻监听再 attach，换绑后由监听持续消费，积压帧不会
     * 留给下一次截图。先 setSurface 再 resize，避免 resize 作用在尺寸不匹配
     * 的旧 Surface 上；任一步失败则回滚旧输出并关闭新 reader。
     */
    private fun rebindOutput(target: CaptureTarget, reason: String): Boolean {
        val display: VirtualDisplay
        val oldReader: ImageReader?
        val oldWidth: Int
        val oldHeight: Int
        val oldDensity: Int
        val handler: Handler
        synchronized(stateLock) {
            if (isReleased) return false
            display = virtualDisplay ?: run {
                Log.w(TAG, "换绑跳过: VirtualDisplay 不存在")
                return false
            }
            oldReader = imageReader
            oldWidth = screenWidth
            oldHeight = screenHeight
            oldDensity = screenDensity
            handler = captureHandler ?: run {
                Log.w(TAG, "换绑跳过: 截图回调线程不存在")
                return false
            }
        }
        if (target.density <= 0) {
            Log.w(TAG, "换绑跳过: density 非法 ${target.density}")
            return false
        }

        val newReader = try {
            ImageReader.newInstance(
                target.width,
                target.height,
                PixelFormat.RGBA_8888,
                MAX_IMAGES
            )
        } catch (e: Exception) {
            Log.e(TAG, "换绑创建 ImageReader 失败: ${target.width}x${target.height}", e)
            return false
        }
        newReader.setOnImageAvailableListener({ r -> onFrameAvailable(r) }, handler)

        val startedAt = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "开始换绑输出: ${oldWidth}x$oldHeight -> ${target.width}x${target.height}, " +
                "原因=$reason, 来源=${target.source}, " +
                "oldReader=${oldReader?.hashCode()}, newReader=${newReader.hashCode()}"
        )
        try {
            display.setSurface(newReader.surface)
            display.resize(target.width, target.height, target.density)
        } catch (e: Exception) {
            Log.e(TAG, "换绑输出 Surface 失败，恢复旧输出", e)
            restoreOutput(display, oldReader, oldWidth, oldHeight, oldDensity)
            closeReader(newReader)
            return false
        }

        val committed = synchronized(stateLock) {
            if (isReleased) {
                false
            } else {
                imageReader = newReader
                screenWidth = target.width
                screenHeight = target.height
                screenDensity = target.density
                true
            }
        }
        if (!committed) {
            Log.d(TAG, "换绑提交前会话已释放，丢弃新输出")
            closeReader(newReader)
            return false
        }

        closeReader(oldReader)
        Log.d(
            TAG,
            "换绑成功: ${target.width}x${target.height}, " +
                "耗时=${SystemClock.elapsedRealtime() - startedAt}ms, " +
                "reader=${newReader.hashCode()}"
        )
        onDisplayResizedListener?.invoke()
        return true
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
            display.setSurface(oldReader.surface)
            display.resize(width, height, density)
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
     * 调用方已持有 operationMutex；新输出挂上后就由常驻监听持续消费，
     * 这里不需要等首帧。
     */
    private fun preRebindOutput(width: Int, height: Int, reason: String) {
        val target: CaptureTarget
        synchronized(stateLock) {
            if (isReleased) return
            if (width == screenWidth && height == screenHeight) {
                Log.d(TAG, "预换绑跳过: 尺寸未变 ${width}x$height")
                return
            }
            target = CaptureTarget(width, height, screenDensity, reason)
        }
        if (target.density <= 0) {
            Log.w(TAG, "预换绑跳过: density 非法 ${target.density}")
            return
        }
        if (!rebindOutput(target, reason)) {
            Log.w(TAG, "预换绑失败，保持旧输出")
        }
    }

    /**
     * 确保 VirtualDisplay/ImageReader 存在
     *
     * 初始化创建失败时允许在这里补建一次；同一 MediaProjection 会话
     * 已经创建过 VirtualDisplay 后不再重建（Android 14+ 只允许创建一次）。
     */
    private fun ensureDisplay(target: CaptureTarget): Boolean {
        val projection: MediaProjection
        synchronized(stateLock) {
            if (isReleased) return false
            if (virtualDisplay != null && imageReader != null) return true
            projection = mediaProjection ?: return false
        }
        Log.w(TAG, "截图输出缺失，尝试创建: ${target.width}x${target.height}")
        return createDisplayAndReader(projection, target)
    }

    /**
     * 创建 ImageReader + VirtualDisplay 并提交到状态
     *
     * ImageReader 创建后立即挂上常驻监听，从第一帧开始就有人消费。
     */
    private fun createDisplayAndReader(
        projection: MediaProjection,
        target: CaptureTarget
    ): Boolean {
        if (target.width <= 0 || target.height <= 0) {
            Log.e(TAG, "创建输出失败: 尺寸非法 ${target.width}x${target.height}")
            return false
        }
        val handler = synchronized(stateLock) {
            if (isReleased) return false
            if (displayCreatedFor === projection) {
                Log.e(
                    TAG,
                    "拒绝重复创建 VirtualDisplay: 同一 MediaProjection 会话只允许创建一次"
                )
                return false
            }
            captureHandler ?: run {
                Log.e(TAG, "创建输出失败: 截图回调线程不存在")
                return false
            }
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
            return false
        }
        reader.setOnImageAvailableListener({ r -> onFrameAvailable(r) }, handler)

        val display = try {
            projection.createVirtualDisplay(
                "ScreenCapture",
                target.width,
                target.height,
                target.density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建 VirtualDisplay 失败: ${target.width}x${target.height}", e)
            closeReader(reader)
            return false
        }
        if (display == null) {
            Log.e(TAG, "创建 VirtualDisplay 返回 null: ${target.width}x${target.height}")
            closeReader(reader)
            return false
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
                displayCreatedFor = projection
                false
            }
        }
        if (releasedNow) {
            Log.d(TAG, "创建输出期间会话已释放，立即回收")
            releaseQuietly(display)
            closeReader(reader)
            return false
        }
        Log.d(
            TAG,
            "创建截图输出: ${target.width}x${target.height}, density=${target.density}, " +
                "来源=${target.source}, reader=${reader.hashCode()}, display=${display.hashCode()}"
        )
        return true
    }

    /** 当前等待帧的请求；只接受挂在该 reader 上的请求 */
    private fun currentWaiterFor(reader: ImageReader): CaptureWaiter? =
        synchronized(stateLock) {
            if (isReleased) {
                null
            } else {
                pendingCapture?.takeIf {
                    it.reader === reader && !it.result.isCompleted
                }
            }
        }

    private fun failWaiter(waiter: CaptureWaiter, reason: String) {
        val failed = synchronized(stateLock) {
            if (pendingCapture === waiter && !waiter.result.isCompleted) {
                pendingCapture = null
                waiter.result.completeExceptionally(IllegalStateException(reason))
                true
            } else {
                false
            }
        }
        if (failed) {
            Log.e(TAG, "截图失败: $reason")
        }
    }

    private fun acquireLatest(reader: ImageReader): Image? = try {
        reader.acquireLatestImage()
    } catch (e: IllegalStateException) {
        Log.w(TAG, "读取屏幕帧失败（reader=${reader.hashCode()}）", e)
        null
    } catch (e: Exception) {
        Log.e(TAG, "读取屏幕帧异常（reader=${reader.hashCode()}）", e)
        null
    }

    /** 帧时间戳距当前单调时钟的毫秒数；时间戳不可用或不可比时返回 -1 */
    private fun frameAgeMs(timestamp: Long): Long {
        if (timestamp <= 0) return -1
        val age = System.nanoTime() - timestamp
        return if (age > 0) age / 1_000_000 else -1
    }

    private fun finishCapture(
        frame: DeliveredFrame,
        startedAt: Long,
        source: String
    ): Bitmap {
        Log.d(
            TAG,
            "截图成功（$source）: ${frame.bitmap.width}x${frame.bitmap.height}, " +
                "帧年龄=${frameAgeMs(frame.timestamp)}ms, " +
                "耗时=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
        return frame.bitmap
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
        val handlerThread: HandlerThread?
        val waiter: CaptureWaiter?
        synchronized(stateLock) {
            if (isReleased && imageReader == null && virtualDisplay == null &&
                mediaProjection == null && captureHandlerThread == null
            ) {
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
            handlerThread = captureHandlerThread
            waiter = pendingCapture
            imageReader = null
            virtualDisplay = null
            mediaProjection = null
            displayManager = null
            preRebindJob = null
            preRebindScope = null
            captureHandlerThread = null
            captureHandler = null
            pendingCapture = null
            displayCreatedFor = null
            context = null
            contentWidth = 0
            contentHeight = 0
        }
        waiter?.result?.completeExceptionally(IllegalStateException("屏幕采集已释放"))
        job?.cancel()
        scope?.cancel()
        closeReader(reader)
        releaseQuietly(display)
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
        handlerThread?.let {
            it.quitSafely()
            Log.d(TAG, "退出截图回调线程: ${it.name}")
        }
        Log.d(TAG, "截图资源已释放")
    }

    override fun isInitialized(): Boolean = synchronized(stateLock) {
        !isReleased &&
            mediaProjection != null &&
            virtualDisplay != null &&
            imageReader != null
    }

    private fun hasActiveResources(): Boolean = synchronized(stateLock) {
        !isReleased ||
            captureHandlerThread != null ||
            imageReader != null ||
            virtualDisplay != null ||
            mediaProjection != null
    }

    private fun isReleasedNow(): Boolean = synchronized(stateLock) { isReleased }

    private fun attachedSize(): Pair<Int, Int> = synchronized(stateLock) {
        screenWidth to screenHeight
    }

    /** 先摘监听再关闭，避免关闭过程中还有新的回调进来 */
    private fun closeReader(reader: ImageReader?) {
        if (reader == null) return
        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (e: Exception) {
            Log.w(TAG, "移除屏幕帧监听失败", e)
        }
        closeQuietly(reader)
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
