package com.zhangxh.subtitletranslator.ui.learning

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.data.vocabulary.SqliteVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStats
import kotlinx.coroutines.launch

/**
 * 学习概览：每日复习目标、待复习、已掌握
 *
 * 只依赖本地词库，不阻塞游客使用；登录只影响未来的云端同步。
 */
class LearningViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: IVocabularyRepository = SqliteVocabularyRepository(application)
) : AndroidViewModel(application) {

    private val _stats = MutableLiveData(LearningStats())
    val stats: LiveData<LearningStats> = _stats

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _stats.value = LearningStats.from(repository.list())
        }
    }

    override fun onCleared() {
        (repository as? SqliteVocabularyRepository)?.close()
        super.onCleared()
    }
}
