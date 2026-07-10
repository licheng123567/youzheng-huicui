package com.youzheng.huicui.app.ui.cases

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.data.case.formatCents
import com.youzheng.huicui.app.data.db.CaseEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 案件 Tab 下的两页：我的案件（私海，可离线读）/ 公海（可抢单）。 */
@Composable
fun CasesScreen(modifier: Modifier = Modifier, onOpenCase: (String) -> Unit) {
    var page by remember { mutableStateOf(0) }

    Column(modifier) {
        TabRow(selectedTabIndex = page) {
            Tab(selected = page == 0, onClick = { page = 0 }, text = { Text("我的案件") })
            Tab(selected = page == 1, onClick = { page = 1 }, text = { Text("公海") })
        }
        when (page) {
            0 -> MyCasesPage(onOpenCase)
            else -> SeaPage(onOpenCase)
        }
    }
}

@Composable
private fun MyCasesPage(onOpenCase: (String) -> Unit) {
    val vm = rememberMyCasesController()
    val state = vm.state

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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.items, key = { it.id }) { c -> CaseRow(c, onOpenCase) }
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
                stamp?.let { "数据截至 $it" } ?: "数据时间未知",
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
