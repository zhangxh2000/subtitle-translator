package com.zhangxh.subtitletranslator.ui.overlay

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.TranslationResult
import com.zhangxh.subtitletranslator.domain.ITranslationCoordinator
import com.zhangxh.subtitletranslator.domain.ai.AiTranslationRequest
import com.zhangxh.subtitletranslator.domain.ai.IAiTranslationUseCase
import com.zhangxh.subtitletranslator.domain.error.TranslationException
import com.zhangxh.subtitletranslator.domain.history.ITranslationHistoryUseCase
import com.zhangxh.subtitletranslator.domain.media.IMediaPlaybackGate
import com.zhangxh.subtitletranslator.domain.settings.ITranslationSettings
import com.zhangxh.subtitletranslator.domain.subtitle.ISubtitleNavigationController
import com.zhangxh.subtitletranslator.domain.subtitle.SubtitleDirection
import com.zhangxh.subtitletranslator.domain.subtitle.SubtitleNavigationOutcome
import com.zhangxh.subtitletranslator.domain.subtitle.SubtitleNavigationResult
import com.zhangxh.subtitletranslator.domain.translation.ITranslationContextStore
import com.zhangxh.subtitletranslator.domain.entitlement.QuotaKind
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularyController
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedSentenceToggleOutcome
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWordContext
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWordToggleOutcome
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import com.zhangxh.subtitletranslator.ui.common.Event
import com.zhangxh.subtitletranslator.ui.common.UiText
import com.zhangxh.subtitletranslator.ui.common.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 翻译面板状态管理
 *
 * 服务层的「ViewModel」：持有当前面板、AI/导航状态和一次性效果，
 * 通过 LiveData 暴露给 FloatingButtonService 渲染。它不持有 View、WindowManager 或 Toast，
 * 所有用户可见文案都用 UiText 交给 Service 解析。
 */
