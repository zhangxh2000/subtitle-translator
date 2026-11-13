package com.zhangxh.subtitletranslator.data.sentence

import android.app.Application
import android.content.Context
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.auth.InMemorySessionStore
import com.zhangxh.subtitletranslator.data.auth.httpError
import com.zhangxh.subtitletranslator.data.net.NetworkStatus
import com.zhangxh.subtitletranslator.data.remote.ApiEnvelope
import com.zhangxh.subtitletranslator.data.remote.SentenceApi
import com.zhangxh.subtitletranslator.data.remote.SentenceChangeDto
import com.zhangxh.subtitletranslator.data.remote.SentenceSnapshotDto
import com.zhangxh.subtitletranslator.data.remote.SentenceSyncRequestDto
import com.zhangxh.subtitletranslator.data.remote.SentenceSyncResponseDto
import com.zhangxh.subtitletranslator.domain.auth.AuthTokens
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import com.zhangxh.subtitletranslator.domain.sentence.ISentenceSyncStateStore
import com.zhangxh.subtitletranslator.domain.sentence.SentenceSyncOperation
import com.zhangxh.subtitletranslator.domain.sync.SyncResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * 句子收藏同步关键逻辑测试
 *
 * 覆盖：墓碑推送与确认、一次完整的推送+拉取。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SentenceSyncTest {

    private lateinit var context: Context
    private lateinit var repository: SqliteSentenceRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(SentenceDatabaseHelper.DATABASE_NAME)
        repository = SqliteSentenceRepository(context)
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(SentenceDatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun `delete writes tombstone and ack removes it`() = runBlocking {
        repository.add("Hello there.", "你好。")
        val upsert = repository.pendingChanges(10).single()
        assertEquals(SentenceSyncOperation.UPSERT, upsert.operation)
        repository.markUpsertSynced(upsert.clientReferenceId, upsert.updatedAt, "remote-hello")
        assertFalse(repository.hasPendingChanges())

        repository.remove("hello there.")
        assertFalse(repository.isSaved("Hello there."))
        val tombstone = repository.pendingChanges(10).single()
        assertEquals(SentenceSyncOperation.DELETE, tombstone.operation)
        assertEquals("remote-hello", tombstone.remoteId)
        assertEquals(upsert.clientReferenceId, tombstone.clientReferenceId)

        repository.deleteSynced(tombstone.clientReferenceId, tombstone.updatedAt)
        assertFalse(repository.hasPendingChanges())
    }

    @Test
    fun `sync pushes local changes and pulls remote page`() = runBlocking {
        repository.add("Hello there.", "你好。")
        val stateStore = FakeSyncStateStore()
        val api = FakeSentenceApi { request ->
            val pushed = request.changes.single()
            SentenceSyncResponseDto(
                serverTime = "2026-11-11T00:00:02Z",
                nextSince = "cursor-1",
                hasMore = false,
                accepted = listOf(
                    SentenceChangeDto(
                        clientReferenceId = pushed.clientReferenceId,
                        remoteId = "remote-hello",
                        operation = "upsert",
                        updatedAt = pushed.updatedAt,
                        data = null
                    )
                ),
                changes = listOf(
                    SentenceChangeDto(
                        clientReferenceId = null,
                        remoteId = "remote-cloud",
                        operation = "upsert",
                        updatedAt = "2026-11-11T00:00:01Z",
                        data = SentenceSnapshotDto(
                            source = "A sentence from another device.",
                            translation = "另一台设备收藏的句子。",
                            createdAt = "2026-11-11T00:00:01Z"
                        )
                    )
                )
            )
        }
        val manager = SentenceSyncManager(
            api = api,
            store = repository,
            stateStore = stateStore,
            sessionManager = loggedInSession(),
            networkStatus = NetworkStatus { true }
        )

        assertEquals(SyncResult.Success, manager.sync())
        assertEquals(1, api.requests.size)
        assertFalse("已确认的本地变更应清掉 dirty", repository.hasPendingChanges())
        assertTrue(repository.isSaved("Hello there."))
        assertTrue(repository.isSaved("A sentence from another device."))
        assertEquals("cursor-1", stateStore.cursorFor("user-1"))
    }

    @Test
    fun `upload truncates fields to server validation limits`() = runBlocking {
        repository.add("s".repeat(800), "t".repeat(3_000))
        var pushed: SentenceSnapshotDto? = null
        val api = FakeSentenceApi { request ->
            pushed = request.changes.single().data
            acceptedResponse(request, "remote-long")
        }
        val manager = SentenceSyncManager(
            api = api,
            store = repository,
            stateStore = FakeSyncStateStore(),
            sessionManager = loggedInSession(),
            networkStatus = NetworkStatus { true }
        )

        assertEquals(SyncResult.Success, manager.sync())
        val data = checkNotNull(pushed)
        assertEquals(500, data.source.length)
        assertEquals(2_000, data.translation?.length)
    }

    @Test
    fun `invalid cursor is discarded and next sync starts from scratch`() = runBlocking {
        repository.add("Hello there.", "你好。")
        val stateStore = FakeSyncStateStore().apply {
            setLastUserId("user-1")
            saveCursor("user-1", "stale-cursor")
        }
        var calls = 0
        val api = FakeSentenceApi { request ->
            calls++
            if (calls == 1) throw httpError(400, "INVALID_CURSOR")
            acceptedResponse(request, "remote-hello")
        }
        val manager = SentenceSyncManager(
            api = api,
            store = repository,
            stateStore = stateStore,
            sessionManager = loggedInSession(),
            networkStatus = NetworkStatus { true }
        )

        assertEquals(SyncResult.Retryable("invalid cursor"), manager.sync())
        assertNull("游标失效后必须丢弃", stateStore.cursorFor("user-1"))

        assertEquals(SyncResult.Success, manager.sync())
        assertNull("重新同步必须从头全量拉取", api.requests[1].since)
        assertEquals("cursor-1", stateStore.cursorFor("user-1"))
    }

    private fun acceptedResponse(
        request: SentenceSyncRequestDto,
        remoteId: String
    ): SentenceSyncResponseDto = SentenceSyncResponseDto(
        serverTime = "2026-11-11T00:00:02Z",
        nextSince = "cursor-1",
        hasMore = false,
        accepted = request.changes.map { change ->
            SentenceChangeDto(
                clientReferenceId = change.clientReferenceId,
                remoteId = remoteId,
                operation = "upsert",
                updatedAt = change.updatedAt,
                data = null
            )
        },
        changes = emptyList()
    )

    private fun loggedInSession(): AuthSessionManager =
        AuthSessionManager(InMemorySessionStore()).apply {
            setSession(
                AuthUser(
                    id = "user-1",
                    email = "user@example.com",
                    emailVerified = true,
                    displayName = null,
                    status = "active",
                    createdAt = null,
                    updatedAt = null
                ),
                AuthTokens(
                    accessToken = "access",
                    accessTokenExpiresAt = Instant.now().plusSeconds(3600),
                    refreshToken = "refresh",
                    refreshTokenExpiresAt = Instant.now().plusSeconds(7200)
                )
            )
        }

    private class FakeSyncStateStore : ISentenceSyncStateStore {
        private val cursors = mutableMapOf<String, String>()
        private var lastUser: String? = null

        override fun cursorFor(userId: String): String? = cursors[userId]
        override fun saveCursor(userId: String, cursor: String) {
            cursors[userId] = cursor
        }

        override fun clearCursor(userId: String) {
            cursors.remove(userId)
        }

        override fun lastUserId(): String? = lastUser
        override fun setLastUserId(userId: String) {
            lastUser = userId
        }
    }

    private class FakeSentenceApi(
        private val respond: suspend (SentenceSyncRequestDto) -> SentenceSyncResponseDto
    ) : SentenceApi {
        val requests = mutableListOf<SentenceSyncRequestDto>()

        override suspend fun sync(
            request: SentenceSyncRequestDto
        ): ApiEnvelope<SentenceSyncResponseDto> {
            requests += request
            return ApiEnvelope(respond(request))
        }
    }
}
