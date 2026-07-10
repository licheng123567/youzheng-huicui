package com.youzheng.huicui.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 拨号前落库的通话会话（BR-APP-03）。这是录音↔案件匹配的**唯一锚点**，
 * 必须先落库再拨号 —— App 一旦被系统杀在通话中，重启后靠它才能找回这通电话属于哪个案件。
 */
@Entity(tableName = "call_session")
data class CallSessionEntity(
    @PrimaryKey val callId: String,
    val caseId: String,
    val number: String,
    val dialStartTs: Long,
    val callEndTs: Long? = null,
    /** CallLog 查到的时长；未查到为 null */
    val durationSec: Int? = null,
    /** 终态：UNRESOLVED / NOT_CONNECTED / MATCHED / RECORDING_MISSING */
    val state: String = "UNRESOLVED",
)

/** 上传队列（PRD §3.4 / A.5c）。进程被杀、重启都能恢复。 */
@Entity(tableName = "upload_item")
data class UploadItemEntity(
    /** 用 fileHash 做主键：本地天然去重，且它就是发给服务端的 Idempotency-Key。 */
    @PrimaryKey val fileHash: String,
    val callId: String?,
    val caseId: String,
    val filePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val recordedAtMillis: Long?,
    val durationSec: Int?,
    val phone: String?,
    /** APP_AUTO / MANUAL */
    val source: String,
    /** UploadStatus.name */
    val status: String,
    val retryCount: Int = 0,
    val nextAttemptAt: Long = 0,
    val createdTs: Long,
    val uploadedTs: Long? = null,
    val lastError: String? = null,
    /** 服务端返回的 recordingId（用于查解析状态） */
    val serverRecordingId: String? = null,
)

@Dao
interface CallSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: CallSessionEntity)

    @Update
    suspend fun update(s: CallSessionEntity)

    @Query("SELECT * FROM call_session WHERE callId = :id")
    suspend fun byId(id: String): CallSessionEntity?

    /** 匹配时只看最近这段时间的会话：太老的会话再冒出录音，多半是别的东西。 */
    @Query("SELECT * FROM call_session WHERE dialStartTs >= :since ORDER BY dialStartTs DESC")
    suspend fun since(since: Long): List<CallSessionEntity>

    @Query("SELECT * FROM call_session WHERE callEndTs IS NULL ORDER BY dialStartTs DESC LIMIT 1")
    suspend fun openSession(): CallSessionEntity?

    @Query("DELETE FROM call_session WHERE dialStartTs < :before")
    suspend fun purgeBefore(before: Long)
}

@Dao
interface UploadDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: UploadItemEntity): Long

    @Update
    suspend fun update(item: UploadItemEntity)

    @Query("SELECT * FROM upload_item WHERE fileHash = :hash")
    suspend fun byHash(hash: String): UploadItemEntity?

    @Query("SELECT * FROM upload_item ORDER BY createdTs DESC")
    fun observeAll(): Flow<List<UploadItemEntity>>

    /** 到点该发的：PENDING 或退避到期的 RETRYING。 */
    @Query(
        "SELECT * FROM upload_item WHERE status IN ('PENDING','RETRYING') AND nextAttemptAt <= :now" +
            " ORDER BY createdTs ASC LIMIT :limit",
    )
    suspend fun dueForUpload(now: Long, limit: Int = 5): List<UploadItemEntity>

    @Query("SELECT * FROM upload_item WHERE status = 'UPLOADED' AND uploadedTs IS NOT NULL")
    suspend fun uploaded(): List<UploadItemEntity>

    @Query("SELECT COUNT(*) FROM upload_item WHERE status IN ('PENDING','UPLOADING','RETRYING')")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM upload_item WHERE fileHash = :hash")
    suspend fun delete(hash: String)

    @Query("DELETE FROM upload_item")
    suspend fun clear()
}
