package com.zhangxh.subtitletranslator.util

import android.content.Context
import android.graphics.Bitmap
import com.zhangxh.subtitletranslator.domain.ocr.OcrPreprocessMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 调试截图存储测试
 *
 * 图片本身能否正常显示依赖设备的编解码实现，这里只验证存储行为：
 * 文件是否落盘、元数据能否读回、超出上限是否清理、开关关闭时是否不写文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DebugCaptureStoreTest {

    private lateinit var context: Context
    private lateinit var store: DebugCaptureStore

    private val captureDir: File get() = File(context.filesDir, "debug_captures")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = DebugCaptureStore(context)
        runBlocking { store.clearAll() }
    }

    private fun bitmap(width: Int = 40, height: Int = 20): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun capture(
        ocrText: String = "They dont need to be burden",
        errorMessage: String? = null,
        cropInfo: CropInfo? = CropInfo(
            mode = "竖屏",
            screenWidth = 1080,
            screenHeight = 2400,
            top = 0,
            bottom = 608,
            width = 1080,
            height = 608,
            videoHeight = 1215
        )
    ) = DebugCapture(
        screenshot = bitmap(40, 80),
        subtitle = bitmap(40, 20),
        processed = bitmap(40, 20),
        ocrRawText = ocrText,
        cleanedText = ocrText,
        translatedText = "他们不需要成为负担",
        errorMessage = errorMessage,
        cropInfo = cropInfo,
        scaleFactor = 1.0f,
        preprocessMode = OcrPreprocessMode.GRAYSCALE.name
    )

    private fun savedDirs(): List<File> =
        captureDir.listFiles { file -> file.isDirectory }?.sortedBy { it.name }.orEmpty()

    @Test
    fun `save writes three images and meta`() = runBlocking {
        assertTrue(store.save(capture()))

        val dirs = savedDirs()
        assertEquals(1, dirs.size)
        val session = dirs.first()

        assertTrue(File(session, DebugCaptureStore.FILE_SCREENSHOT).exists())
        assertTrue(File(session, DebugCaptureStore.FILE_SUBTITLE).exists())
        assertTrue(File(session, DebugCaptureStore.FILE_PROCESSED).exists())
        assertTrue(File(session, "meta.json").exists())
    }

    @Test
    fun `listCaptures reads back meta`() = runBlocking {
        store.save(capture(ocrText = "The mitochondrial DNA evidence"))

        val records = store.listCaptures()
        assertEquals(1, records.size)

        val record = records.first()
        assertEquals("The mitochondrial DNA evidence", record.ocrRawText)
        assertEquals("竖屏", record.cropInfo?.mode)
        assertEquals(608, record.cropInfo?.height)
        assertEquals(1215, record.cropInfo?.videoHeight)
        assertEquals(1.0f, record.scaleFactor, 0.001f)
        assertEquals(OcrPreprocessMode.GRAYSCALE.name, record.preprocessMode)
        assertFalse(record.isFailed)
        assertEquals(
            listOf(
                DebugCaptureStore.FILE_SCREENSHOT,
                DebugCaptureStore.FILE_SUBTITLE,
                DebugCaptureStore.FILE_PROCESSED
            ),
            record.imageFiles
        )
    }

    /** 失败记录也要能保存和读回：OCR 失败时最需要看截图 */
    @Test
    fun `keeps failed capture with error message`() = runBlocking {
        store.save(capture(ocrText = "", errorMessage = "未识别到字幕文字"))

        val record = store.listCaptures().single()
        assertTrue(record.isFailed)
        assertEquals("未识别到字幕文字", record.errorMessage)
    }

    /** 部分阶段失败时（例如截图后就失败），缺失的图跳过，其余照常保存 */
    @Test
    fun `saves partial capture when some bitmaps are missing`() = runBlocking {
        val partial = capture().copy(subtitle = null, processed = null)
        assertTrue(store.save(partial))

        val record = store.listCaptures().single()
        assertEquals(listOf(DebugCaptureStore.FILE_SCREENSHOT), record.imageFiles)
    }

    @Test
    fun `keeps only the most recent captures`() = runBlocking {
        repeat(DebugCaptureStore.MAX_CAPTURES + 3) { index ->
            store.save(capture(ocrText = "第 $index 次识别"))
        }

        val records = store.listCaptures()
        assertEquals(DebugCaptureStore.MAX_CAPTURES, records.size)
        assertEquals(DebugCaptureStore.MAX_CAPTURES, savedDirs().size)

        // 最旧的几次应已被删除，保留的是最后写入的
        assertTrue(
            "应保留最后写入的记录",
            records.first().ocrRawText == "第 ${DebugCaptureStore.MAX_CAPTURES + 2} 次识别"
        )
    }

    @Test
    fun `lists newest first`() = runBlocking {
        listOf("第一次", "第二次", "第三次").forEach { store.save(capture(ocrText = it)) }

        val texts = store.listCaptures().map { it.ocrRawText }
        assertEquals(listOf("第三次", "第二次", "第一次"), texts)
    }

    @Test
    fun `loadImage returns null for missing file`() = runBlocking {
        assertNull(store.loadImage("不存在的会话", DebugCaptureStore.FILE_SCREENSHOT, 400))
    }

    @Test
    fun `clearAll removes every capture`() = runBlocking {
        repeat(3) { store.save(capture()) }
        assertEquals(3, savedDirs().size)

        store.clearAll()

        assertTrue(store.listCaptures().isEmpty())
        assertTrue(savedDirs().isEmpty())
    }

    /** 同一毫秒内连续保存不能挤进同一个目录，否则会被合并成一条记录 */
    @Test
    fun `rapid saves create separate sessions`() = runBlocking {
        repeat(3) { store.save(capture()) }

        assertEquals(3, savedDirs().size)
        assertEquals(3, store.listCaptures().size)
    }
}
