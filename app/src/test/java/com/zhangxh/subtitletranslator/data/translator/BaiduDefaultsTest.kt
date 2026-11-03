package com.zhangxh.subtitletranslator.data.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduDefaultsTest {

    @Test
    fun `built-in credentials are complete`() {
        assertTrue(BaiduDefaults.credentials.isComplete)
        assertEquals("20250811002428473", BaiduDefaults.credentials.appId)
    }
}
