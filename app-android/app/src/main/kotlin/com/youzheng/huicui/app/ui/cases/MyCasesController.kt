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
    val offline: Boolean = false,
    val cachedAt: Long? = null,
    val error: String? = null,
)

class MyCasesController(private val scope: CoroutineScope) {
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

    fun load() {
        scope.launch {
            state = state.copy(loading = true, error = null)
            ServiceLocator.caseRepository.list(query = query).fold(
                onSuccess = {
                    state = MyCasesState(
                        items = it.items,
                        loading = false,
                        offline = it.fromCache,
                        cachedAt = it.cachedAt,
                    )
                },
                onFailure = {
                    state = state.copy(loading = false, error = it.message ?: "加载失败")
                },
            )
        }
    }
}

@Composable
fun rememberMyCasesController(): MyCasesController {
    val scope = rememberCoroutineScope()
    val c = remember(scope) { MyCasesController(scope) }
    LaunchedEffect(c) { c.load() }
    return c
}
