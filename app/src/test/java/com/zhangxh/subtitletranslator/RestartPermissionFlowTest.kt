package com.zhangxh.subtitletranslator

import android.Manifest
import android.content.Intent
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 录屏权限失效后的恢复流程
 *
 * 锁屏会让 MediaProjection 失效；服务会打开主界面并带上 EXTRA_RESTART_SERVICE。
 * 主界面必须自动重新发起授权，而不是只弹一句提示、等用户自己再点一次「启动」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RestartPermissionFlowTest {

    @Test
    fun `restart intent automatically requests permissions`() {
        val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_RESTART_SERVICE, true)

        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        try {
            val request = shadowOf(controller.get()).lastRequestedPermission
            assertNotNull("收到重新授权标记后应自动发起权限申请", request)
            assertTrue(
                "应申请通知权限（Android 13+）",
                request!!.requestedPermissions.contains(Manifest.permission.POST_NOTIFICATIONS)
            )
        } finally {
            controller.destroy()
        }
    }
}
