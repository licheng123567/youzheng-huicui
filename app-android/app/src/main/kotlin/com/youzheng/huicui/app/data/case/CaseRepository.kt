package com.youzheng.huicui.app.data.case

import com.youzheng.huicui.app.api.models.Case
import com.youzheng.huicui.app.api.models.CaseDetail
import com.youzheng.huicui.app.data.db.CaseDao
import com.youzheng.huicui.app.data.db.CaseEntity
import java.io.IOException

/** 列表结果。`fromCache=true` 时界面必须明确告诉用户「这是离线数据」，不能假装在线。 */
data class CaseList(
    val items: List<CaseEntity>,
    val fromCache: Boolean,
    val cachedAt: Long? = null,
)

/**
 * 案件仓储。**单一事实源**：网络成功 → 落库并返回；网络失败 → 读库（验收④）。
 *
 * 「网络失败」只认 [IOException]（连不上/超时）。HTTP 4xx/5xx **不算**离线：
 * 那是服务端明确的回答（403 无权、500 出错），拿旧缓存去糊弄用户，比报错更坏。
 */
class CaseRepository(
    private val api: CaseApiPort,
    private val dao: CaseDao,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun list(query: String? = null, page: Int = 1, size: Int = 20): Result<CaseList> {
        return try {
            val res = api.listCases(q = query?.takeIf { it.isNotBlank() }, page = page, size = size)
            val body = res.body()
            if (!res.isSuccessful || body == null) {
                return Result.failure(IllegalStateException("加载案件失败（HTTP ${res.code()}）"))
            }
            val stamp = now()
            val entities = body.items.orEmpty().map { it.toEntity(stamp) }
            // 只有「无过滤条件的第一页」才配当缓存：带 q 的结果是子集，缓存它会让离线列表凭空变短。
            if (query.isNullOrBlank() && page == 1) dao.replaceAll(entities)
            Result.success(CaseList(entities, fromCache = false))
        } catch (e: IOException) {
            val cached = dao.all()
            if (cached.isEmpty()) Result.failure(e)
            else Result.success(CaseList(cached, fromCache = true, cachedAt = cached.maxOf { it.cachedAt }))
        }
    }

    /** 详情不入缓存（含联系人电话等敏感数据，且离线也打不了电话）。断网时返回 failure，由界面退回缓存的列表信息。 */
    suspend fun detail(id: String): Result<CaseDetail> = try {
        val res = api.getCase(id)
        val body = res.body()
        if (res.isSuccessful && body != null) Result.success(body)
        else Result.failure(IllegalStateException("加载案件详情失败（HTTP ${res.code()}）"))
    } catch (e: IOException) {
        Result.failure(e)
    }

    /** 离线时详情页只能靠列表缓存里的那几个字段兜底。 */
    suspend fun cachedCore(id: String): CaseEntity? = dao.byId(id)

    /**
     * 退出登录时清空。缓存里是上一个账号的案件（含户号、业主、房号），
     * 换人登录后还留在库里就是越权数据泄露 —— 一号多账号的手机上尤其真实。
     */
    suspend fun clearCache() = dao.clear()
}

private fun Case.toEntity(stamp: Long) = CaseEntity(
    id = id.orEmpty(),
    acctNo = acctNo.orEmpty(),
    ownerName = ownerName.orEmpty(),
    room = room.orEmpty(),
    projectName = projectName.orEmpty(),
    dueCents = (dueCents ?: 0).toLong(),
    status = status?.value.orEmpty(),
    pool = pool?.value.orEmpty(),
    redacted = redacted ?: false,
    cachedAt = stamp,
)
