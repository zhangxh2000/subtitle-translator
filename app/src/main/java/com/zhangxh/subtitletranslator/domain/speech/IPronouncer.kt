package com.zhangxh.subtitletranslator.domain.speech

/**
 * 单词朗读接口
 *
 * 目前只有「生词列表里点喇叭」一个调用方，但仍按仓库惯例把能力定义在 domain 层、
 * 平台实现放在 data 层：界面测试可以用一个假的实现，不必依赖系统语音引擎。
 */
interface IPronouncer {
    /**
     * 朗读单词
     *
     * 引擎是异步初始化的：首次调用时若还没就绪，会先记下这个词，就绪后自动朗读。
     */
    fun speak(word: String)

    /**
     * 停止当前朗读，并丢弃尚未开始朗读的单词（面板关闭时调用）
     */
    fun stop()

    /**
     * 关闭引擎释放资源，之后不可再用；可重复调用
     */
    fun release()
}

/**
 * 朗读失败原因，供调用方决定给用户什么提示
 */
enum class PronouncerError {
    /** 设备没有可用的语音引擎，或引擎初始化失败 */
    ENGINE_UNAVAILABLE,

    /** 引擎缺少英语（en-US）语音数据 */
    ENGLISH_VOICE_UNAVAILABLE
}
