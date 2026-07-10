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
        override suspend fun listCases(q: String?, page: Int, size: Int): Response<CasePage> {
            listCalls++
            return listResult()
        }
        override suspend fun getCase(id: String): Response<CaseDetail> = throw IOException("offline")
    }

    private fun page(vararg ids: String) = Response.success(
        CasePage(
            items = ids.map {
                Case(
                    id = it, acctNo = "A-$it", ownerName = "业主$it", room = "$it-101",
                    projectName = "翠湖一期", dueCents = 280000,
                    status = CaseStatusEnum.IN_PROGRESS, pool = PoolEnum.PRIVATE, redacted = false,
                )
            },
            meta = PageMeta(page = 1, propertySize = 20, total = ids.size),
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
}
