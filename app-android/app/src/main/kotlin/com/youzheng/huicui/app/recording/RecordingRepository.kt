package com.youzheng.huicui.app.recording

import com.youzheng.huicui.app.api.apis.CollectionApi
import com.youzheng.huicui.app.api.models.LatestRecording
import com.youzheng.huicui.app.data.db.CallSessionDao
import com.youzheng.huicui.app.data.db.CallSessionEntity
import com.youzheng.huicui.app.data.db.UploadDao
import com.youzheng.huicui.app.data.db.UploadItemEntity
import com.youzheng.huicui.app.data.net.RecordingUploadApi
import com.youzheng.huicui.app.data.net.parseApiError
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** 上传执行的窄接口：单测里用假实现，不必去 mock Retrofit。 */
interface RecordingUploadPort {
    suspend fun upload(item: UploadItemEntity): UploadOutcome
}

class RetrofitRecordingUploadPort(private val api: RecordingUploadApi) : RecordingUploadPort {

    override suspend fun upload(item: UploadItemEntity): UploadOutcome = try {
        val f = File(item.filePath)
        if (!f.exists()) {
            UploadOutcome.Permanent("本地录音文件已不存在：${item.fileName}")
        } else {
            val body = f.asRequestBody(mimeOf(item.fileName))
            val part = MultipartBody.Part.createFormData("file", item.fileName, body)
            val res = api.uploadRecording(
                caseId = item.caseId,
                // 幂等键 = 文件 SHA-256：同一文件重传，服务端返 409 而非重复解析扣费
                idempotencyKey = item.fileHash,
                file = part,
                source = item.source.text(),
                recordedAt = item.recordedAtMillis?.let { Instant.ofEpochMilli(it).toString().text() },
                durationSec = item.durationSec?.toString()?.text(),
                phone = item.phone?.text(),
            )
            UploadOutcomeClassifier.classify(
                httpCode = res.code(),
                errorMessage = if (res.isSuccessful) null else parseApiError(res.errorBody()?.string())?.message,
                recordingId = res.body()?.id,
            )
        }
    } catch (e: IOException) {
        UploadOutcomeClassifier.network("网络不可用（${e.javaClass.simpleName}）")
    }

    private fun String.text(): RequestBody = toRequestBody("text/plain".toMediaTypeOrNull())

    private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "m4a", "3gp" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "amr" -> "audio/amr"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }.toMediaTypeOrNull()
}

/**
 * 录音管线的仓储层：会话生命周期 + 上传队列 + 解析状态查询。
 * 所有「决定」都委托给纯函数（[RecordingMatcher] / [CallOutcomeDecider] / [UploadOutcomeClassifier]），
 * 这里只做 IO 与状态落库。
 */
