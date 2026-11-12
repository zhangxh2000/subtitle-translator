package com.zhangxh.subtitletranslator.ui.learning

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.domain.entitlement.IEntitlementManager
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyRepository
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStats
import kotlinx.coroutines.launch

data class LearningUiState(
    val stats: LearningStats = LearningStats(),
    /** 游客不展示统计，但保留复习入口 */
    val isGuest: Boolean = true
)

/**
 * 学习概览：每日复习目标、待复习、已掌握
 *
 * 只依赖本地词库，不阻塞游客使用；游客可以复习，统计区域替换为登录引导。
 */
class LearningViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: IVocabularyRepository =
        (application as SubtitleTranslatorApplication).container.vocabularyRepository,
    private val entitlementManager: IEntitlementManager =
        (application as SubtitleTranslatorApplication).container.entitlementManager
) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData(
        LearningUiState(isGuest = entitlementManager.entitlement.value.isGuest)
    )
    val uiState: LiveData<LearningUiState> = _uiState

    init {
        viewModelScope.launch {
            entitlementManager.entitlement.collect { entitlement ->
                val current = _uiState.value ?: LearningUiState()
                _uiState.value = current.copy(isGuest = entitlement.isGuest)
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val stats = LearningStats.from(repository.list())
            val current = _uiState.value ?: LearningUiState()
            _uiState.value = current.copy(stats = stats)
        }
    }
}
