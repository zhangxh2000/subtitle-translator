package com.zhangxh.subtitletranslator.data.vocabulary

import android.app.Application
import android.content.Context
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.auth.InMemorySessionStore
import com.zhangxh.subtitletranslator.data.net.NetworkStatus
import com.zhangxh.subtitletranslator.data.remote.ApiEnvelope
import com.zhangxh.subtitletranslator.data.remote.VocabularyApi
import com.zhangxh.subtitletranslator.data.remote.VocabularyChangeDto
import com.zhangxh.subtitletranslator.data.remote.VocabularySnapshotDto
import com.zhangxh.subtitletranslator.data.remote.VocabularySyncRequestDto
import com.zhangxh.subtitletranslator.data.remote.VocabularySyncResponseDto
import com.zhangxh.subtitletranslator.domain.auth.AuthTokens
import com.zhangxh.subtitletranslator.domain.auth.AuthUser
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularySyncStateStore
import com.zhangxh.subtitletranslator.domain.vocabulary.LearningStatus
import com.zhangxh.subtitletranslator.domain.vocabulary.RemoteVocabularyChange
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWordContext
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyItemType
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularySnapshot
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularySyncOperation
import com.zhangxh.subtitletranslator.domain.sync.SyncResult
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.domain.wordextractor.WordEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * 生词同步关键逻辑测试
 *
 * 覆盖：墓碑推送与确认、客户端版本保护、云端变更合并、一次完整的推送+拉取。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class VocabularySyncTest {

    private lateinit var context: Context
    private lateinit var repository: SqliteVocabularyRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(VocabularyDatabaseHelper.DATABASE_NAME)
        repository = SqliteVocabularyRepository(context)
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(VocabularyDatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun `delete writes tombstone and ack removes it`() = runBlocking {
        repository.add(entry("hello"))
        val upsert = repository.pendingChanges(10).single()
        assertEquals(VocabularySyncOperation.UPSERT, upsert.operation)
        repository.markUpsertSynced(upsert.clientReferenceId, upsert.updatedAt, "remote-hello")
        assertFalse(repository.hasPendingChanges())

        repository.remove("hello")
        assertTrue(repository.list().isEmpty())
        val tombstone = repository.pendingChanges(10).single()
        assertEquals(VocabularySyncOperation.DELETE, tombstone.operation)
        assertEquals("remote-hello", tombstone.remoteId)
        assertEquals(upsert.clientReferenceId, tombstone.clientReferenceId)

        repository.deleteSynced(tombstone.clientReferenceId, tombstone.updatedAt)
        assertFalse(repository.hasPendingChanges())
    }

    @Test
    fun `re-adding a deleted word revives the same row`() = runBlocking {
        repository.add(entry("hello"))
        val original = repository.pendingChanges(10).single()
        repository.remove("hello")

        repository.add(entry("hello"))
        val revived = repository.pendingChanges(10).single()
        assertEquals(VocabularySyncOperation.UPSERT, revived.operation)
        assertEquals(original.clientReferenceId, revived.clientReferenceId)
        assertEquals(listOf("hello"), repository.list().map { it.word })
    }

    @Test
    fun `mark synced only clears the pushed version`() = runBlocking {
        repository.add(entry("hello"))
        val pushed = repository.pendingChanges(10).single()

        // 模拟请求在途时用户又复习了一次
        Thread.sleep(10)
        repository.updateLearningState("hello", LearningStatus.LEARNING, 2, System.currentTimeMillis())
        repository.markUpsertSynced(pushed.clientReferenceId, pushed.updatedAt, "remote-hello")

        assertTrue("在途期间的新修改不能被误标为已同步", repository.hasPendingChanges())
        val latest = repository.pendingChanges(10).single()
        assertTrue(latest.updatedAt > pushed.updatedAt)
    }

    @Test
    fun `remote change does not overwrite newer local edit`() = runBlocking {
        repository.add(entry("hello", phonetic = "LOCAL"))
        val stale = remoteChange(updatedAt = 1_000L, phonetic = "STALE")
        repository.applyRemoteChanges(listOf(stale))
        assertEquals("LOCAL", repository.find("hello")?.phonetic)

        val fresh = remoteChange(updatedAt = System.currentTimeMillis() + 60_000, phonetic = "REMOTE")
        repository.applyRemoteChanges(listOf(fresh))
        assertEquals("REMOTE", repository.find("hello")?.phonetic)
    }

    @Test
    fun `sync pushes local changes and pulls remote page`() = runBlocking {
        repository.add(entry("hello"))
        val stateStore = FakeSyncStateStore()
        val api = FakeVocabularyApi { request ->
            val pushed = request.changes.single()
            VocabularySyncResponseDto(
                serverTime = "2026-11-11T00:00:02Z",
                nextSince = "cursor-1",
                hasMore = false,
                accepted = listOf(
                    VocabularyChangeDto(
                        clientReferenceId = pushed.clientReferenceId,
                        remoteId = "remote-hello",
                        operation = "upsert",
                        updatedAt = pushed.updatedAt,
                        data = null
                    )
                ),
                changes = listOf(
                    VocabularyChangeDto(
                        clientReferenceId = null,
                        remoteId = "remote-cloud",
                        operation = "upsert",
                        updatedAt = "2026-11-11T00:00:01Z",
                        data = VocabularySnapshotDto(
                            term = "cloud",
                            phonetic = "klaʊd",
                            meanings = emptyList(),
                            difficulty = "EASY",
                            itemType = "word",
                            context = null,
                            learningStatus = "new",
                            familiarity = 0,
                            lastReviewedAt = null,
                            createdAt = "2026-11-11T00:00:01Z"
                        )
                    )
                )
            )
        }
        val manager = VocabularySyncManager(
            api = api,
            store = repository,
            stateStore = stateStore,
            sessionManager = loggedInSession(),
            networkStatus = NetworkStatus { true }
        )

        assertEquals(SyncResult.Success, manager.sync())
        assertEquals(1, api.requests.size)
        assertFalse("已确认的本地变更应清掉 dirty", repository.hasPendingChanges())
        assertEquals("cloud", repository.find("cloud")?.word)
        assertEquals("hello", repository.find("hello")?.word)
        assertEquals("cursor-1", stateStore.cursorFor("user-1"))
    }

    @Test
    fun `sync does nothing when logged out`() = runBlocking {
        val api = FakeVocabularyApi {
            error("未登录不应发起请求")
        }
        val manager = VocabularySyncManager(
            api = api,
            store = repository,
            stateStore = FakeSyncStateStore(),
            sessionManager = AuthSessionManager(InMemorySessionStore()),
            networkStatus = NetworkStatus { true }
        )

        assertEquals(SyncResult.NotLoggedIn, manager.sync())
        assertEquals(0, api.requests.size)
    }

    private fun entry(word: String, phonetic: String = "ˈwɜːd") = WordEntry(
        word = word,
        phonetic = phonetic,
        meanings = emptyList(),
        difficulty = WordDifficulty.MEDIUM
    )

    private fun remoteChange(updatedAt: Long, phonetic: String) = RemoteVocabularyChange(
        clientReferenceId = null,
        remoteId = "remote-hello",
        operation = VocabularySyncOperation.UPSERT,
        updatedAt = updatedAt,
        snapshot = VocabularySnapshot(
            word = "hello",
            phonetic = phonetic,
            meanings = emptyList(),
            difficulty = WordDifficulty.MEDIUM,
            itemType = VocabularyItemType.WORD,
            context = SavedWordContext("hello there", "你好"),
            learningStatus = LearningStatus.LEARNING,
            familiarity = 0,
            lastReviewedAt = null,
            createdAt = 1_000L
        )
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

    private class FakeSyncStateStore : IVocabularySyncStateStore {
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

    private class FakeVocabularyApi(
        private val respond: suspend (VocabularySyncRequestDto) -> VocabularySyncResponseDto
    ) : VocabularyApi {
        val requests = mutableListOf<VocabularySyncRequestDto>()

        override suspend fun sync(
            request: VocabularySyncRequestDto
        ): ApiEnvelope<VocabularySyncResponseDto> {
            requests += request
            return ApiEnvelope(respond(request))
        }
    }
}
