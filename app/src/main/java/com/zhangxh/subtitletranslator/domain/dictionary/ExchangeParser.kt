package com.zhangxh.subtitletranslator.domain.dictionary

/**
 * 词形变化解析器
 *
 * ECDICT 的 exchange 字段记录单词的屈折变化，格式为 `key:value` 用 `/` 分隔：
 * ```
 * go    → i:going/p:went/d:gone/3:goes
 * wolf  → s:wolves
 * happy → r:happier/t:happiest
 * ```
 * 解析后得到「变化类型 → 形式列表」，如 `{"过去式": ["went"], "过去分词": ["gone"]}`。
 */
object ExchangeParser {

    /** ECDICT exchange 字段的 key 含义 */
    private val KEY_LABELS = mapOf(
        "p" to "过去式",
        "d" to "过去分词",
        "i" to "现在分词",
        "3" to "第三人称单数",
        "s" to "复数",
        "r" to "比较级",
        "t" to "最高级",
        "0" to "词元",
        "1" to "词元变体"
    )

    /** 词元本身不是「变化」，展示词形变化时应排除 */
    private val LEMMA_KEYS = setOf("0", "1")

    /**
     * @param raw exchange 字段原文
     * @return 变化类型 → 形式列表；无法识别的内容会被忽略，无有效内容返回空 Map
     */
    fun parse(raw: String?): Map<String, List<String>> {
        if (raw.isNullOrBlank()) return emptyMap()

        val result = linkedMapOf<String, MutableList<String>>()
        raw.split("/").forEach { pair ->
            val separator = pair.indexOf(':')
            if (separator <= 0) return@forEach

            val key = pair.substring(0, separator).trim()
            val label = KEY_LABELS[key] ?: return@forEach
            if (key in LEMMA_KEYS) return@forEach

            val forms = pair.substring(separator + 1)
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (forms.isNotEmpty()) {
                result.getOrPut(label) { mutableListOf() }.addAll(forms)
            }
        }
        return result
    }
}
