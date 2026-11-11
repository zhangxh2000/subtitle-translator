package com.zhangxh.subtitletranslator.data.vocabulary

import android.util.Log
import com.zhangxh.subtitletranslator.data.auth.AuthSessionManager
import com.zhangxh.subtitletranslator.data.net.NetworkStatus
import com.zhangxh.subtitletranslator.data.remote.VocabularyApi
import com.zhangxh.subtitletranslator.data.remote.VocabularyChangeDto
import com.zhangxh.subtitletranslator.data.remote.VocabularySyncRequestDto
import com.zhangxh.subtitletranslator.data.remote.toDomain
import com.zhangxh.subtitletranslator.data.remote.toDto
import com.zhangxh.subtitletranslator.domain.auth.AuthState
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularySyncStateStore
import com.zhangxh.subtitletranslator.domain.vocabulary.IVocabularySyncStore
import com.zhangxh.subtitletranslator.domain.vocabulary.PendingVocabularyChange
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularySyncOperation
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularySyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

/**
 * 生词批量增量同步
 *
 * 一次请求同时推送本地队列并按 since 拉取云端增量；服务端返回的 accepted
 * 用来清理本地 dirty 标记，changes 写回本地。整个过程串行执行，避免两个
 * sync 同时在途时互相覆盖游标。
 *
 * 协议契约见 docs/vocabulary-sync-api.md。
 */
class VocabularySyncManager(
    private val api: VocabularyApi,
    private val store: IVocabularySyncStore,
    private val stateStore: IVocabularySyncStateStore,
    private val sessionManager: AuthSessionManager,
    private val networkStatus: NetworkStatus,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val maxPages: Int = DEFAULT_MAX_PAGES
) {

    private companion object {
        const val TAG = "VocabularySync"
        const val DEFAULT_BATCH_SIZE = 100
        const val DEFAULT_MAX_PAGES = 50
    }

    private val mutex = Mutex()

    suspend fun sync(): VocabularySyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val userId = (sessionManager.state.value as? AuthState.LoggedIn)?.user?.id
                ?: return@withContext VocabularySyncResult.NotLoggedIn
            if (!networkStatus.isOnline()) return@withContext VocabularySyncResult.Offline

            try {
                prepareAccount(userId)
                runPages(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                Log.w(TAG, "同步失败: HTTP ${e.code()}")
                classifyHttpError(e.code())
            } catch (e: IOException) {
                Log.w(TAG, "同步网络异常: ${e.javaClass.simpleName}")
                VocabularySyncResult.Retryable("network")
            } catch (e: Exception) {
                Log.e(TAG, "同步异常", e)
                VocabularySyncResult.Retryable(e.javaClass.simpleName)
            }
        }
    }

    /**
     * 换账号时本地词库继续沿用，但远端映射必须清空：
     * 上一个账号的 remoteId 不能带到新账号，否则会把数据写到错误的行上。
     */
    private suspend fun prepareAccount(userId: String) {
        if (stateStore.lastUserId() == userId) return
        Log.d(TAG, "登录账号变化，重置同步映射并全量上传本地生词")
        store.resetRemoteMappings()
        stateStore.clearCursor(userId)
        stateStore.setLastUserId(userId)
    }

    private suspend fun runPages(userId: String): VocabularySyncResult {
        var page = 0
        while (true) {
            val pending = store.pendingChanges(batchSize)
            val since = stateStore.cursorFor(userId)
            val response = api.sync(
                VocabularySyncRequestDto(
                    since = since,
                    limit = batchSize,
                    changes = pending.map { it.toDto() }
                )
            )
            val data = response.data
                ?: return VocabularySyncResult.Retryable("empty response")

            // 先合并云端版本，再清理已确认的本地变更：
            // 这样删除墓碑在合并期间仍然存在，旧的云端记录不会把刚删的词复活
            val remoteChanges = data.changes.orEmpty().mapNotNull { it.toDomain() }
            if (remoteChanges.isNotEmpty()) store.applyRemoteChanges(remoteChanges)
            val acknowledged = acknowledge(data.accepted.orEmpty(), pending)

            val nextSince = data.nextSince?.takeIf { it.isNotBlank() }
                ?: data.serverTime?.takeIf { it.isNotBlank() }
                ?: return VocabularySyncResult.Retryable("missing cursor")
            if (nextSince != since) stateStore.saveCursor(userId, nextSince)
            page++

            val hasMoreLocal = store.hasPendingChanges()
            val hasMoreRemote = data.hasMore
            if (!hasMoreRemote && !hasMoreLocal) {
                Log.d(TAG, "同步完成: pages=$page")
                return VocabularySyncResult.Success
            }
            // 服务端拒绝/忽略了全部变更且游标没动：继续翻页只会打满空请求
            val madeProgress = acknowledged > 0 || remoteChanges.isNotEmpty() || nextSince != since
            if (!madeProgress) {
                return VocabularySyncResult.Retryable("no progress")
            }
            if (page >= maxPages) {
                return VocabularySyncResult.Retryable("page limit reached")
            }
        }
    }

    /**
     * 处理服务端接受回执
     *
     * upsert 需要 remoteId 才能建立映射；删除只要确认 clientReferenceId 被接受即可。
     * 只对本次请求带上去的变更做清理，避免误标在途期间的新修改。
     */
    private suspend fun acknowledge(
        accepted: List<VocabularyChangeDto>,
        pending: List<PendingVocabularyChange>
    ): Int {
        if (accepted.isEmpty() || pending.isEmpty()) return 0
        val acceptedReferences = HashSet<String>()
        val remoteIds = HashMap<String, String>()
        accepted.forEach { dto ->
            val referenceId = dto.clientReferenceId?.takeIf { it.isNotBlank() }
                ?: return@forEach
            acceptedReferences += referenceId
            dto.remoteId?.takeIf { it.isNotBlank() }?.let { remoteIds[referenceId] = it }
        }
        var cleared = 0
        pending.forEach { change ->
            when (change.operation) {
                VocabularySyncOperation.UPSERT -> {
                    val remoteId = remoteIds[change.clientReferenceId] ?: return@forEach
                    store.markUpsertSynced(
                        clientReferenceId = change.clientReferenceId,
                        pushedUpdatedAt = change.updatedAt,
                        remoteId = remoteId
                    )
                    cleared++
                }
                VocabularySyncOperation.DELETE -> {
                    if (change.clientReferenceId in acceptedReferences) {
                        store.deleteSynced(change.clientReferenceId, change.updatedAt)
                        cleared++
                    }
                }
            }
        }
        return cleared
    }

    private fun classifyHttpError(code: Int): VocabularySyncResult = when (code) {
        // 刷新失败时会话已被清空，等下一次登录再同步
        401, 403 -> VocabularySyncResult.NotLoggedIn
        408, 429 -> VocabularySyncResult.Retryable("HTTP $code")
        in 500..599 -> VocabularySyncResult.Retryable("HTTP $code")
        // 其他 4xx 重试也不会成功，保留 dirty 等下次版本修复
        else -> VocabularySyncResult.Permanent("HTTP $code")
    }
}
