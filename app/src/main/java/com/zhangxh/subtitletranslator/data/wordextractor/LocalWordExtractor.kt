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
            "shall", "may", "might", "must", "can", "need", "dare", "ought", "used",
            "i", "you", "he", "she", "it", "we", "they", "me", "him", "her", "us", "them",
            "my", "your", "his", "her", "its", "our", "their", "this", "that", "these", "those",
            "mine", "yours", "hers", "ours", "theirs",
            "and", "or", "but", "if", "then", "else", "when", "where", "why", "how",
            "what", "who", "which", "whose", "whom", "whether", "while", "since", "until",
            "in", "on", "at", "to", "for", "of", "with", "by", "from", "up", "about", "into",
            "through", "during", "before", "after", "above", "below", "between", "among",
            "within", "without", "under", "over", "across", "around", "behind", "beyond",
            "down", "off", "onto", "out", "outside", "inside", "upon", "via", "toward",
            "yes", "no", "not", "all", "any", "both", "each", "few", "more", "most",
            "other", "some", "such", "only", "own", "same", "so", "than", "too", "very",
            "just", "also", "even", "still", "already", "yet", "once", "twice",
            "here", "there", "now", "today", "tomorrow", "yesterday", "always", "never",
            "sometimes", "often", "usually", "again", "soon", "early", "late",
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "first", "second", "last", "next", "many", "much", "little", "lot",
            "go", "come", "get", "make", "take", "see", "know", "think", "say", "tell",
            "give", "find", "want", "use", "work", "call", "try", "ask", "need", "feel",
            "become", "leave", "put", "mean", "keep", "let", "begin", "seem", "help",
            "show", "hear", "play", "run", "move", "live", "believe", "bring", "happen",
            "write", "provide", "sit", "stand", "lose", "pay", "meet", "include",
            "continue", "set", "learn", "change", "lead", "understand", "watch", "follow",
            "stop", "create", "speak", "read", "allow", "add", "spend", "grow", "open",
            "walk", "win", "offer", "remember", "love", "consider", "appear", "buy",
            "wait", "serve", "die", "send", "expect", "build", "stay", "fall", "cut",
            "reach", "kill", "remain", "good", "new", "old", "great", "high", "small",
            "different", "large", "next", "early", "young", "important", "bad", "same",
            "able", "right", "best", "better", "real", "sure", "free", "full", "long",
            "little", "big", "clear", "easy", "hard", "final", "special", "possible",
            "certain", "strong", "whole", "true", "open", "human", "local", "early",
            "late", "happy", "concerned", "similar", "general", "specific", "common",
            "available", "various", "several", "certain", "particular", "certainly"
        )

        // 常见词根，用于判断难度
        private val ADVANCED_PREFIXES = listOf(
            "un", "re", "in", "im", "dis", "en", "em", "non", "over", "mis", "sub", "pre",
            "inter", "fore", "de", "trans", "super", "semi", "anti", "mid", "under",
            "hyper", "hypo", "mega", "micro", "macro", "multi", "poly", "pseudo"
        )

        private val ADVANCED_SUFFIXES = listOf(
            "ness", "ment", "ful", "less", "able", "ible", "ly", "ward", "wise", "ize",
            "ise", "ify", "en", "er", "or", "ist", "ism", "tion", "sion", "ity", "ty",
            "al", "ial", "ic", "ical", "ous", "ious", "eous", "ive", "ative", "itive",
            "ance", "ence", "ure", "age", "dom", "ship", "hood", "let", "ling"
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
            .lowercase()
            .replace(Regex("""[^a-z\s'-]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .map { it.trim('\'', '-') }
            .filter { it.isNotBlank() }
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
     */
    private fun loadDictionary() {
        // 内置基础词典（扩展版）
        dictionary = mapOf(
            // 学术/正式词汇
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
            "innovation" to "创新；革新；新方法",
            // 情感/心理词汇
            "nostalgia" to "怀旧；乡愁；念旧",
            "melancholy" to "忧郁；悲哀；愁思",
            "euphoria" to "欣快症；精神欢愉；狂喜",
            "anxiety" to "焦虑；忧虑；渴望",
            "empathy" to "共情；同理心；移情作用",
            "resilience" to "恢复力；弹力；顺应力",
            "intuition" to "直觉；直觉力",
            // 影视/媒体常用词
            "protagonist" to "主角；主人公；支持者",
            "antagonist" to "反派；对立者；拮抗药",
            "climax" to "高潮；顶点；层进法",
            "metaphor" to "隐喻；比喻；象征",
            "allegory" to "寓言；讽喻；寓言体",
            "satire" to "讽刺；讽刺文学；讽刺作品",
            "parody" to "拙劣的模仿；戏仿",
            "foreshadowing" to "伏笔；预兆；预示",
            "flashback" to "闪回；倒叙；追述",
            "suspense" to "悬念；悬疑；焦虑",
            "plot" to "情节；阴谋；小块土地",
            "theme" to "主题；主旋律；题目",
            "genre" to "类型；体裁；风格",
            // 社会/政治词汇
            "democracy" to "民主；民主主义；民主政治",
            "bureaucracy" to "官僚制度；官僚作风",
            "hierarchy" to "等级制度；统治集团；层次体系",
            "ideology" to "意识形态；思想体系；观念学",
            "propaganda" to "宣传；宣传活动",
            "sanction" to "制裁；处罚；批准",
            "diplomacy" to "外交；外交手腕；交际手段",
            // 科技/商业词汇
            "algorithm" to "算法；运算法则",
            "automation" to "自动化；自动操作",
            "encryption" to "加密；编密码",
            "monopoly" to "垄断；垄断者；专利品",
            "merger" to "合并；归并；吸收",
            "acquisition" to "收购；获得；购置",
            "liability" to "责任；债务；倾向",
            "equity" to "公平；公正；权益",
            // 描述性形容词
            "meticulous" to "一丝不苟的；小心翼翼的；拘泥小节的",
            "arduous" to "努力的；费力的；险峻的",
            "tenuous" to "纤细的；稀薄的；贫乏的",
            "profound" to "深厚的；意义深远的；渊博的",
            "trivial" to "不重要的；琐碎的；琐细的",
            "redundant" to "多余的；冗余的；被裁员的",
            "obsolete" to "废弃的；老式的； n. 废词；陈腐的人",
            "dubious" to "可疑的；暧昧的；无把握的",
            "plausible" to "似是而非的；貌似可信的；花言巧语的",
            "inevitable" to "必然的；不可避免的",
            "spontaneous" to "自发的；自然的；无意识的"
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
