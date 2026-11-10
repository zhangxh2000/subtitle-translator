package com.zhangxh.subtitletranslator

import android.content.Context
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * 多语言资源完整性
 *
 * 三个语言目录（默认英文、b+zh+Hans、b+zh+Hant）任何一个漏建或漏 key，
 * 这里都会失败，避免上线后某种语言静默回退成英文。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalizationTest {

    private fun contextFor(tag: String): Context {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return app.createConfigurationContext(config)
    }

    @Test
    fun `app name is localized in all supported languages`() {
        assertEquals("Subtitle Translator", contextFor("en").getString(R.string.app_name))
        assertEquals("字幕翻译助手", contextFor("zh-Hans").getString(R.string.app_name))
        assertEquals("字幕翻譯助手", contextFor("zh-Hant").getString(R.string.app_name))
    }

    @Test
    fun `traditional chinese uses traditional wording`() {
        assertEquals("設定", contextFor("zh-Hant").getString(R.string.main_settings))
        assertEquals("學習", contextFor("zh-Hant").getString(R.string.learning_title))
        assertEquals("生詞庫", contextFor("zh-Hant").getString(R.string.main_vocabulary))
        assertEquals("偏好設定", contextFor("zh-Hant").getString(R.string.profile_preferences))
    }

    @Test
    fun `simplified chinese and english resolve key labels`() {
        assertEquals("设置", contextFor("zh-Hans").getString(R.string.main_settings))
        assertEquals("Settings", contextFor("en").getString(R.string.main_settings))
        assertEquals("生词库", contextFor("zh-Hans").getString(R.string.main_vocabulary))
        assertEquals("Vocabulary", contextFor("en").getString(R.string.main_vocabulary))
    }

    @Test
    fun `main screen steps and shortcut labels are translated`() {
        val keys = listOf(
            R.string.main_step_1,
            R.string.main_step_2,
            R.string.main_step_3,
            R.string.main_step_4,
            R.string.main_step_5,
            R.string.main_vocabulary_short,
            R.string.main_settings_short
        )
        val english = contextFor("en")
        listOf("zh-Hans", "zh-Hant").forEach { tag ->
            val localized = contextFor(tag)
            keys.forEach { id ->
                val name = english.resources.getResourceEntryName(id)
                assertNotEquals(
                    "$tag 的 $name 回退成了英文，说明缺少翻译",
                    english.getString(id),
                    localized.getString(id)
                )
            }
        }
    }

    @Test
    fun `subtitle navigation and media access copy is translated`() {
        val keys = listOf(
            R.string.overlay_previous_subtitle,
            R.string.overlay_next_subtitle,
            R.string.overlay_navigation_no_session,
            R.string.overlay_navigation_seek_unsupported,
            R.string.overlay_navigation_not_found,
            R.string.overlay_navigation_recognition_failed,
            R.string.overlay_navigation_permission_required,
            R.string.overlay_navigation_accessibility_required,
            R.string.overlay_navigation_progress_bar_missing,
            R.string.overlay_seeking_adjacent_subtitle,
            R.string.settings_media_control,
            R.string.settings_media_access_granted,
            R.string.settings_media_access_missing,
            R.string.settings_media_access_action,
            R.string.settings_media_control_desc,
            R.string.media_notification_listener_label,
            R.string.menu_a11y_dump,
            R.string.a11y_dump_started,
            R.string.a11y_dump_unavailable,
            R.string.settings_accessibility_diagnostics,
            R.string.settings_accessibility_granted,
            R.string.settings_accessibility_missing,
            R.string.settings_accessibility_action,
            R.string.settings_accessibility_desc,
            R.string.accessibility_service_label,
            R.string.accessibility_service_description
        )
        val english = contextFor("en")
        listOf("zh-Hans", "zh-Hant").forEach { tag ->
            val localized = contextFor(tag)
            keys.forEach { id ->
                val name = english.resources.getResourceEntryName(id)
                assertNotEquals(
                    "$tag 的 $name 回退成了英文，说明缺少翻译",
                    english.getString(id),
                    localized.getString(id)
                )
            }
        }
    }

    @Test
    fun `auth profile and navigation copy is translated`() {
        val keys = listOf(
            R.string.nav_home,
            R.string.nav_vocabulary,
            R.string.nav_learning,
            R.string.nav_profile,
            R.string.auth_login_title,
            R.string.auth_login_subtitle,
            R.string.auth_register_title,
            R.string.auth_email_label,
            R.string.auth_password_label,
            R.string.auth_login_action,
            R.string.auth_register_action,
            R.string.auth_terms_accept,
            R.string.auth_config_unavailable,
            R.string.profile_guest_title,
            R.string.profile_login_action,
            R.string.profile_register_action,
            R.string.profile_settings,
            R.string.profile_logout,
            R.string.profile_logout_confirm_title,
            R.string.login_required_title,
            R.string.login_required_action,
            R.string.login_required_later,
            R.string.vocabulary_login_banner,
            R.string.auth_register_success,
            R.string.error_auth_network,
            R.string.error_auth_invalid_credentials,
            R.string.error_auth_session_expired,
            R.string.error_auth_server
        )
        val english = contextFor("en")
        listOf("zh-Hans", "zh-Hant").forEach { tag ->
            val localized = contextFor(tag)
            keys.forEach { id ->
                val name = english.resources.getResourceEntryName(id)
                assertNotEquals(
                    "$tag 的 $name 回退成了英文，说明缺少翻译",
                    english.getString(id),
                    localized.getString(id)
                )
            }
        }
    }

    @Test
    fun `home vocabulary learning and about copy is translated`() {
        val keys = listOf(
            R.string.home_greeting,
            R.string.home_tagline,
            R.string.home_assistant_title,
            R.string.home_section_history,
            R.string.home_view_all,
            R.string.history_title,
            R.string.history_empty_title,
            R.string.history_all_title,
            R.string.history_today_label,
            R.string.history_sentence_saved,
            R.string.history_sentence_not_saved,
            R.string.history_today_at,
            R.string.history_yesterday_at,
            R.string.vocabulary_title,
            R.string.vocabulary_subtitle,
            R.string.vocabulary_search_hint,
            R.string.vocabulary_empty_title,
            R.string.vocabulary_meta,
            R.string.learning_title,
            R.string.learning_subtitle,
            R.string.learning_goal_title,
            R.string.learning_continue_review,
            R.string.word_review_title,
            R.string.word_review_known,
            R.string.word_review_unknown,
            R.string.word_review_finished_summary,
            R.string.word_detail_title,
            R.string.word_detail_context_title,
            R.string.word_status_mastered,
            R.string.profile_preferences,
            R.string.profile_language_settings,
            R.string.profile_about,
            R.string.about_title,
            R.string.help_title,
            R.string.help_step_1_desc
        )
        val english = contextFor("en")
        listOf("zh-Hans", "zh-Hant").forEach { tag ->
            val localized = contextFor(tag)
            keys.forEach { id ->
                val name = english.resources.getResourceEntryName(id)
                assertNotEquals(
                    "$tag 的 $name 回退成了英文，说明缺少翻译",
                    english.getString(id),
                    localized.getString(id)
                )
            }
        }
    }

    @Test
    fun `history subtitle count plural is localized`() {
        assertEquals(
            "1 subtitle",
            contextFor("en").resources.getQuantityString(
                R.plurals.history_subtitle_count, 1, 1
            )
        )
        assertEquals(
            "12 subtitles",
            contextFor("en").resources.getQuantityString(
                R.plurals.history_subtitle_count, 12, 12
            )
        )
        assertEquals(
            "12 条字幕",
            contextFor("zh-Hans").resources.getQuantityString(
                R.plurals.history_subtitle_count, 12, 12
            )
        )
        assertEquals(
            "12 條字幕",
            contextFor("zh-Hant").resources.getQuantityString(
                R.plurals.history_subtitle_count, 12, 12
            )
        )
    }

    @Test
    fun `history detail title plurals are localized`() {
        assertEquals(
            "Today · 1 translation",
            contextFor("en").resources.getQuantityString(
                R.plurals.history_detail_title_today, 1, 1
            )
        )
        assertEquals(
            "今天翻译 12 次",
            contextFor("zh-Hans").resources.getQuantityString(
                R.plurals.history_detail_title_today, 12, 12
            )
        )
        assertEquals(
            "11-09 翻譯 3 次",
            contextFor("zh-Hant").resources.getQuantityString(
                R.plurals.history_detail_title_date, 3, "11-09", 3
            )
        )
    }

    @Test
    fun `word count plural is localized`() {
        assertEquals(
            "1 word",
            contextFor("en").resources.getQuantityString(R.plurals.overlay_word_count, 1, 1)
        )
        assertEquals(
            "3 words",
            contextFor("en").resources.getQuantityString(R.plurals.overlay_word_count, 3, 3)
        )
        assertEquals(
            "3 个词",
            contextFor("zh-Hans").resources.getQuantityString(R.plurals.overlay_word_count, 3, 3)
        )
        assertEquals(
            "3 個詞",
            contextFor("zh-Hant").resources.getQuantityString(R.plurals.overlay_word_count, 3, 3)
        )
    }
}
