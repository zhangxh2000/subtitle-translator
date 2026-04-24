package com.zhangxh.subtitletranslator.data.wordextractor

import android.content.Context
import android.util.Log
import com.zhangxh.subtitletranslator.domain.wordextractor.IWordExtractor
import com.zhangxh.subtitletranslator.domain.wordextractor.Meaning
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 本地难词提取器
 * 基于内置的词频表和词典数据
 */
class LocalWordExtractor(private val context: Context) : IWordExtractor {

    companion object {
        private const val TAG = "LocalWordExtractor"
        
        // 常见简单词（不需要解释的）
        private val COMMON_WORDS = setOf(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could", "should",
            "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
            "my", "your", "his", "her", "its", "our", "their", "this", "that", "these", "those",
            "and", "or", "but", "if", "then", "else", "when", "where", "why", "how",
            "what", "who", "which", "whose", "whom",
            "in", "on", "at", "to", "for", "of", "with", "by", "from", "up", "about", "into",
            "through", "during", "before", "after", "above", "below", "between", "among"
        )
        
        // 常见词根，用于判断难度
        private val ADVANCED_PREFIXES = listOf(
            "un", "re", "in", "im", "dis", "en", "em", "non", "over", "mis", "sub", "pre",
            "inter", "fore", "de", "trans", "super", "semi", "anti", "mid", "under"
        )
        
        private val ADVANCED_SUFFIXES = listOf(
            "ness", "ment", "ful", "less", "able", "ible", "ly", "ward", "wise", "ize",
            "ise", "ify", "en", "er", "or", "ist", "ism", "tion", "sion", "ity", "ty",
            "al", "ial", "ic", "ical", "ous", "ious", "eous", "ive", "ative", "itive"
        )
    }

    private var dictionary: Map<String, String> = emptyMap()

    init {
        loadDictionary()
    }

    override suspend fun extractDifficultWords(text: String, maxWords: Int): List<WordEntry> =
        withContext(Dispatchers.Default) {
            try {
                // 1. 分词
                val words = tokenize(text)
                
                // 2. 过滤简单词和重复词
                val uniqueWords = words
                    .filter { it.length > 3 }  // 过滤短词
                    .filter { !COMMON_WORDS.contains(it.lowercase()) }
                    .distinct()
                
                // 3. 评估难度并排序
                val scoredWords = uniqueWords.map { word ->
                    val difficulty = assessDifficulty(word)
                    val entry = lookupWordInternal(word)
                    entry.copy(difficulty = difficulty)
                }
                
                // 4. 按难度排序，优先返回难词
                scoredWords
                    .filter { it.difficulty != WordDifficulty.EASY }
                    .sortedByDescending { it.difficulty.ordinal }
                    .take(maxWords)
                    
            } catch (e: Exception) {
                Log.e(TAG, "提取难词失败", e)
                emptyList()
            }
        }

    override suspend fun lookupWord(word: String): WordEntry? =
        withContext(Dispatchers.IO) {
            lookupWordInternal(word)
        }

    /**
     * 分词：简单实现，按空格和标点分割
     */
    private fun tokenize(text: String): List<String> {
        return text
            .replace(Regex("[^a-zA-Z\s]"), " ")  // 移除非字母字符
            .split(Regex("\s+"))  // 按空格分割
            .filter { it.isNotBlank() }
            .map { it.lowercase().trim() }
    }

    /**
     * 评估单词难度
     */
    private fun assessDifficulty(word: String): WordDifficulty {
        val lowerWord = word.lowercase()
        
        // 长度判断
        if (word.length <= 4) return WordDifficulty.EASY
        if (word.length >= 10) return WordDifficulty.HARD
        
        // 检查是否有复杂词根词缀
        var complexityScore = 0
        
        // 检查前缀
        for (prefix in ADVANCED_PREFIXES) {
            if (lowerWord.startsWith(prefix) && lowerWord.length > prefix.length + 3) {
                complexityScore += 1
                break
            }
        }
        
        // 检查后缀
        for (suffix in ADVANCED_SUFFIXES) {
            if (lowerWord.endsWith(suffix) && lowerWord.length > suffix.length + 3) {
                complexityScore += 1
                break
            }
        }
        
        // 检查是否在词典中（有释义说明较复杂）
        if (dictionary.containsKey(lowerWord)) {
            complexityScore += 1
        }
        
        return when {
            complexityScore >= 2 -> WordDifficulty.HARD
            complexityScore >= 1 -> WordDifficulty.MEDIUM
            else -> WordDifficulty.EASY
        }
    }

    /**
     * 内部查词方法
     */
    private fun lookupWordInternal(word: String): WordEntry {
        val lowerWord = word.lowercase()
        val definition = dictionary[lowerWord]
        
        return if (definition != null) {
            WordEntry(
                word = word,
                meanings = listOf(
                    Meaning(
                        partOfSpeech = "",
                        definition = definition,
                        chineseDefinition = definition
                    )
                ),
                difficulty = assessDifficulty(word)
            )
        } else {
            // 如果没有找到释义，返回基础信息
            WordEntry(
                word = word,
                meanings = listOf(
                    Meaning(
                        partOfSpeech = "",
                        definition = "未找到释义",
                        chineseDefinition = "未找到释义"
                    )
                ),
                difficulty = assessDifficulty(word)
            )
        }
    }

    /**
     * 加载内置词典
     * 实际项目中可以从 assets 加载更完整的词典 JSON
     */
    private fun loadDictionary() {
        // 内置基础词典（示例）
        dictionary = mapOf(
            "serendipity" to "意外发现珍奇事物的本领；机缘凑巧",
            "ubiquitous" to "无所不在的；普遍存在的",
            "ephemeral" to "短暂的；瞬息万变的",
            "paradigm" to "范例；样式；模范",
            "pragmatic" to "实用的；务实的",
            "ambiguous" to "模棱两可的；含糊不清的",
            "narrative" to "叙述；故事；叙事",
            "rhetoric" to "修辞；修辞学；华丽的辞藻",
            "empirical" to "经验主义的；以经验为依据的",
            "coherent" to "连贯的；一致的；条理清楚的",
            "subtle" to "微妙的；精细的；敏锐的",
            "implicit" to "含蓄的；暗示的；固有的",
            "explicit" to "明确的；清楚的；直率的",
            "comprehensive" to "全面的；综合的；广泛的",
            "controversial" to "有争议的；引起争论的",
            "hypothesis" to "假设；假说；前提",
            "phenomenon" to "现象；奇迹；杰出人才",
            "infrastructure" to "基础设施；下部构造",
            "sustainable" to "可持续的；可以忍受的",
            "innovation" to "创新；革新；新方法"
        )
    }

    /**
     * 从 assets 加载词典文件
     * 词典文件格式：JSON {"word": "释义", ...}
     */
    fun loadDictionaryFromAssets(fileName: String) {
        try {
            context.assets.open(fileName).use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                val jsonString = reader.readText()
                val jsonObject = JSONObject(jsonString)
                
                val loadedDict = mutableMapOf<String, String>()
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    loadedDict[key] = jsonObject.getString(key)
                }
                
                dictionary = loadedDict
                Log.d(TAG, "词典加载完成，共 ${loadedDict.size} 个单词")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载词典文件失败: $fileName", e)
        }
    }
}