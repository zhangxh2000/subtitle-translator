package com.zhangxh.subtitletranslator.data.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 词典数据库（assets 预置 → 应用私有目录 → 只读打开）
 *
 * Android 无法直接打开 APK 内被压缩的数据库文件，因此首次使用时需要把 assets 中的
 * 数据库复制到应用私有目录。此处负责这件事，并保证：
 * - **不在主线程做拷贝**：数据库约 16MB，主线程拷贝会阻塞界面；[prepare] 为挂起函数
 * - **原子写入**：先写临时文件再改名，避免中途失败留下损坏的半成品
 * - **版本校验**：读取库内 metadata 表的 schema_version，与预期不符则重新复制
 *
 * 数据库由 scripts/import_ecdict.py 生成，多语言词典只需换一份 assets 文件。
 */
class DictionaryDatabase(
    context: Context,
    /** assets 中的数据库文件名 */
    val assetName: String = DEFAULT_ASSET_NAME,
    /** 期望的 schema 版本，与生成脚本中的 SCHEMA_VERSION 对应 */
    private val expectedSchemaVersion: Int = DEFAULT_SCHEMA_VERSION
) {

    companion object {
        private const val TAG = "DictionaryDatabase"

        const val DEFAULT_ASSET_NAME = "dictionary.db"
        const val DEFAULT_SCHEMA_VERSION = 1

        private const val META_TABLE = "metadata"
        private const val PREPARING_SUFFIX = ".preparing"
    }

    private val appContext = context.applicationContext
    private val databaseFile: File = appContext.getDatabasePath(assetName)
    private val prepareMutex = Mutex()

    @Volatile
    private var database: SQLiteDatabase? = null

    /**
     * 确保数据库已就绪（必要时从 assets 复制）
     *
     * @return 是否可用。复制失败、assets 缺失或校验不通过都返回 false，不抛异常
     */
    suspend fun prepare(): Boolean {
        database?.takeIf { it.isOpen }?.let { return true }

        return prepareMutex.withLock {
            // 双重检查：可能已被其它协程准备好
            database?.takeIf { it.isOpen }?.let { return@withLock true }

            withContext(Dispatchers.IO) {
                try {
                    if (!isUsable(databaseFile)) {
                        copyFromAssets()
                    }
                    val db = SQLiteDatabase.openDatabase(
                        databaseFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY
                    )
                    database = db
                    Log.d(TAG, "词典数据库就绪: $assetName (${databaseFile.length() / 1024} KB)")
                    true
                } catch (e: IOException) {
                    Log.e(TAG, "词典数据库复制失败，请确认 assets/$assetName 存在", e)
                    false
                } catch (e: Exception) {
                    Log.e(TAG, "词典数据库打开失败: $assetName", e)
                    false
                }
            }
        }
    }

    /**
     * 获取已打开的只读数据库
     *
     * @return 尚未就绪时返回 null，调用方应先检查 [isReady] 或调用 [prepare]
     */
    fun readable(): SQLiteDatabase? = database?.takeIf { it.isOpen }

    /** 数据库是否已就绪 */
    fun isReady(): Boolean = readable() != null

    /** 关闭数据库连接 */
    fun close() {
        try {
            database?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭词典数据库失败", e)
        }
        database = null
    }

    /**
     * 判断已有文件是否可用：能打开、schema 版本匹配、且确有数据
     */
    private fun isUsable(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false

        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val version = db.rawQuery(
                    "SELECT value FROM $META_TABLE WHERE key = ?",
                    arrayOf("schema_version")
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.toIntOrNull() else null
                }

                val matches = version == expectedSchemaVersion
                if (!matches) {
                    Log.w(TAG, "词典 schema 版本不匹配（文件 $version，期望 $expectedSchemaVersion），将重新复制")
                }
                matches
            }
        } catch (e: Exception) {
            Log.w(TAG, "已有词典文件不可用，将重新复制", e)
            false
        }
    }

    /**
     * 从 assets 复制数据库文件（先写临时文件再改名，保证原子性）
     */
    private fun copyFromAssets() {
        val tmpFile = File(databaseFile.parentFile, "${databaseFile.name}$PREPARING_SUFFIX")
        try {
            databaseFile.parentFile?.mkdirs()
            tmpFile.delete()

            appContext.assets.open(assetName).use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (tmpFile.length() == 0L) {
                throw IOException("assets/$assetName 内容为空")
            }

            // rename 在同一分区内是原子操作，避免读到写了一半的文件
            databaseFile.delete()
            if (!tmpFile.renameTo(databaseFile)) {
                throw IOException("重命名词典数据库失败: ${tmpFile.absolutePath}")
            }

            if (!isUsable(databaseFile)) {
                databaseFile.delete()
                throw IOException("assets/$assetName 校验失败（schema 版本不符或内容损坏）")
            }
            Log.d(TAG, "已从 assets 复制词典数据库: ${databaseFile.length() / 1024} KB")
        } finally {
            tmpFile.delete()
        }
    }
}