class RecordingRepository(
    private val sessions: CallSessionDao,
    private val uploads: UploadDao,
    private val port: RecordingUploadPort,
    private val collectionApi: CollectionApi,
    private val store: LocalRecordingStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 拨号前调用（BR-APP-03）。**必须先落库再拨号**：拨出去之后 App 随时可能被系统杀掉。 */
    suspend fun beginCall(caseId: String, number: String): CallSessionEntity {
        val s = CallSessionEntity(
            callId = UUID.randomUUID().toString(),
            caseId = caseId,
            number = number,
            dialStartTs = now(),
        )
        sessions.upsert(s)
        return s
    }

    suspend fun endCall(callId: String, durationSec: Int?) {
        val s = sessions.byId(callId) ?: return
        sessions.update(s.copy(callEndTs = now(), durationSec = durationSec))
    }

    suspend fun openSession(): CallSessionEntity? = sessions.openSession()

    suspend fun recentSessions(windowMs: Long = 6 * 3600_000L): List<CallSessionEntity> =
        sessions.since(now() - windowMs)

    suspend fun markSessionState(callId: String, state: String) {
        sessions.byId(callId)?.let { sessions.update(it.copy(state = state)) }
    }

    /**
     * 匹配成功后入队。`fileHash` 做主键 → 天然去重，兜底扫描重复发现同一文件不会入队两次。
     *
     * 入队时把录音**复制进 App 私有目录**，队列里存的是副本路径：
     * 系统录音目录随时可能被 ROM 的存储清理、或被用户手动删掉，
     * 而离线时一条录音可能要在队列里躺几个小时。原文件一根手指都不碰。
     */
    suspend fun enqueue(
        caseId: String,
        callId: String?,
        candidate: RecordingCandidate,
        durationSec: Int?,
        phone: String?,
        source: String,
        recordedAtMillis: Long? = null,
    ): UploadItemEntity {
        val original = File(candidate.path)
        val hash = FileHash.sha256(original)
        uploads.byHash(hash)?.let { return it }   // 已在队列（或已上传过）

        val copy = store.copyIn(original, hash)
        val item = UploadItemEntity(
            fileHash = hash,
            callId = callId,
            caseId = caseId,
            filePath = copy.absolutePath,
            fileName = candidate.fileName,
            sizeBytes = candidate.sizeBytes,
            recordedAtMillis = recordedAtMillis ?: candidate.lastModified,
            durationSec = durationSec,
            phone = phone,
            source = source,
            status = UploadStatus.PENDING.name,
            createdTs = now(),
        )
        uploads.insertIfAbsent(item)
        return item
    }

    /** 队列里到点该发的项。 */
    suspend fun due(): List<UploadItemEntity> = uploads.dueForUpload(now())

    fun observeQueue(): Flow<List<UploadItemEntity>> = uploads.observeAll()
    fun observePendingCount(): Flow<Int> = uploads.observePendingCount()

    /**
     * 发一条。返回是否已终结（成功或永久失败）。
     * 失败按指数退避改 nextAttemptAt；重试耗尽转 FAILED 等用户手动处理（BR-APP-07）。
     */
    suspend fun uploadOnce(item: UploadItemEntity): UploadOutcome {
        uploads.update(item.copy(status = UploadStatus.UPLOADING.name))
        val outcome = port.upload(item)
        when (outcome) {
            is UploadOutcome.Success -> {
                uploads.update(
                    item.copy(
                        status = UploadStatus.UPLOADED.name,
                        uploadedTs = now(),
                        serverRecordingId = outcome.recordingId,
                        lastError = null,
                    ),
                )
                // 服务端已收下（V921 存了 audio_bytes，可流式回听）。本地副本立即删，
                // 不留 7 天——数据落地越少越好。系统录音目录里的原件不动。
                store.delete(item.filePath)
            }
            is UploadOutcome.Permanent -> uploads.update(
                item.copy(status = UploadStatus.FAILED.name, lastError = outcome.message),
            )
            is UploadOutcome.Retryable -> {
                val retry = item.retryCount + 1
                if (UploadPolicy.shouldGiveUp(retry)) {
                    uploads.update(
                        item.copy(
                            status = UploadStatus.FAILED.name,
                            retryCount = retry,
                            lastError = "${outcome.message}（已重试 $retry 次）",
                        ),
                    )
                } else {
                    uploads.update(
                        item.copy(
                            status = UploadStatus.RETRYING.name,
                            retryCount = retry,
                            nextAttemptAt = now() + UploadPolicy.backoffMillis(retry),
                            lastError = outcome.message,
                        ),
                    )
                }
            }
        }
        return outcome
    }

    /** 用户在队列页点「重试」：立刻可发，重试次数归零。 */
    suspend fun retryNow(hash: String) {
        uploads.byHash(hash)?.let {
            uploads.update(it.copy(status = UploadStatus.PENDING.name, retryCount = 0, nextAttemptAt = 0))
        }
    }

    suspend fun drop(hash: String) = uploads.delete(hash)

    /**
     * 兜底清理：正常路径上传成功即删副本，这里处理「删的时候进程被杀」之类的残留。
     * 只删 App 私有目录里的副本，永不触碰系统录音目录。
     */
    suspend fun purgeUploadedLocalFiles() {
        uploads.uploaded().forEach { item -> store.delete(item.filePath) }
    }

    /** 「获取最新通话录音」——判断录音有没有上来、解析到哪一步（BR-M4-01b）。 */
    suspend fun latest(caseId: String): Result<LatestRecording> = try {
        val res = collectionApi.getLatestRecording(caseId)
        val body = res.body()
        if (res.isSuccessful && body != null) Result.success(body)
        else Result.failure(IllegalStateException("查询失败（HTTP ${res.code()}）"))
    } catch (e: IOException) {
        Result.failure(e)
    }

    /**
     * 退出登录：队列里的录音属于上一个账号的案件，换人登录后必然 403。
     * 清空队列 + 删除全部私有副本 + 清通话会话。系统录音目录里的原件仍在，用户不会丢东西。
     * 界面必须在还有未上传项时先警示用户（见 MeScreen 的退出确认）。
     */
    suspend fun clearOnLogout() {
        uploads.clear()
        sessions.purgeBefore(Long.MAX_VALUE)
        store.deleteAll()
    }
}
