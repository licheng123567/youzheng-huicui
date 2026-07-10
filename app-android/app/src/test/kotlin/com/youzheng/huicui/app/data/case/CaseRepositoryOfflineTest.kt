package com.youzheng.huicui.app.data.case

import com.youzheng.huicui.app.api.models.Case
import com.youzheng.huicui.app.api.models.CaseDetail
import com.youzheng.huicui.app.api.models.CasePage
import com.youzheng.huicui.app.api.models.CaseStatusEnum
import com.youzheng.huicui.app.api.models.PageMeta
import com.youzheng.huicui.app.api.models.PoolEnum
import com.youzheng.huicui.app.data.db.CaseDao
import com.youzheng.huicui.app.data.db.CaseEntity
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 验收④：断网后仍能读到上次缓存的案件。
 *
 * 关键区分（这条线很容易走歪）：
 *   · `IOException`（连不上/超时）→ 读缓存，界面标「离线」。
 *   · HTTP 4xx/5xx           → **不读缓存**，如实报错。服务端明确说了「你没权限」，
 *     再拿旧数据糊弄用户，就是把权限收回这件事悄悄延迟了。
 */
class CaseRepositoryOfflineTest {

    private class FakeDao : CaseDao {
        val store = linkedMapOf<String, CaseEntity>()
        var clearCalls = 0
        override suspend fun all() = store.values.sortedByDescending { it.id }
        override suspend fun byId(id: String) = store[id]
        override suspend fun upsertAll(cases: List<CaseEntity>) { cases.forEach { store[it.id] = it } }
        override suspend fun clear() { clearCalls++; store.clear() }
    }

    private class FakeApi(
        var listResult: () -> Response<CasePage>,
    ) : CaseApiPort {
        var listCalls = 0
        /** 记录最后一次调用的参数，用来断言 holderId/page 真的传下去了 */
        var lastHolderId: String? = null
        var lastPage: Int = 0
        var lastQuery: String? = null

        override suspend fun listCases(q: String?, holderId: String?, page: Int, size: Int): Response<CasePage> {
            listCalls++
            lastQuery = q
            lastHolderId = holderId
            lastPage = page
            return listResult()
        }
        override suspend fun getCase(id: String): Response<CaseDetail> = throw IOException("offline")
    }

    private fun page(vararg ids: String, total: Int = ids.size) = Response.success(
        CasePage(
            items = ids.map {
                Case(
                    id = it, acctNo = "A-$it", ownerName = "业主$it", room = "$it-101",
                    projectName = "翠湖一期", dueCents = 280000,
                    status = CaseStatusEnum.IN_PROGRESS, pool = PoolEnum.PRIVATE, redacted = false,
                )
            },
            meta = PageMeta(page = 1, propertySize = 20, total = total),
        ),
    )

    private fun errorPage(code: Int) = Response.error<CasePage>(
        code,
        """{"code":"PERM_403","message":"无权访问"}""".toResponseBody("application/json".toMediaType()),
    )

    @Test
    fun `在线成功 落库并返回 fromCache=false`() = runTest {
        val dao = FakeDao()
        val repo = CaseRepository(FakeApi { page("7", "8") }, dao, now = { 1_700_000_000_000 })

        val r = repo.list().getOrThrow()

        assertFalse(r.fromCache)
        assertEquals(listOf("7", "8"), r.items.map { it.id })
        assertEquals(2, dao.store.size)
        assertEquals(1_700_000_000_000, dao.store.getValue("7").cachedAt)
    }

    @Test
    fun `断网 读缓存 fromCache=true 且带上缓存时间`() = runTest {
        val dao = FakeDao()
        val online = CaseRepository(FakeApi { page("7", "8") }, dao, now = { 1_700_000_000_000 })
        online.list().getOrThrow()   // 先灌一次缓存

        val offline = CaseRepository(FakeApi { throw SocketTimeoutException("timeout") }, dao)
        val r = offline.list().getOrThrow()

        assertTrue(r.fromCache)
        assertEquals(setOf("7", "8"), r.items.map { it.id }.toSet())
        assertEquals(1_700_000_000_000, r.cachedAt)
    }

