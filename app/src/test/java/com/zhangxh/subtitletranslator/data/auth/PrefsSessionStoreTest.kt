package com.zhangxh.subtitletranslator.data.auth

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrefsSessionStoreTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var store: PrefsSessionStore

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("session_store_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = PrefsSessionStore(prefs)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun tokens(access: String = "access-1", refresh: String = "refresh-1") =
        testTokensDomain(accessToken = access, refreshToken = refresh).copy(
            accessTokenExpiresAt = Instant.ofEpochMilli(1_700_000_000_000),
            refreshTokenExpiresAt = Instant.ofEpochMilli(1_800_000_000_000)
        )

    @Test
    fun `session round trips through preferences`() {
        store.saveSession(testUserDomain(), tokens())

        val user = requireNotNull(store.readUser())
        assertEquals("usr_1", user.id)
        assertEquals("maria@example.com", user.email)
        assertEquals("Maria", user.displayName)
        assertEquals("access-1", store.readTokens()?.accessToken)
        assertEquals("refresh-1", store.readTokens()?.refreshToken)
        assertEquals(
            Instant.ofEpochMilli(1_700_000_000_000),
            store.readTokens()?.accessTokenExpiresAt
        )
    }

    @Test
    fun `saving tokens keeps the cached user`() {
        store.saveSession(testUserDomain(), tokens())
        store.saveTokens(tokens(access = "access-2", refresh = "refresh-2"))

        assertEquals("Maria", store.readUser()?.displayName)
        assertEquals("access-2", store.readTokens()?.accessToken)
    }

    @Test
    fun `clear removes both user and tokens`() {
        store.saveSession(testUserDomain(), tokens())

        store.clear()

        assertNull(store.readUser())
        assertNull(store.readTokens())
    }

    @Test
    fun `missing keys produce no session`() {
        assertNull(store.readUser())
        assertNull(store.readTokens())
    }
}
