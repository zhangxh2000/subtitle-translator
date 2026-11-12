package com.zhangxh.subtitletranslator.domain.entitlement

import com.zhangxh.subtitletranslator.data.entitlement.EntitlementManager
import com.zhangxh.subtitletranslator.domain.auth.AuthEvent
import com.zhangxh.subtitletranslator.domain.auth.AuthManager
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import com.zhangxh.subtitletranslator.domain.membership.IMembershipRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EntitlementManagerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun user() = AuthUser(
        id = "user-1",
        email = "user@example.com",
        emailVerified = true,
        displayName = null,
        status = "active",
        createdAt = null,
        updatedAt = null
    )

    private fun manager(
        authState: AuthState,
        membership: Membership = Membership()
    ): IEntitlementManager {
        val auth = FakeAuthManager(authState)
        val repository = FakeMembershipRepository(membership)
        return EntitlementManager(auth, repository, scope)
    }

    @Test
    fun `guest has no account features`() {
        val entitlement = manager(AuthState.Guest).entitlement.value

        assertEquals(Plan.GUEST, entitlement.plan)
        assertFalse(entitlement.hasAccess(Feature.BASIC_STATS))
        assertFalse(entitlement.hasAccess(Feature.CLOUD_SYNC))
        assertFalse(entitlement.hasAccess(Feature.AI_TRANSLATION))
    }

    @Test
    fun `logged in free account unlocks free features only`() {
        val entitlement = manager(AuthState.LoggedIn(user())).entitlement.value

        assertEquals(Plan.FREE, entitlement.plan)
        assertTrue(entitlement.hasAccess(Feature.BASIC_STATS))
        assertTrue(entitlement.hasAccess(Feature.CLOUD_SYNC))
        assertFalse(entitlement.hasAccess(Feature.AI_TRANSLATION))
        assertFalse(entitlement.hasAccess(Feature.AI_CONTEXT_ANALYSIS))
    }

    @Test
    fun `active member unlocks AI features`() {
        val entitlement = manager(
            AuthState.LoggedIn(user()),
            Membership(Plan.MEMBER, Instant.now().plusSeconds(3_600))
        ).entitlement.value

        assertEquals(Plan.MEMBER, entitlement.plan)
        assertTrue(entitlement.hasAccess(Feature.AI_TRANSLATION))
        assertTrue(entitlement.hasAccess(Feature.AI_SPOKEN_DISCOVERY))
        assertTrue(entitlement.hasAccess(Feature.BASIC_STATS))
    }

    @Test
    fun `expired member falls back to free`() {
        val entitlement = manager(
            AuthState.LoggedIn(user()),
            Membership(Plan.MEMBER, Instant.now().minusSeconds(1))
        ).entitlement.value

        assertEquals(Plan.FREE, entitlement.plan)
        assertTrue(entitlement.hasAccess(Feature.BASIC_STATS))
        assertFalse(entitlement.hasAccess(Feature.AI_TRANSLATION))
    }

    private class FakeAuthManager(initial: AuthState) : AuthManager {
        override val state: StateFlow<AuthState> = MutableStateFlow(initial)
        override val events: SharedFlow<AuthEvent> = MutableSharedFlow()
    }

    private class FakeMembershipRepository(
        initial: Membership = Membership()
    ) : IMembershipRepository {
        override val membership: StateFlow<Membership> = MutableStateFlow(initial)
        override suspend fun refresh() = Unit
    }
}
