package com.youzheng.huicui.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * 案件列表的离线缓存（验收④：断网后仍能读上次看到的案件）。
 *
 * 只缓存**列表级**字段——催收员在电梯里、地下车库里断网时要看的是「我手上有哪些案件、欠多少、什么状态」。
 * 联系人电话、时间线、话术这些详情不入缓存：它们是敏感数据，且离线也打不了电话。
 *
 * 缓存的是**后端已经吐出来的形状**（包括 `ownerName` 可能就是 `***`），不做任何客户端脱敏推断——
 * 脱敏是后端的权威判断（`redacted` 实测语义是「案件已关闭」，不是「非持有人」）。
 */
@Entity(tableName = "cached_case")
data class CaseEntity(
    @PrimaryKey val id: String,
    val acctNo: String,
    val ownerName: String,
    val room: String,
    val projectName: String,
    val dueCents: Long,
    val status: String,
    val pool: String,
    val redacted: Boolean,
    /** 写入时刻（epoch millis），用于给用户显示「数据截至 …」 */
    val cachedAt: Long,
)

@Dao
interface CaseDao {
    @Query("SELECT * FROM cached_case ORDER BY id DESC")
    suspend fun all(): List<CaseEntity>

    @Query("SELECT * FROM cached_case WHERE id = :id")
    suspend fun byId(id: String): CaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cases: List<CaseEntity>)

    @Query("DELETE FROM cached_case")
    suspend fun clear()

    /**
     * 整体替换。分两步且包在事务里：否则一次网络成功后若进程被杀，
     * 缓存会停在「已清空、未写入」的状态 —— 下次断网打开就是空列表，比显示旧数据更糟。
     */
    @Transaction
    suspend fun replaceAll(cases: List<CaseEntity>) {
        clear()
        upsertAll(cases)
    }
}

@Database(entities = [CaseEntity::class], version = 1, exportSchema = false)
abstract class HuicuiDb : RoomDatabase() {
    abstract fun caseDao(): CaseDao
}
