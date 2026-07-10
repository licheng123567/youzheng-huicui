package com.youzheng.huicui.app.ui.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.api.models.WorkbenchData
import com.youzheng.huicui.app.api.models.WorkbenchTodo
import com.youzheng.huicui.app.ui.common.LoadState
import com.youzheng.huicui.app.ui.common.LoadStateBox
import kotlinx.coroutines.launch

/**
 * 催收员驾驶舱（`layout=cockpit`）。KPI 一栏、待办一列，待办点进案件。
 * KPI 的 `filterKey` 非空时按该 category 过滤待办 —— 这是契约定义的交互，不是我们发明的。
 */
@Composable
fun WorkbenchScreen(modifier: Modifier = Modifier, onOpenCase: (String) -> Unit) {
    var state by remember { mutableStateOf<LoadState<WorkbenchData>>(LoadState.Loading) }
    var filter by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        state = LoadState.Loading
        state = ServiceLocator.workbenchRepository.load()
            .fold({ LoadState.Data(it) }, { LoadState.Error(it.message ?: "加载失败") })
    }

    LaunchedEffect(Unit) { load() }

    LoadStateBox(state, modifier, onRetry = { scope.launch { load() } }) { data ->
        val todos = data.todos.orEmpty().filter { filter == null || it.category.value == filter }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // KPI 每行三个；实测 CO 有 6 个 KPI，只有部分带 filterKey（无 filterKey 的不可点）
            items(data.kpis.orEmpty().chunked(3)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { kpi ->
                        Card(
                            modifier = Modifier.weight(1f).clickable(enabled = kpi.filterKey != null) {
                                filter = if (filter == kpi.filterKey) null else kpi.filterKey
                            },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${kpi.value}", style = MaterialTheme.typography.headlineSmall)
                                Text(kpi.label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    // 补齐末行，避免剩下的卡片被拉成整行宽（用 Spacer 而非空 Card，否则会画出一张空卡）
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            item {
                Text(
                    if (filter == null) "待办" else "待办 · 已按 $filter 过滤（再点一次取消）",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (todos.isEmpty()) {
                item {
                    Text(
                        "暂无待办",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            items(todos) { todo -> TodoRow(todo, onOpenCase) }
        }
    }
}

@Composable
private fun TodoRow(todo: WorkbenchTodo, onOpenCase: (String) -> Unit) {
    val caseId = todo.caseId
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 没有 caseId 的待办（如工单回执）点了也没地方去，索性不给点击反馈
            .clickable(enabled = caseId != null) { caseId?.let(onOpenCase) },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(todo.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${todo.category.value} · ${urgencyLabel(todo.urgency.value)}" +
                    (todo.deadline?.let { " · 截至 $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 契约枚举是 HIGH/MED/LOW —— 不是 MEDIUM。 */
private fun urgencyLabel(u: String): String = when (u) {
    "HIGH" -> "紧急"
    "MED" -> "一般"
    "LOW" -> "不急"
    else -> u
}
