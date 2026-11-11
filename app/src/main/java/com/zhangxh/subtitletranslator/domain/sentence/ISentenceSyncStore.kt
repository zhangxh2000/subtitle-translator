package com.zhangxh.subtitletranslator.domain.sentence

/**
 * 句子同步所需的本地队列读写
 *
 * 与业务仓库分开：同步器只关心 dirty 队列、远端映射和云端变更的落地。
 */
interface ISentenceSyncStore {

    /** 按 updatedAt 升序取出待推送变更 */
    suspend fun pendingChanges(limit: Int): List<PendingSentenceChange>

    suspend fun hasPendingChanges(): Boolean

    /** 服务端已接受 upsert；只清理推送时那个版本 */
    suspend fun markUpsertSynced(
        clientReferenceId: String,
        pushedUpdatedAt: Long,
        remoteId: String
    )

    /** 服务端已接受删除；物理清除墓碑 */
    suspend fun deleteSynced(clientReferenceId: String, pushedUpdatedAt: Long)

    /** 合并云端变更；本地更新的未推送修改优先 */
    suspend fun applyRemoteChanges(changes: List<RemoteSentenceChange>)

    /** 换账号：清空远端映射，把本地活跃句子重新排队 */
    suspend fun resetRemoteMappings()
}
