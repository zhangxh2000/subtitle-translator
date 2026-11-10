package com.zhangxh.subtitletranslator.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryRepository
import com.zhangxh.subtitletranslator.domain.history.TranslationDayHistory
import kotlinx.coroutines.launch

/** 首页「最近翻译」和完整历史页共用 */
class TranslationHistoryViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: ITranslationHistoryRepository =
        (application as SubtitleTranslatorApplication).container.translationHistory
) : AndroidViewModel(application) {

    private val _days = MutableLiveData<List<TranslationDayHistory>>(emptyList())
    val days: LiveData<List<TranslationDayHistory>> = _days

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _days.value = repository.recentDays()
        }
    }
}
