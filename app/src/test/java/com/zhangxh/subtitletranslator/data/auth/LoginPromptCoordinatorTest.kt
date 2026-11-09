package com.zhangxh.subtitletranslator.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginPromptCoordinatorTest {

    @Test
    fun `only one prompt can be in flight`() {
        val coordinator = LoginPromptCoordinator()

        assertTrue(coordinator.tryBegin())
        assertTrue(coordinator.isPrompting)
        assertFalse(coordinator.tryBegin())

        coordinator.finish(success = false)

        assertFalse(coordinator.isPrompting)
        assertTrue("登录页结束后必须允许下一次引导", coordinator.tryBegin())
    }

    @Test
    fun `finishing twice is ignored`() {
        val coordinator = LoginPromptCoordinator()
        assertTrue(coordinator.tryBegin())

        coordinator.finish(success = true)
        coordinator.finish(success = true)

        assertFalse(coordinator.isPrompting)
    }
}
