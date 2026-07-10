package com.youzheng.huicui.app.ui.cases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.api.models.CaseDetail
import com.youzheng.huicui.app.data.case.CaseActions
import com.youzheng.huicui.app.data.case.formatCents
import com.youzheng.huicui.app.data.db.CaseEntity
import com.youzheng.huicui.app.ui.dial.dial

/**
 * 案件详情（只读 + 拨号）。跟进、承诺、缴费链接、释放等写操作留到 M-A2 与录音管线一起做，
 * 这里不放假按钮。
 *
 * 离线时详情拿不到（含联系人电话，不入缓存），退回展示列表缓存里的基本信息，并明说「离线」。
 */
@Composable
fun CaseDetailScreen(caseId: String, onBack: () -> Unit) {
    var detail by remember { mutableStateOf<CaseDetail?>(null) }
    var offlineCore by remember { mutableStateOf<CaseEntity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(caseId) {
        loading = true
        error = null
        ServiceLocator.caseRepository.detail(caseId).fold(
            onSuccess = { detail = it; offlineCore = null },
            onFailure = { e ->
                error = e.message ?: "加载失败"
                offlineCore = ServiceLocator.caseRepository.cachedCore(caseId)
            },
        )
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回") }

        when {
            loading -> CircularProgressIndicator()

            detail != null -> DetailBody(detail!!)

            offlineCore != null -> {
                val c = offlineCore!!
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("离线：仅显示缓存的基本信息", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "联系方式与跟进记录需要联网查看",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Text(c.acctNo, style = MaterialTheme.typography.headlineSmall)
                Text("${c.ownerName} · ${c.room} · ${c.projectName}")
                Text("应收 ¥${formatCents(c.dueCents.toInt())}")
                Text(statusLabel(c.status))
            }

            else -> Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DetailBody(d: CaseDetail) {
    val context = LocalContext.current
    val permissions = ServiceLocator.session.permissions()
    val c = d.case

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(c?.acctNo.orEmpty(), style = MaterialTheme.typography.headlineSmall)
        Text("${c?.ownerName.orEmpty()} · ${c?.room.orEmpty()} · ${c?.projectName.orEmpty()}")
        Text("应收 ¥${formatCents(c?.dueCents ?: 0)}", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, enabled = false, label = { Text(statusLabel(c?.status?.value.orEmpty())) })
            if (c?.redacted == true) {
                AssistChip(onClick = {}, enabled = false, label = { Text("已结案·信息脱敏") })
            }
        }

        HorizontalDivider()
        Text("联系人", style = MaterialTheme.typography.titleMedium)
        val contacts = d.contacts.orEmpty()
        if (contacts.isEmpty()) {
            Text(
                "无可见联系人（未持有该案件时后端不下发联系方式）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        contacts.forEach { contact ->
            val dialable = CaseActions.isDialable(contact.phone)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(contact.phone.orEmpty())
                    Text(
                        (contact.label ?: "") + if (contact.isPrimary == true) " · 主号" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (CaseActions.canCall(d, permissions) && dialable) {
                    TextButton(onClick = { dial(context, contact.phone!!) }) { Text("拨号") }
                }
            }
        }

        HorizontalDivider()
        Text("跟进记录", style = MaterialTheme.typography.titleMedium)
        val timeline = d.timeline.orEmpty()
        if (timeline.isEmpty()) {
            Text("暂无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        timeline.take(20).forEach { a ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(a.content.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    a.createdAt?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
