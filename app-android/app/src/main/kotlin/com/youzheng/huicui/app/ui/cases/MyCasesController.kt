package com.youzheng.huicui.app.ui.cases

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.data.db.CaseEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class MyCasesState(
    val items: List<CaseEntity> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val offline: Boolean = false,
    val cachedAt: Long? = null,
    val error: String? = null,
    val page: Int = 1,
    val total: Int = 0,
    val hasMore: Boolean = false,
)

/**
 * 案件列表控制器。
 *
 * `holderId` 由调用方决定，而不是这里猜：
 *   · 催收员（CO）传自己的 accountId → 「我持有的」
 *   · 物业协调员（PC）传 null       → 后端按项目/批次协调关系裁剪出「我协调的案件」
 *
 * 不传 holderId 的催收员会拿到**本服务商全部案件**（含他人持有、待派单）——
 * 那正是这一版之前「我的案件」在撒谎的原因。
 */
class MyCasesController(
    private val scope: CoroutineScope,
    private val holderId: String?,
) {
    var state by mutableStateOf(MyCasesState())
        private set
    var query by mutableStateOf("")
        private set

    private var searchJob: Job? = null

    fun onQuery(q: String) {
        query = q
        // 每敲一个字就打一次后端会把列表刷成一片抖动，且离线时反复穿透到缓存。300ms 防抖。
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(300)
            load()
        }
    }

    /** 首屏 / 搜索 / 下拉刷新：回到第 1 页，整体替换。 */
    fun load() {
        scope.launch {
            state = state.copy(loading = true, error = null)
            fetch(page = 1).fold(
                onSuccess = { r ->
                    state = MyCasesState(
                        items = r.items,
                        loading = false,
                        offline = r.fromCache,
                        cachedAt = r.cachedAt,
                        page = 1,
                        total = r.total,
                        hasMore = r.hasMore,
                    )
                },
                onFailure = { state = state.copy(loading = false, error = it.message ?: "加载失败") },
            )
        }
    }

    /**
     * 滚到底部时取下一页，**追加**而不是覆盖。
     * 重入保护：`loadingMore` 期间再触发直接忽略，否则快速滚动会把同一页取两遍、列表里出现重复行。
     */
    fun loadMore() {
        val s = state
        if (s.loadingMore || s.loading || !s.hasMore) return
        scope.launch {
            state = state.copy(loadingMore = true)
            fetch(page = s.page + 1).fold(
                onSuccess = { r ->
                    state = state.copy(
                        items = state.items + r.items,
                        loadingMore = false,
                        page = r.page,
                        total = r.total,
                        hasMore = r.hasMore,
                    )
                },
                onFailure = { state = state.copy(loadingMore = false, error = it.message ?: "加载更多失败") },
            )
        }
    }

    private suspend fun fetch(page: Int) =
        ServiceLocator.caseRepository.list(query = query, holderId = holderId, page = page)
}

@Composable
fun rememberMyCasesController(holderId: String?): MyCasesController {
    val scope = rememberCoroutineScope()
    val c = remember(scope, holderId) { MyCasesController(scope, holderId) }
    LaunchedEffect(c) { c.load() }
    return c
}