class TranslationOverlayPresenter(
    private val coordinator: ITranslationCoordinator,
    private val aiTranslationUseCase: IAiTranslationUseCase,
    private val contextStore: ITranslationContextStore,
    private val settings: ITranslationSettings,
    private val historyUseCase: ITranslationHistoryUseCase,
    private val vocabulary: IVocabularyController,
    private val navigationController: ISubtitleNavigationController,
    private val mediaGate: IMediaPlaybackGate,
    dispatcher: CoroutineDispatcher = Dispatchers.Main
) {

    private companion object {
        private const val TAG = "OverlayPresenter"
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableLiveData(TranslationOverlayUiState())
    val state: LiveData<TranslationOverlayUiState> = _state

    private val _effects = MutableLiveData<Event<OverlayEffect>>()
    val effects: LiveData<Event<OverlayEffect>> = _effects

    private var nextPanelId = 1L

    val isBusy: Boolean
        get() = _state.value?.isBusy == true

    val isPanelVisible: Boolean
        get() = _state.value?.panel != null

    /** 悬浮球单击：有面板就收起，没有就翻译当前字幕 */
    fun toggleTranslation() {
        val current = _state.value ?: return
        if (current.isBusy) {
            Log.d(TAG, "翻译/导航进行中，忽略本次点击")
            return
        }
        if (current.panel != null) {
            close()
            return
        }
        translate(preRecognizedText = null, fromNavigation = false)
    }

    /** 关闭面板并恢复由本应用暂停的媒体 */
    fun close() {
        val current = _state.value ?: return
        if (current.panel == null) {
            mediaGate.resumeIfPausedByApp()
            return
        }
        clearPanel()
        _state.value = current.copy(panel = null, translating = false, navigating = false)
        emit(OverlayEffect.DismissPanel(animated = true))
        mediaGate.resumeIfPausedByApp()
        Log.d(TAG, "翻译面板关闭")
    }

    /** AI 图片精译当前面板 */
    fun requestAiTranslation() {
        val current = _state.value ?: return
        val panel = current.panel ?: return
        if (current.isBusy || panel.aiState != AiPanelState.Idle) {
            Log.d(TAG, "AI 精译不可用: busy=${current.isBusy}, aiState=${panel.aiState}")
            return
        }

        val frame = panel.frame
        if (frame == null || frame.image.isRecycled) {
            Log.w(TAG, "缺少当前字幕截图，取消 AI 图片翻译")
            emit(OverlayEffect.Toast(UiText(R.string.overlay_ai_image_unavailable)))
            return
        }

        val image = try {
            frame.image.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Log.e(TAG, "复制字幕截图失败", e)
            null
        }
        if (image == null) {
            emit(OverlayEffect.Toast(UiText(R.string.overlay_ai_image_unavailable)))
            return
        }

        Log.d(
            TAG,
            "开始 AI 图片翻译: panel=${panel.id}, ${image.width}x${image.height}, " +
                "context=${panel.aiContext.size}, resultChars=${panel.result.originalText.length}"
        )
        updatePanel(panel.id) { it.copy(aiState = AiPanelState.Loading) }

        scope.launch {
            val outcome = try {
                aiTranslationUseCase.translate(
                    AiTranslationRequest(
                        image = image,
                        previousOriginalText = panel.result.originalText,
                        sourceLanguage = panel.languagePair.first,
                        targetLanguage = panel.languagePair.second,
                        context = panel.aiContext,
                        fallbackWords = panel.result.difficultWords
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI 图片翻译异常", e)
                Result.failure(e)
            } finally {
                image.recycle()
            }

            val latest = _state.value?.panel
            if (latest?.id != panel.id) {
                Log.d(TAG, "AI 结果到达时面板已切换，丢弃")
                return@launch
            }

            outcome.fold(
                onSuccess = { output ->
                    val updated = latest.copy(
                        result = latest.result.copy(
                            originalText = output.originalText,
                            translatedText = output.translatedText,
                            difficultWords = output.difficultWords
                        ),
                        frame = latest.frame?.copy(text = output.originalText),
                        aiState = AiPanelState.Applied
                    )
                    applyPanel(updated)
                    contextStore.replace(
                        previousOriginal = panel.result.originalText,
                        correctedOriginal = output.originalText,
                        translation = output.translatedText
                    )
                    loadSavedStates(updated)
                    Log.d(
                        TAG,
                        "AI 图片翻译已应用: panel=${updated.id}, " +
                            "原文 ${output.originalText.length} 字, " +
                            "生词 ${output.difficultWords.size} 个"
                    )
                },
                onFailure = { error ->
                    updatePanel(panel.id) { it.copy(aiState = AiPanelState.Idle) }
                    val translationError = (error as? TranslationException)?.error
                    Log.w(
                        TAG,
                        "AI 图片翻译失败: " +
                            "${translationError?.code ?: error.javaClass.simpleName}"
                    )
                    emit(
                        OverlayEffect.Toast(
                            if (translationError != null) {
                                translationError.toUiText()
                            } else {
                                UiText(R.string.error_translation_failed)
                            }
                        )
                    )
                }
            )
        }
    }

    /** 上一句 / 下一句 */
    fun navigate(direction: SubtitleDirection) {
        val current = _state.value ?: return
        val panel = current.panel ?: return
        if (current.isBusy) return

        // 面板显示时视频通常已经暂停；兜底一次，避免用户中途用耳机恢复播放
        mediaGate.pauseForCapture()
        _state.value = current.copy(navigating = true)
        // 立即移除，避免后续截图截到半透明面板
        emit(OverlayEffect.DismissPanel(animated = false))
        Log.d(TAG, "开始字幕导航: direction=$direction, panel=${panel.id}")

        scope.launch {
            val outcome = try {
                navigationController.findAdjacent(panel.result.originalText, direction)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "字幕导航异常", e)
                SubtitleNavigationOutcome.Failed(SubtitleNavigationResult.RecognitionFailed)
            }

            when (outcome) {
                is SubtitleNavigationOutcome.Found -> {
                    // 导航期间保持 navigating=true，面板继续隐藏，直到新译文就绪
                    translate(preRecognizedText = outcome.text, fromNavigation = true)
                }

                SubtitleNavigationOutcome.ProgressBarMissing -> restorePanel(
                    UiText(R.string.overlay_navigation_progress_bar_missing),
                    long = true
                )

                SubtitleNavigationOutcome.AccessibilityRequired -> guideToPermission(
                    UiText(R.string.overlay_navigation_accessibility_required),
                    OverlayEffect.OpenAccessibilitySettings
                )

                SubtitleNavigationOutcome.NotificationAccessRequired -> guideToPermission(
                    UiText(R.string.overlay_navigation_permission_required),
                    OverlayEffect.OpenNotificationAccessSettings
                )

                is SubtitleNavigationOutcome.Failed -> {
                    Log.w(TAG, "字幕导航未命中: ${outcome.result}")
                    restorePanel(navigationError(outcome.result), long = false)
                }
            }
        }
    }

    fun toggleSavedWord(entry: WordEntry) {
        val panel = _state.value?.panel ?: return
        val context = SavedWordContext(
            source = panel.result.originalText.takeIf { it.isNotBlank() },
            translation = panel.result.translatedText.takeIf { it.isNotBlank() }
        )
        scope.launch {
            when (val outcome = vocabulary.toggleWord(entry, context)) {
                is SavedWordToggleOutcome.Toggled -> {
                    updatePanel(panel.id) { current ->
                        current.copy(savedWords = current.savedWords + (entry.word to outcome.saved))
                    }
                    emit(
                        OverlayEffect.Toast(
                            UiText(
                                if (outcome.saved) R.string.service_word_saved
                                else R.string.service_word_removed
                            )
                        )
                    )
                }

                is SavedWordToggleOutcome.QuotaBlocked -> emit(quotaToast(outcome.kind))
                SavedWordToggleOutcome.Failed ->
                    emit(OverlayEffect.Toast(UiText(R.string.service_save_word_failed)))
            }
        }
    }

    fun toggleSavedSentence(source: String, translation: String?) {
        val panel = _state.value?.panel ?: return
        if (source.isBlank()) return
        scope.launch {
            when (val outcome = vocabulary.toggleSentence(source, translation)) {
                is SavedSentenceToggleOutcome.Toggled -> {
                    updatePanel(panel.id) { it.copy(sentenceSaved = outcome.saved) }
                    emit(
                        OverlayEffect.Toast(
                            UiText(
                                if (outcome.saved) R.string.service_sentence_saved
                                else R.string.service_sentence_removed
                            )
                        )
                    )
                }

                is SavedSentenceToggleOutcome.QuotaBlocked -> emit(quotaToast(outcome.kind))
                SavedSentenceToggleOutcome.Failed ->
                    emit(OverlayEffect.Toast(UiText(R.string.service_save_sentence_failed)))
            }
        }
    }

    /** 窗口 addView 失败：清空状态并恢复媒体，避免停在「看似有面板」的状态 */
    fun onOverlayShowFailed() {
        Log.w(TAG, "翻译面板显示失败，清空状态")
        clearPanel()
        _state.value = (_state.value ?: TranslationOverlayUiState()).copy(
            panel = null,
            translating = false,
            navigating = false
        )
        mediaGate.resumeIfPausedByApp()
    }

    fun release() {
        Log.d(TAG, "释放翻译面板状态")
        scope.cancel()
        clearPanel()
        _state.value = TranslationOverlayUiState()
        contextStore.clear()
        navigationController.release()
        mediaGate.resumeIfPausedByApp()
    }

    private fun translate(preRecognizedText: String?, fromNavigation: Boolean) {
        val current = _state.value ?: return
        if (current.translating) return
        val previousPanel = current.panel
        val languagePair = if (fromNavigation && previousPanel != null) {
            previousPanel.languagePair
        } else {
            settings.sourceLang to settings.targetLang
        }

        _state.value = current.copy(translating = true)
        scope.launch {
            var failure: Throwable? = null
            val result = try {
                if (preRecognizedText != null) {
                    coordinator.translateRecognizedText(preRecognizedText)
                } else {
                    coordinator.translateSubtitle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "翻译流程异常", e)
                failure = e
                null
            }

            if (result?.isSuccess == true) {
                val newPanel = buildPanel(
                    result = result,
                    previous = if (fromNavigation) previousPanel else null,
                    languagePair = languagePair
                )
                applyPanel(newPanel)
                _state.value = (_state.value ?: TranslationOverlayUiState()).copy(
                    translating = false,
                    navigating = false
                )
                contextStore.remember(result.originalText, result.translatedText)
                historyUseCase.record(result)
                loadSavedStates(newPanel)
                Log.d(
                    TAG,
                    "翻译面板已更新: panel=${newPanel.id}, " +
                        "originalChars=${result.originalText.length}, " +
                        "words=${result.difficultWords.size}"
                )
                return@launch
            }

            if (result != null) {
                Log.e(TAG, "翻译失败: ${result.error?.code}")
            }
            // 字幕导航成功后旧面板已经不对应当前画面，失败时不再恢复
            if (fromNavigation) clearPanel()
            _state.value = (_state.value ?: TranslationOverlayUiState()).copy(
                panel = if (fromNavigation) null else _state.value?.panel,
                translating = false,
                navigating = false
            )
            emit(translationFailureToast(result, failure))
            mediaGate.resumeIfPausedByApp()
        }
    }

    private fun buildPanel(
        result: TranslationResult,
        previous: OverlayPanel?,
        languagePair: Pair<String, String>
    ): OverlayPanel {
        val cachedImage = coordinator.takeSubtitleImage(result.originalText)
        val previousFrame = previous?.frame
        val frame = when {
            cachedImage != null -> SubtitleFrame(cachedImage, result.originalText)
            previousFrame?.text == result.originalText ->
                previousFrame.copy(text = result.originalText)
            else -> null
        }
        if (cachedImage == null && previousFrame != null && frame == null) {
            Log.w(TAG, "没有与当前字幕匹配的截图，AI 图片翻译暂不可用")
        }
        return OverlayPanel(
            id = nextPanelId++,
            result = result,
            frame = frame,
            languagePair = languagePair,
            aiContext = contextStore.snapshot(languagePair.first, languagePair.second)
        )
    }

    /** 替换当前面板；旧面板独有的截图在这里回收 */
    private fun applyPanel(panel: OverlayPanel) {
        val previous = _state.value?.panel
        if (previous != null && previous.frame?.image !== panel.frame?.image) {
            previous.frame?.image?.recycle()
        }
        _state.value = (_state.value ?: TranslationOverlayUiState()).copy(panel = panel)
    }

    private fun clearPanel() {
        _state.value?.panel?.frame?.image?.recycle()
    }

    private fun updatePanel(panelId: Long, transform: (OverlayPanel) -> OverlayPanel) {
        _state.value = _state.value?.let { current ->
            val panel = current.panel
            if (panel?.id != panelId) current else current.copy(panel = transform(panel))
        } ?: return
    }

    private fun loadSavedStates(panel: OverlayPanel) {
        val words = panel.result.difficultWords.map { it.word }
        val source = panel.result.originalText
        scope.launch {
            val savedWords = vocabulary.savedWords(words)
            val sentenceSaved = vocabulary.isSentenceSaved(source)
            updatePanel(panel.id) { current ->
                // 查询期间 AI 可能换了原文，旧查询结果不能写回新面板
                if (current.result.originalText != source) {
                    current
                } else {
                    current.copy(
                        savedWords = words.filter { it in savedWords }.associateWith { true },
                        sentenceSaved = sentenceSaved
                    )
                }
            }
        }
    }

    private fun restorePanel(message: UiText, long: Boolean) {
        _state.value = (_state.value ?: TranslationOverlayUiState()).copy(
            translating = false,
            navigating = false
        )
        emit(OverlayEffect.Toast(message, long))
    }

    private fun guideToPermission(message: UiText, effect: OverlayEffect) {
        clearPanel()
        _state.value = (_state.value ?: TranslationOverlayUiState()).copy(
            panel = null,
            translating = false,
            navigating = false
        )
        emit(OverlayEffect.Toast(message, long = true))
        emit(effect)
    }

    private fun navigationError(result: SubtitleNavigationResult): UiText = UiText(
        when (result) {
            SubtitleNavigationResult.NoMediaSession -> R.string.overlay_navigation_no_session
            SubtitleNavigationResult.SeekUnsupported -> R.string.overlay_navigation_seek_unsupported
            SubtitleNavigationResult.NotFound -> R.string.overlay_navigation_not_found
            SubtitleNavigationResult.RecognitionFailed -> R.string.overlay_navigation_recognition_failed
            is SubtitleNavigationResult.Found -> R.string.overlay_navigation_not_found
        }
    )

    private fun quotaToast(kind: QuotaKind): OverlayEffect.Toast = OverlayEffect.Toast(
        UiText(
            when (kind) {
                QuotaKind.WORD -> R.string.guest_quota_word_reached
                QuotaKind.PHRASE -> R.string.guest_quota_phrase_reached
            }
        ),
        long = true
    )

    private fun translationFailureToast(
        result: TranslationResult?,
        failure: Throwable?
    ): OverlayEffect.Toast = OverlayEffect.Toast(
        when {
            result?.error != null -> result.error.toUiText()
            failure != null -> UiText(
                R.string.service_translation_error,
                listOf(failure.message ?: failure.javaClass.simpleName)
            )
            else -> UiText(R.string.service_translation_failed)
        }
    )

    private fun emit(effect: OverlayEffect) {
        _effects.value = Event(effect)
    }
}
