package com.youzheng.huicui.app.ui.cases

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.api.apis.DispatchApi.PoolListSea
import com.youzheng.huicui.app.data.case.SeaCardState
import com.youzheng.huicui.app.data.case.formatCents
import com.youzheng.huicui.app.ui.common.LoadState
import com.youzheng.huicui.app.ui.common.LoadStateBox
import kotlinx.coroutines.launch

/**
 * 公海（服务商待接单）。**列表里不出现任何联系方式** —— `contactMasked` 为真时后端连
 * `ownerName` 都已换成 `***`，客户端只如实呈现，不做任何反向还原。
 */
@Composable
fun SeaPage(onOpenCase: (String) -> Unit) {
    var state by remember { mutableStateOf<LoadState<List<SeaCardState>>>(LoadState.Loading) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val permissions = ServiceLocator.session.permissions()

    suspend fun load() {
        state = LoadState.Loading
        state = ServiceLocator.seaRepository.list(PoolListSea.provider)
            .fold({ LoadState.Data(it) }, { LoadState.Error(it.message ?: "加载失败") })
    }

    LaunchedEffect(Unit) { load() }

    Column {
        toast?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
        LoadStateBox(state, onRetry = { scope.launch { load() } }) { items ->
            if (items.isEmpty()) {
                Text(
                    "公海暂无待接案件",
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.outline,
                )
                return@LoadStateBox
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { c ->
                    SeaCard(
                        card = c,
                        permissions = permissions,
                        onOpen = { onOpenCase(c.id) },
                        onClaim = {
                            scope.launch {
                                ServiceLocator.seaRepository.claim(c.id).fold(
                                    onSuccess = { toast = null; load() },
                                    onFailure = { toast = it.message },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SeaCard(
    card: SeaCardState,
    permissions: Set<String>,
    onOpen: () -> Unit,
    onClaim: () -> Unit,
) {
    // 抢单按钮的可见性由 permissions[] 决定（缺 case.claim 就整个不渲染），
    // 可用性由竞争态与持仓余量决定。二者不能混为一谈：没权限的人连按钮都不该看到。
    val canSeeClaim = com.youzheng.huicui.app.data.session.Permissions.CASE_CLAIM in permissions
    val capacityFull = card.capacityHint != null && card.capacityHint <= 0
    val claimed = card.competitionState == "CLAIMED"

    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(card.acctNo, style = MaterialTheme.typography.titleSmall)
                Text("¥${formatCents(card.dueCents)}", style = MaterialTheme.typography.titleSmall)
            }
            Text("${card.ownerName} · ${card.room} · ${card.projectName}", style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (card.contactMasked) {
                    AssistChip(onClick = {}, enabled = false, label = { Text("联系方式已脱敏") })
                }
                if (card.viewerCount > 0) {
                    AssistChip(onClick = {}, enabled = false, label = { Text("${card.viewerCount} 人在看") })
                }
            }

            if (canSeeClaim) {
                Button(
                    onClick = onClaim,
                    enabled = !claimed && !capacityFull,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            claimed -> "已被抢走"
                            capacityFull -> "持仓已满"
                            else -> "抢单"
                        },
                    )
                }
            }
        }
    }
}