    @Test
    fun `断网且缓存为空 如实失败 不返回空列表冒充成功`() = runTest {
        val repo = CaseRepository(FakeApi { throw IOException("no network") }, FakeDao())
        val r = repo.list()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is IOException)
    }

    @Test
    fun `HTTP 403 不读缓存 —— 服务端说了没权限就别拿旧数据糊弄`() = runTest {
        val dao = FakeDao()
        CaseRepository(FakeApi { page("7") }, dao).list().getOrThrow()   // 缓存里有数据
        assertEquals(1, dao.store.size)

        val repo = CaseRepository(FakeApi { errorPage(403) }, dao)
        val r = repo.list()

        assertTrue("403 必须失败，不得退回缓存", r.isFailure)
        assertTrue(r.exceptionOrNull() !is IOException)
    }

    @Test
    fun `带搜索词的结果不入缓存 否则离线列表会凭空变短`() = runTest {
        val dao = FakeDao()
        val repo = CaseRepository(FakeApi { page("7", "8") }, dao)
        repo.list().getOrThrow()
        assertEquals(2, dao.store.size)

        val filtered = CaseRepository(FakeApi { page("7") }, dao)
        filtered.list(query = "A-7").getOrThrow()

        assertEquals("搜索结果不该覆盖缓存", 2, dao.store.size)
    }

    @Test
    fun `第二页不入缓存`() = runTest {
        val dao = FakeDao()
        CaseRepository(FakeApi { page("7", "8") }, dao).list(page = 1).getOrThrow()
        CaseRepository(FakeApi { page("9") }, dao).list(page = 2).getOrThrow()
        assertEquals(2, dao.store.size)
    }

    @Test
    fun `刷新是整体替换 不会留下上一次的残行`() = runTest {
        val dao = FakeDao()
        CaseRepository(FakeApi { page("7", "8") }, dao).list().getOrThrow()
        CaseRepository(FakeApi { page("9") }, dao).list().getOrThrow()

        assertEquals(setOf("9"), dao.store.keys)
        assertTrue("replaceAll 应先 clear", dao.clearCalls >= 1)
    }

    @Test
    fun `退出登录清空缓存 —— 上一个账号的案件不能留给下一个人`() = runTest {
        val dao = FakeDao()
        val repo = CaseRepository(FakeApi { page("7") }, dao)
        repo.list().getOrThrow()
        assertEquals(1, dao.store.size)

        repo.clearCache()
        assertEquals(0, dao.store.size)
        assertTrue(repo.cachedCore("7") == null)
    }

    // ── holderId：修「我的案件」在撒谎的那个 bug ─────────────────────────────

    @Test
    fun `holderId 真的传到了 API 层 —— 催收员的「我持有的」靠它`() = runTest {
        val api = FakeApi { page("7") }
        CaseRepository(api, FakeDao()).list(holderId = "5").getOrThrow()
        assertEquals("5", api.lastHolderId)
    }

    @Test
    fun `holderId 为 null 或空串时不传 —— 协调员不按持有人过滤`() = runTest {
        val api = FakeApi { page("7") }
        CaseRepository(api, FakeDao()).list(holderId = null).getOrThrow()
        assertNull(api.lastHolderId)

        CaseRepository(api, FakeDao()).list(holderId = "  ").getOrThrow()
        assertNull("空白串应视同不过滤，不能把 '  ' 发给后端", api.lastHolderId)
    }

    @Test
    fun `带 holderId 的第一页仍写缓存 —— 那就是催收员的默认视图`() = runTest {
        val dao = FakeDao()
        CaseRepository(FakeApi { page("7", "8") }, dao).list(holderId = "5").getOrThrow()
        assertEquals("holderId 不是「过滤子集」，而是催收员的默认列表，应当缓存", 2, dao.store.size)
    }

    // ── 分页：第 2 页必须追加，不是覆盖 ─────────────────────────────────────

    @Test
    fun `total 大于本页时 hasMore 为真`() = runTest {
        val r = CaseRepository(FakeApi { page("1", "2", total = 45) }, FakeDao())
            .list(page = 1, size = 20).getOrThrow()
        assertTrue(r.hasMore)
        assertEquals(45, r.total)
    }

    @Test
    fun `最后一页 hasMore 为假`() = runTest {
        val r = CaseRepository(FakeApi { page("41", total = 41) }, FakeDao())
            .list(page = 3, size = 20).getOrThrow()   // 3*20=60 >= 41
        assertFalse(r.hasMore)
    }

    @Test
    fun `离线时 hasMore 恒为假 —— 缓存里只有第一页，不能假装还有更多`() = runTest {
        val dao = FakeDao()
        CaseRepository(FakeApi { page("7", "8", total = 100) }, dao).list().getOrThrow()

        val offline = CaseRepository(FakeApi { throw SocketTimeoutException("x") }, dao)
        val r = offline.list().getOrThrow()
        assertTrue(r.fromCache)
        assertFalse("离线还提示「加载更多」，点了必然失败", r.hasMore)
    }

    @Test
    fun `第二页不覆盖缓存 —— 否则前 20 条会被挤掉`() = runTest {
        val dao = FakeDao()
        CaseRepository(FakeApi { page("1", "2", total = 40) }, dao).list(page = 1).getOrThrow()
        CaseRepository(FakeApi { page("21", "22", total = 40) }, dao).list(page = 2).getOrThrow()
        assertEquals(setOf("1", "2"), dao.store.keys)
    }

    @Test
    fun `page 参数真的传到了 API 层`() = runTest {
        val api = FakeApi { page("x") }
        CaseRepository(api, FakeDao()).list(page = 3).getOrThrow()
        assertEquals(3, api.lastPage)
    }
}
