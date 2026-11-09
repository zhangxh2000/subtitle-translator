package com.zhangxh.subtitletranslator

import android.app.Application
import com.zhangxh.subtitletranslator.di.AppContainer

/**
 * 应用入口
 *
 * 只做进程级依赖装配与会话恢复；具体业务仍由 ViewModel/Service 承担。
 */
class SubtitleTranslatorApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.restoreSession()
    }
}
