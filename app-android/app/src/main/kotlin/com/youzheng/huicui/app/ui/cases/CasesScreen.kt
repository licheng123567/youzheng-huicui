package com.youzheng.huicui.app.ui.cases

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.data.case.formatCents
import com.youzheng.huicui.app.data.session.Permissions
import com.youzheng.huicui.app.data.db.CaseEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 案件屏。两个角色看到的东西不一样，而且**由权限决定，不由角色名决定**：
 *
 * | | 列表 | 公海 Tab |
 * |---|---|---|
 * | 催收员（有 `case.claim`） | 「我持有的」（`holderId=me`） | 有 |
 * | 物业协调员（无 `case.claim`） | 「我协调的案件」（后端按项目/批次协调关系裁剪） | **无** |
 *
 * 催收员必须传 `holderId`：不传的话 `GET /cases` 的 range scope 返回的是本服务商全部案件，
 * 「我的」二字就是谎言（曾经确实如此）。
 */
@Composable
fun CasesScreen(modifier: Modifier = Modifier, onOpenCase: (String) -> Unit) {
    val session = ServiceLocator.session
    val canClaim = session.has(Permissions.CASE_CLAIM)
    // 抢单权 ⇒ 私海持有制 ⇒ 列表就是「我持有的」。没有抢单权的（协调员）没有持有概念。
    val holderId = if (canClaim) session.accountId() else null
    val listLabel = if (canClaim) "我持有的" else "我协调的案件"

    var page by remember { mutableStateOf(0) }

    Column(modifier) {
        if (canClaim) {
            TabRow(selectedTabIndex = page) {
                Tab(selected = page == 0, onClick = { page = 0 }, text = { Text(listLabel) })
                Tab(selected = page == 1, onClick = { page = 1 }, text = { Text("公海") })
            }
        }
        when {
            !canClaim -> MyCasesPage(holderId, onOpenCase)     // 协调员：单列表，无 Tab 栏
            page == 0 -> MyCasesPage(holderId, onOpenCase)
            else -> SeaPage(onOpenCase)
        }
    }
}

@Composable
private fun MyCasesPage(holderId: String?, onOpenCase: (String) -> Unit) {
    val vm = rememberMyCasesController(holderId)
    val state = vm.state
    val listState = rememberLazyListState()

    // 滚到距底部 3 条时预取下一页。放在 LaunchedEffect 里而不是 item 的 onDispose，
    // 是为了让「列表本身就不足一屏」的情况也能触发（否则永远没有滚动事件）。
    LaunchedEffect(listState, state.items.size, state.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                if (last != null && last >= state.items.size - 3) vm.loadMore()
            }
    }

    Column {
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::onQuery,
            label = { Text("按户号 / 业主 / 房号搜索") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )

        if (state.offline) {
            OfflineBanner(state.cachedAt)
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }

        if (state.items.isEmpty() && !state.loading && state.error == null) {
            Text(
                "没有案件",
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.items, key = { it.id }) { c -> CaseRow(c, onOpenCase) }

            if (state.loadingMore) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.height(20.dp))
                    }
                }
            } else if (state.items.isNotEmpty() && !state.hasMore && !state.offline) {
                item {
                    Text(
                        "共 ${state.total} 件，已全部加载",
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner(cachedAt: Long?) {
    val stamp = cachedAt?.let {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("离线：正在显示缓存的案件", style = MaterialTheme.typography.bodyMedium)
            Text(
                (stamp?.let { "数据截至 $it" } ?: "数据时间未知") + " · 只缓存了最近一页，联网后可加载更多",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun CaseRow(c: CaseEntity, onOpenCase: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpenCase(c.id) }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(c.acctNo, style = MaterialTheme.typography.titleSmall)
                Text("¥${formatCents(c.dueCents.toInt())}", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "${c.ownerName} · ${c.room} · ${c.projectName}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                statusLabel(c.status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

fun statusLabel(s: String): String = when (s) {
    "PENDING_DISPATCH" -> "待派单"
    "PROVIDER_SEA" -> "服务商公海"
    "IN_PROGRESS" -> "催收中"
    "SETTLED" -> "已结清"
    "WITHDRAWN" -> "已撤回"
    "CLOSED" -> "已结案"
    else -> s
}
