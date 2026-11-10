package com.zhangxh.subtitletranslator.ui.learning

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhangxh.subtitletranslator.data.speech.AndroidTtsPronouncer
import com.zhangxh.subtitletranslator.data.vocabulary.SqliteVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStatus
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.UiText
import com.zhangxh.subtitletranslator.ui.common.messageRes
import kotlinx.coroutines.launch

enum class ReviewMode {
    DUE,
    ALL;

    companion object {
        fun fromName(name: String?): ReviewMode =
            entries.firstOrNull { it.name == name } ?: DUE
    }
}

data class ReviewUiState(
    val current: SavedWord? = null,
    val index: Int = 0,
    val total: Int = 0,
    val revealed: Boolean = false,
    val finished: Boolean = false,
    val empty: Boolean = false,
    val knownCount: Int = 0,
    val unknownCount: Int = 0
) {
    val reviewedCount: Int get() = knownCount + unknownCount
}

/**
 * 单词卡片复习
 *
 * 第一版只做本地熟悉度：认识累计 3 次视为已掌握，不认识回到复习中。
 * 不做复杂的 SRS，先保证学习 Tab 有真实可用的内容。
 */
class WordReviewViewModel(
    application: Application,
    private val mode: ReviewMode,
    private val repository: IVocabularyRepository = SqliteVocabularyRepository(application)
) : AndroidViewModel(application) {

    private val pronouncer = AndroidTtsPronouncer(application) { error ->
        _messages.value = Event(UiText(error.messageRes()))
    }

    private var words: List<SavedWord> = emptyList()

    private val _uiState = MutableLiveData(ReviewUiState())
    val uiState: LiveData<ReviewUiState> = _uiState

    private val _messages = MutableLiveData<Event<UiText>>()
    val messages: LiveData<Event<UiText>> = _messages

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val all = repository.list()
            words = when (mode) {
                ReviewMode.DUE -> all.filter { it.learningStatus != LearningStatus.MASTERED }
                ReviewMode.ALL -> all
            }.sortedWith(compareByDescending<SavedWord> { it.createdAt })
            if (words.isEmpty()) {
                _uiState.value = ReviewUiState(empty = true, finished = true)
            } else {
                _uiState.value = ReviewUiState(
                    current = words.first(),
                    index = 0,
                    total = words.size
                )
            }
        }
    }

    fun reveal() {
        val state = _uiState.value ?: return
        if (state.finished || state.current == null || state.revealed) return
        _uiState.value = state.copy(revealed = true)
    }

    fun markKnown() = mark(known = true)

    fun markUnknown() = mark(known = false)

    fun speak() {
        _uiState.value?.current?.word?.let(pronouncer::speak)
    }

    /** 完成一轮后从头再来；此处直接复用内存中的词表，避免等待数据库写入。 */
    fun restart() {
        val pool = when (mode) {
            ReviewMode.DUE -> words.filter { it.learningStatus != LearningStatus.MASTERED }
            ReviewMode.ALL -> words
        }
        if (pool.isEmpty()) {
            _uiState.value = ReviewUiState(empty = true, finished = true)
            return
        }
        words = pool
        _uiState.value = ReviewUiState(
            current = words.first(),
            index = 0,
            total = words.size
        )
    }

    private fun mark(known: Boolean) {
        val state = _uiState.value ?: return
        val word = state.current ?: return
        if (state.finished || !state.revealed) return

        val familiarity = if (known) (word.familiarity + 1).coerceAtMost(5) else 0
        val status = if (known && familiarity >= MASTER_THRESHOLD) {
            LearningStatus.MASTERED
        } else if (known) {
            LearningStatus.LEARNING
        } else {
            LearningStatus.REVIEWING
        }
        val reviewedAt = System.currentTimeMillis()
        viewModelScope.launch {
            repository.updateLearningState(
                word = word.word,
                status = status,
                familiarity = familiarity,
                reviewedAt = reviewedAt
            )
        }

        words = words.toMutableList().also { list ->
            list[state.index] = word.copy(
                learningStatus = status,
                familiarity = familiarity,
                lastReviewedAt = reviewedAt
            )
        }

        val nextIndex = state.index + 1
        _uiState.value = state.copy(
            current = words.getOrNull(nextIndex),
            index = nextIndex,
            total = state.total,
            revealed = false,
            finished = nextIndex >= words.size,
            knownCount = state.knownCount + if (known) 1 else 0,
            unknownCount = state.unknownCount + if (known) 0 else 1
        )
    }

    override fun onCleared() {
        pronouncer.release()
        (repository as? SqliteVocabularyRepository)?.close()
        super.onCleared()
    }

    companion object {
        private const val MASTER_THRESHOLD = 3

        fun factory(application: Application, mode: ReviewMode) = viewModelFactory {
            initializer { WordReviewViewModel(application, mode) }
        }
    }
}
