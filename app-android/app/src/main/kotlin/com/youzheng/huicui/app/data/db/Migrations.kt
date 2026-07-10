package com.youzheng.huicui.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2：加通话会话与上传队列两张表。
 *
 * **必须写真迁移，不能 fallbackToDestructiveMigration。**
 * `cached_case` 丢了无所谓（重拉即可），但 `upload_item` 里躺着的是**还没传上去的通话录音** ——
 * 那是催收员刚打完的电话，丢了就永远找不回来了，而且服务端也不会有。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `call_session` (
                `callId` TEXT NOT NULL,
                `caseId` TEXT NOT NULL,
                `number` TEXT NOT NULL,
                `dialStartTs` INTEGER NOT NULL,
                `callEndTs` INTEGER,
                `durationSec` INTEGER,
                `state` TEXT NOT NULL,
                PRIMARY KEY(`callId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `upload_item` (
                `fileHash` TEXT NOT NULL,
                `callId` TEXT,
                `caseId` TEXT NOT NULL,
                `filePath` TEXT NOT NULL,
                `fileName` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `recordedAtMillis` INTEGER,
                `durationSec` INTEGER,
                `phone` TEXT,
                `source` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `retryCount` INTEGER NOT NULL,
                `nextAttemptAt` INTEGER NOT NULL,
                `createdTs` INTEGER NOT NULL,
                `uploadedTs` INTEGER,
                `lastError` TEXT,
                `serverRecordingId` TEXT,
                PRIMARY KEY(`fileHash`)
            )
            """.trimIndent(),
        )
    }
}
