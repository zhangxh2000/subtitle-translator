package com.zhangxh.subtitletranslator.ui.overlay

import android.view.View
import android.widget.ScrollView
import androidx.appcompat.view.ContextThemeWrapper
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.TranslationResult
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

    /** 封顶比例要留出上方空间，不能占满整个屏幕 */
    @Test
    fun `cap leaves room above the panel`() {
        assertTrue(TranslationOverlayView.MAX_HEIGHT_RATIO <= 0.8f)
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

    private inline fun <reified T : View> hasAncestor(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent is T) return true
            parent = (parent as? View)?.parent
        }
        return false
    }
}
