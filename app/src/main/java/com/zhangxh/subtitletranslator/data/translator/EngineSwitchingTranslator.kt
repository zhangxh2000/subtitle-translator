package com.zhangxh.subtitletranslator.data.translator

import android.util.Log
import com.zhangxh.subtitletranslator.domain.translator.ITranslator
import com.zhangxh.subtitletranslator.domain.translator.TranslationEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 跟随设置切换的翻译器
 *
 * 悬浮窗服务只在录屏授权时创建一次翻译器，而设置页可以在服务运行期间修改引擎。
 * 如果固定持有创建时的引擎，用户改完设置后仍会继续用旧引擎翻译
 * （典型现象：选了百度翻译，实际仍走本地 ML Kit），只能停掉悬浮球重开。
 *
 * 这里在每次 [prepare] / [translate] 前重新读取引擎设置，发现变化时创建新引擎替换，
 * 并释放旧实例。读取设置用回调而不是传值，保证设置页修改后立刻生效。
 */
class EngineSwitchingTranslator(
    private val engineProvider: () -> TranslationEngine,
    private val factory: (TranslationEngine) -> ITranslator
) : ITranslator {

    private companion object {
        const val TAG = "EngineSwitchingTranslator"
    }

    /** 切换与使用串行化：避免两个并发翻译各自创建一个同类型引擎 */
    private val switchLock = Mutex()

    @Volatile
    private var current: ITranslator? = null

    @Volatile
    private var currentEngine: TranslationEngine? = null

    override fun getName(): String = current?.getName() ?: "未初始化"

    override fun isOffline(): Boolean = current?.isOffline() ?: true

    override suspend fun prepare(sourceLang: String, targetLang: String): Result<Unit> =
        ensureTranslator().prepare(sourceLang, targetLang)

    override suspend fun translate(text: String, from: String, to: String): Result<String> {
        val translator = ensureTranslator()
        Log.d(TAG, "使用 ${translator.getName()} 翻译（引擎=${currentEngine?.name}）")
        return translator.translate(text, from, to)
    }

    override fun release() {
        // 服务销毁路径不挂起；切换锁中的旧实例由下一次 ensureTranslator 释放
        current?.release()
        current = null
        currentEngine = null
    }

    /** 按最新设置返回翻译器；引擎未变化时复用已有实例，变化时切换并释放旧实例 */
    private suspend fun ensureTranslator(): ITranslator = switchLock.withLock {
        val desired = engineProvider()
        val existing = current
        if (existing != null && desired == currentEngine) return@withLock existing

        Log.i(TAG, "翻译引擎切换: ${currentEngine?.name ?: "无"} -> ${desired.name}")
        val created = factory(desired)
        current = created
        currentEngine = desired
        // 替换完成后再释放旧引擎，避免释放掉仍在使用的实例
        existing?.release()
        created
    }
}
