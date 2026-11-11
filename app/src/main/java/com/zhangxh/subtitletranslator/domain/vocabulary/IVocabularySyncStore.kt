package com.zhangxh.subtitletranslator.domain.vocabulary

/**
 * 同步队列的持久化接口
 *
 * 实现必须保证：本地写入先落库，云端确认只清理「推送时那个版本」的行，
 * 避免同步请求在途时用户的新修改被误标为已同步。
 */
interface IVocabularySyncStore {

    /** 读取待推送的 upsert / 删除墓碑，按修改时间升序 */
    suspend fun pendingChanges(limit: Int): List<PendingVocabularyChange>

    /** 是否还有待推送的本地变更 */
    suspend fun hasPendingChanges(): Boolean

    /**
     * 服务端已接受某条 upsert
     *
     * @param pushedUpdatedAt 推送时的本地时间；只有数据库里仍是这个版本才清 dirty
     */
    suspend fun markUpsertSynced(
        clientReferenceId: String,
        pushedUpdatedAt: Long,
        remoteId: String
    )

    /** 服务端已接受某条删除，物理删除本地墓碑 */
    suspend fun deleteSynced(clientReferenceId: String, pushedUpdatedAt: Long)

    /** 应用服务端的增量变更（含删除墓碑），本地更新的 dirty 数据优先 */
    suspend fun applyRemoteChanges(changes: List<RemoteVocabularyChange>)

    /** 当前账号与上次同步账号不一致时，清掉旧的远端映射并重新全量上传 */
    suspend fun resetRemoteMappings()
}
