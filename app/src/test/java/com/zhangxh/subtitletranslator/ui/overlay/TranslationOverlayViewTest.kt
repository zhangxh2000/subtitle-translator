package com.zhangxh.subtitletranslator.ui.overlay

import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.TranslationResult
import com.zhangxh.subtitletranslator.domain.speech.IPronouncer
import com.zhangxh.subtitletranslator.domain.wordextractor.Meaning
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 翻译覆盖层测试
 *
 * 起因：单词释义内容多时，面板高度超过屏幕，窗口又顶到屏幕外，顶部内容被裁掉看不全。
 * 现在改为窗口高度按屏幕封顶 + 面板内滚动，这里把两个关键点固定下来：
 * 内容能滚动、以及关闭按钮不能跟着滚走。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TranslationOverlayViewTest {

    private fun overlay(): TranslationOverlayView {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SubtitleTranslator)
        return TranslationOverlayView(context)
    }

    /**
     * 单词释义很多的识别结果
     *
     * 义项长度按 ECDICT 单条释义的上限（120 字）来造，这是真实数据下
     * 「内容超出屏幕」的常见情形。注意 Robolectric 的字体度量是桩实现，
     * 不会真的换行，所以用例靠**词条数量**而不是文字长度来撑高内容。
     */
    private fun longResult(wordCount: Int = 5): TranslationResult = TranslationResult(
        originalText = "They dont need to be burden withour marital problems",
        translatedText = "他们不需要成为我们婚姻问题的负担",
        difficultWords = (1..wordCount).map { index ->
            WordEntry(
                word = "word$index",
                phonetic = "ˈwɜːd$index",
                difficulty = WordDifficulty.MEDIUM,
                meanings = (1..3).map {
                    Meaning(
                        partOfSpeech = "n.",
                        definition = "a fairly long english definition ".repeat(4),
                        chineseDefinition = "很长的中文释义，".repeat(15)
                    )
                }
            )
        },
        isSuccess = true
    )

    // ---------------------------------------------------------------------
    // 窗口高度
    // ---------------------------------------------------------------------

    /** 内容不多时按内容撑开，面板保持紧凑 */
    @Test
    fun `window height follows content when it fits`() {
        assertEquals(500, TranslationOverlayView.windowHeightFor(contentHeight = 500, screenHeight = 2000))
    }

    /** 内容过多时按屏幕高度封顶，超出的部分靠滚动 */
    @Test
    fun `window height is capped when content is too tall`() {
        val screenHeight = 2000
        val capped = TranslationOverlayView.windowHeightFor(contentHeight = 5000, screenHeight = screenHeight)

        assertEquals((screenHeight * TranslationOverlayView.MAX_HEIGHT_RATIO).toInt(), capped)
        assertTrue("封顶高度必须小于内容高度，否则不会滚动", capped < 5000)
    }

    /**
     * 封顶比例要两头兼顾
     *
     * 太小会让内容稍多就得频繁滚动（0.6 时实测就是这样），
     * 太大则会完全占满屏幕、看不到画面。
     */
    @Test
    fun `cap balances readable area and leaving room above`() {
        val ratio = TranslationOverlayView.MAX_HEIGHT_RATIO

        assertTrue("上限 $ratio 偏小，内容稍多就要一直滚动", ratio >= 0.8f)
        assertTrue("上限 $ratio 偏大，会完全挡住画面", ratio <= 0.95f)
    }

    /**
     * 内容超过封顶高度后，面板内确实可以滚动看到剩余内容
     *
     * 这是本次修复的核心：以前窗口高度不封顶，超出屏幕的部分直接被裁掉。
     */
    @Test
    fun `capped panel scrolls to reveal the rest of the content`() {
        val overlay = overlay()
        overlay.setTranslationResult(longResult(wordCount = 30))

        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val natural = overlay.measuredHeight
        // 用一块小屏幕，保证内容一定超过封顶高度
        val capped = TranslationOverlayView.windowHeightFor(natural, screenHeight = 800)
        assertTrue("内容高度 $natural 应超过封顶高度 $capped", natural > capped)

        // 按封顶后的尺寸重新测量并布局，滚动区内容应比可视区高
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(capped, View.MeasureSpec.EXACTLY)
        )
        overlay.layout(0, 0, 1080, capped)

        val scroll = overlay.findViewById<ScrollView>(R.id.contentScroll)
        val content = scroll.getChildAt(0)
        assertTrue(
            "滚动区内容(${content.height})应高于可视区(${scroll.height})，否则滚动不起来",
            content.height > scroll.height
        )
    }

    // ---------------------------------------------------------------------
    // 结构约束
    // ---------------------------------------------------------------------

    /** 重点词汇必须位于可滚动容器内，否则内容多时看不全 */
    @Test
    fun `words container sits inside a scroll view`() {
        val words = overlay().findViewById<View>(R.id.wordsContainer)
        assertNotNull(words)

        assertTrue("重点词汇必须在 ScrollView 里", hasAncestor<ScrollView>(words))
    }

    /** 关闭按钮必须在滚动区之外：内容一多就滚走的话，面板就关不掉了 */
    @Test
    fun `close button stays outside the scroll view`() {
        val close = overlay().findViewById<View>(R.id.btnClose)
        assertNotNull(close)

        assertFalse("关闭按钮不能位于滚动区内", hasAncestor<ScrollView>(close))
    }

    // ---------------------------------------------------------------------
    // 朗读
    // ---------------------------------------------------------------------

    /**
     * 记录朗读调用的假实现
     *
     * 真实实现要连系统语音引擎，界面测试只关心「点了哪一行的喇叭」，
     * 所以这里用假实现接住回调。
     */
    private class RecordingPronouncer : IPronouncer {
        val spoken = mutableListOf<String>()
        var stopCount = 0

        override fun speak(word: String) {
            spoken.add(word)
        }

        override fun stop() {
            stopCount++
        }

        override fun release() = Unit
    }

    private fun rows(overlay: TranslationOverlayView): LinearLayout =
        requireNotNull(overlay.findViewById(R.id.wordsContainer))

    private fun pronounceButton(row: View): ImageButton =
        requireNotNull(row.findViewById(R.id.btnPronounce)) { "该行没有朗读按钮" }

    /** 每个生词后面都要有喇叭，且点击区明显大于图标本身 */
    @Test
    fun `each word row has a pronounce button with a bigger touch target`() {
        val overlay = overlay()
        overlay.setTranslationResult(longResult(wordCount = 3))

        val density = overlay.resources.displayMetrics.density
        val container = rows(overlay)
        assertEquals(3, container.childCount)

        repeat(container.childCount) { index ->
            val button = pronounceButton(container.getChildAt(index))
            val boxDp = button.layoutParams.width / density
            val paddingDp = button.paddingLeft / density

            assertTrue("点击区 $boxDp" + "dp 太小，手指点不准", boxDp >= 40f)
            assertTrue(
                "点击区(${boxDp}dp)必须大于图标(${boxDp - 2 * paddingDp}dp)，差额靠 padding 撑",
                paddingDp > 0f
            )
        }
    }

    /** 点哪一行的喇叭就读哪个词 */
    @Test
    fun `tapping the speaker speaks that word only`() {
        val pronouncing = RecordingPronouncer()
        val overlay = overlay()
        overlay.setOnPronounceListener(pronouncing::speak)
        overlay.setTranslationResult(longResult(wordCount = 3))

        pronounceButton(rows(overlay).getChildAt(1)).performClick()

        assertEquals(listOf("word2"), pronouncing.spoken)
    }

    /** 朗读不抢复制：点喇叭只朗读，长按整行仍然复制 */
    @Test
    fun `speaking does not copy while long pressing the row still does`() {
        val pronouncing = RecordingPronouncer()
        val copied = mutableListOf<String>()
        val overlay = overlay()
        overlay.setOnPronounceListener(pronouncing::speak)
        overlay.setOnCopyListener { copied.add(it) }
        overlay.setTranslationResult(longResult(wordCount = 2))

        val row = rows(overlay).getChildAt(0)
        pronounceButton(row).performClick()

        assertEquals(listOf("word1"), pronouncing.spoken)
        assertTrue("点喇叭不应触发复制", copied.isEmpty())

        row.performLongClick()
        assertEquals("长按整行仍要能复制", listOf("word1"), copied)
    }

    /** 每次翻译都会重建生词列表，喇叭数量要跟着最新的结果走 */
    @Test
    fun `pronounce buttons follow the latest result`() {
        val overlay = overlay()
        overlay.setTranslationResult(longResult(wordCount = 4))
        overlay.setTranslationResult(longResult(wordCount = 2))

        val container = rows(overlay)
        assertEquals(2, container.childCount)
        repeat(container.childCount) { index ->
            pronounceButton(container.getChildAt(index))
        }
    }

    /**
     * 难度标识要和单词对齐
     *
     * 喇叭按钮的 40dp 触摸框把「单词行」撑高了（单词在行内垂直居中），
     * 标识要是还顶格放，就会比单词高一截 —— 这是加朗读按钮时踩过的坑。
     */
    @Test
    fun `difficulty badge is vertically aligned with the word`() {
        val overlay = overlay()
        overlay.setTranslationResult(longResult(wordCount = 1))
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        overlay.layout(0, 0, 1080, overlay.measuredHeight)

        val row = rows(overlay).getChildAt(0)
        val badge = requireNotNull(row.findViewById<TextView>(R.id.tvDifficulty))
        val word = requireNotNull(row.findViewById<TextView>(R.id.tvWord))

        // 单词在「单词行」里是垂直居中的，所以标识要对齐的是这一行的中心；
        // 中间隔了两层父容器，逐层累加算出该行在整行坐标系里的位置。
        // （不用 offsetDescendantRectToMyCoords：Robolectric 下它只返回退化矩形）
        val wordLine = word.parent as View
        val wordBlock = wordLine.parent as View
        val badgeCenter = badge.top + badge.height / 2
        val wordLineCenter = wordBlock.top + wordLine.top + wordLine.height / 2

        // 单词要在 40dp 的单词行里真正垂直居中，标识才对得上：
        // 水平 LinearLayout 默认按基线排列，加了按钮后文字会贴到行顶部。
        // （Robolectric 的字体度量是桩，测不出文字真实位置，只能把开关钉住）
        assertFalse(
            "单词行必须关闭 baseline 对齐，否则单词不会垂直居中",
            (wordLine as LinearLayout).isBaselineAligned
        )

        assertTrue(
            "难度标识中心($badgeCenter)与单词中心($wordLineCenter)相差过大",
            abs(badgeCenter - wordLineCenter) <= 2
        )
    }

    // ---------------------------------------------------------------------
    // 收藏生词
    // ---------------------------------------------------------------------

    private fun saveButton(row: View): ImageButton =
        requireNotNull(row.findViewById(R.id.btnSaveWord)) { "该行没有收藏按钮" }

    /** 收藏按钮同样要有大于图标的点击区 */
    @Test
    fun `each word row has a save button with a bigger touch target`() {
        val overlay = overlay()
        overlay.setTranslationResult(longResult(wordCount = 3))

        val density = overlay.resources.displayMetrics.density
        val container = rows(overlay)
        assertEquals(3, container.childCount)

        repeat(container.childCount) { index ->
            val button = saveButton(container.getChildAt(index))
            val boxDp = button.layoutParams.width / density
            val paddingDp = button.paddingLeft / density

            assertTrue("点击区 ${boxDp}dp 太小，手指点不准", boxDp >= 40f)
            assertTrue("点击区(${boxDp}dp)必须大于图标，差额靠 padding 撑", paddingDp > 0f)
        }
    }

    /** 点收藏要把整条 WordEntry 传出去（存库需要音标/释义/难度） */
    @Test
    fun `tapping save passes the whole word entry`() {
        val received = mutableListOf<WordEntry>()
        val overlay = overlay()
        overlay.setOnSaveToggleListener { received.add(it) }
        val result = longResult(wordCount = 3)
        overlay.setTranslationResult(result)

        saveButton(rows(overlay).getChildAt(1)).performClick()

        assertEquals(1, received.size)
        assertEquals(result.difficultWords[1], received.single())
    }

    /** 数据库里的收藏状态回来后要点亮对应图标，大小写不敏感 */
    @Test
    fun `setWordSaved switches the icon state case insensitively`() {
        val overlay = overlay()
        overlay.setTranslationResult(longResult(wordCount = 2))
        val button = saveButton(rows(overlay).getChildAt(0))

        assertEquals(overlay.context.getString(R.string.action_save_word), button.contentDescription)
        assertFalse(button.isSelected)

        overlay.setWordSaved("word1", true)
        assertEquals(
            overlay.context.getString(R.string.action_unsave_word),
            button.contentDescription
        )
        assertTrue(button.isSelected)

        overlay.setWordSaved("WORD1", false)
        assertEquals(overlay.context.getString(R.string.action_save_word), button.contentDescription)
        assertFalse(button.isSelected)
    }

    /** 每次翻译都会重建行，收藏状态不能从上一批结果泄漏过来 */
    @Test
    fun `saved state does not leak across rebuilt results`() {
        val overlay = overlay()
        overlay.setTranslationResult(longResult(wordCount = 2))
        overlay.setWordSaved("word1", true)

        overlay.setTranslationResult(longResult(wordCount = 2))

        val button = saveButton(rows(overlay).getChildAt(0))
        assertEquals(overlay.context.getString(R.string.action_save_word), button.contentDescription)
        assertFalse(button.isSelected)
    }

    /** 同一个词在一个结果里出现多次时，所有行一起点亮 */
    @Test
    fun `saved state updates every row with the same word`() {
        val duplicate = WordEntry(
            word = "dup",
            phonetic = "dʌp",
            difficulty = WordDifficulty.MEDIUM,
            meanings = listOf(Meaning("n.", "duplicate", "重复", emptyList()))
        )
        val overlay = overlay()
        overlay.setTranslationResult(
            TranslationResult(
                originalText = "dup dup",
                translatedText = "重复 重复",
                difficultWords = listOf(duplicate, duplicate),
                isSuccess = true
            )
        )

        overlay.setWordSaved("DUP", true)

        val container = rows(overlay)
        repeat(container.childCount) { index ->
            assertTrue("第 $index 行未点亮", saveButton(container.getChildAt(index)).isSelected)
        }
    }

    /** 点收藏只收藏：不朗读、不复制 */
    @Test
    fun `tapping save does not pronounce or copy`() {
        val pronouncing = RecordingPronouncer()
        val copied = mutableListOf<String>()
        val overlay = overlay()
        overlay.setOnPronounceListener(pronouncing::speak)
        overlay.setOnCopyListener { copied.add(it) }
        overlay.setTranslationResult(longResult(wordCount = 2))

        saveButton(rows(overlay).getChildAt(0)).performClick()

        assertTrue("点收藏不应触发朗读", pronouncing.spoken.isEmpty())
        assertTrue("点收藏不应触发复制", copied.isEmpty())
    }

    /** 重点词汇标题行要显示词数；没有生词时隐藏 */
    @Test
    fun `word count follows the result`() {
        val overlay = overlay()
        overlay.setTranslationResult(longResult(wordCount = 3))

        val count = requireNotNull(overlay.findViewById<TextView>(R.id.tvWordCount))
        assertEquals(View.VISIBLE, count.visibility)
        assertEquals(
            overlay.resources.getQuantityString(R.plurals.overlay_word_count, 3, 3),
            count.text.toString()
        )

        overlay.setTranslationResult(longResult(wordCount = 0))
        assertEquals(View.GONE, count.visibility)
    }

    private inline fun <reified T : View> hasAncestor(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent is T) return true
            parent = (parent as? View)?.parent
        }
        return false
    }
}
