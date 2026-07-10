package com.youzheng.huicui.app.data.case

import com.youzheng.huicui.app.api.apis.DispatchApi
import com.youzheng.huicui.app.api.apis.NotificationApi
import com.youzheng.huicui.app.api.apis.WorkbenchApi
import com.youzheng.huicui.app.api.models.Notification
import com.youzheng.huicui.app.api.apis.DispatchApi.PoolListSea
import com.youzheng.huicui.app.api.models.WorkbenchData
import com.youzheng.huicui.app.data.net.parseApiError
import java.io.IOException

class WorkbenchRepository(private val api: WorkbenchApi) {
    suspend fun load(): Result<WorkbenchData> = runCatchingApi {
        val res = api.getWorkbench()
        res.body()?.takeIf { res.isSuccessful } ?: error("加载工作台失败（HTTP ${res.code()}）")
    }
}

class SeaRepository(private val api: DispatchApi) {

    suspend fun list(pool: PoolListSea, page: Int = 1, size: Int = 20): Result<List<SeaCardState>> =
        runCatchingApi {
            val res = api.listSea(pool = pool, page = page, size = size)
            val body = res.body()?.takeIf { res.isSuccessful }
                ?: error("加载公海失败（HTTP ${res.code()}）")
            body.items.orEmpty().map(SeaCardState::from)
        }

    /**
     * 抢单。Idempotency-Key 由 AuthInterceptor 兜底注入；重试同一逻辑操作时应由调用方传同一个 key，
     * 但抢单是幂等的（重复抢自己已持有的案件后端返回同结果），此处不额外处理。
     * 失败原因要原样给用户看：`BIZ_HOLD_CAP`（持仓已满）与「被别人抢先」是两回事。
     */
    suspend fun claim(caseId: String): Result<Unit> = try {
        val res = api.claimCase(caseId)
        if (res.isSuccessful) Result.success(Unit)
        else Result.failure(
            IllegalStateException(
                parseApiError(res.errorBody()?.string())?.message ?: "抢单失败（HTTP ${res.code()}）",
            ),
        )
    } catch (e: IOException) {
        Result.failure(e)
    }
}

class NotificationRepository(private val api: NotificationApi) {

    suspend fun list(unreadOnly: Boolean = false, page: Int = 1, size: Int = 20): Result<List<Notification>> =
        runCatchingApi {
            val res = api.listNotifications(unreadOnly = unreadOnly, page = page, size = size)
            val body = res.body()?.takeIf { res.isSuccessful }
                ?: error("加载消息失败（HTTP ${res.code()}）")
            body.items.orEmpty()
        }

    suspend fun markRead(id: String): Result<Unit> = runCatchingApi {
        val res = api.markNotificationRead(id)
        if (!res.isSuccessful) error("标记已读失败（HTTP ${res.code()}）")
    }
}

/**
 * 网络异常与业务失败都收敛成 `Result.failure`，但**消息不同**：
 * IOException 说「连不上服务器」，其余说具体原因。绝不把异常栈甩给用户。
 */
private inline fun <T> runCatchingApi(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: IOException) {
    Result.failure(IOException("无法连接服务器（${e.javaClass.simpleName}）", e))
} catch (e: IllegalStateException) {
    Result.failure(e)
}
