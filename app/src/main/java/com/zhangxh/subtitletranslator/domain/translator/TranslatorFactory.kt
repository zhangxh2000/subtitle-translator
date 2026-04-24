package com.zhangxh.subtitletranslator.domain.translator

/**
 * 翻译引擎工厂，支持动态切换翻译服务
 */
class TranslatorFactory {

    private val translators = mutableMapOf<String, ITranslator>()

    /**
     * 注册翻译引擎
     */
    fun register(name: String, translator: ITranslator) {
        translators[name] = translator
    }

    /**
     * 获取指定名称的翻译引擎
     */
    fun getTranslator(name: String): ITranslator? {
        return translators[name]
    }

    /**
     * 获取默认翻译引擎（第一个注册的离线引擎，或第一个注册的引擎）
     */
    fun getDefaultTranslator(): ITranslator? {
        // 优先返回离线引擎
        return translators.values.firstOrNull { it.isOffline() }
            ?: translators.values.firstOrNull()
    }

    /**
     * 列出所有可用的翻译引擎
     */
    fun listAvailable(): List<Pair<String, String>> {
        return translators.map { (name, translator) ->
            name to translator.getName()
        }
    }

    /**
     * 释放所有翻译引擎资源
     */
    fun releaseAll() {
        translators.values.forEach { it.release() }
        translators.clear()
    }
}