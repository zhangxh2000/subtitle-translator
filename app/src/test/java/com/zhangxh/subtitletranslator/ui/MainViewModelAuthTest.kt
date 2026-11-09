package com.zhangxh.subtitletranslator.ui

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.auth.AuthEvent
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainViewModelAuthTest {

    private class FakeAuthManager : AuthManager {
        private val _state = MutableStateFlow<AuthState>(AuthState.Guest)
        override val state: StateFlow<AuthState> = _state
        private val _events = MutableSharedFlow<AuthEvent>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        override val events: SharedFlow<AuthEvent> = _events

        fun emit(event: AuthEvent) {
            _events.tryEmit(event)
        }
    }

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun `session expiry is surfaced as a localized message`() {
        val application = RuntimeEnvironment.getApplication()
        val auth = FakeAuthManager()
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(application, auth) as T
                }
            }
        ).get(MainViewModel::class.java)

        val events = mutableListOf<MainEvent>()
        viewModel.events.observeForever { event ->
            event.getContentIfNotHandled()?.let(events::add)
        }

        auth.emit(AuthEvent.SessionExpired)
        shadowOf(Looper.getMainLooper()).idle()

        val message = events.filterIsInstance<MainEvent.ShowMessage>().lastOrNull()
        assertTrue("应产生会话过期提示", message != null)
        assertEquals(R.string.error_auth_session_expired, message?.text?.resId)
    }
}
