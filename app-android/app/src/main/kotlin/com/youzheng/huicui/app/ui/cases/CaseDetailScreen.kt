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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.api.models.CaseDetail
import com.youzheng.huicui.app.data.case.CaseActions
import com.youzheng.huicui.app.data.case.formatCents
import com.youzheng.huicui.app.data.db.CaseEntity
import com.youzheng.huicui.app.data.session.Permissions
import kotlinx.coroutines.launch
import com.youzheng.huicui.app.ui.dial.startCall

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
    val scope = rememberCoroutineScope()
    val permissions = ServiceLocator.session.permissions()
    val c = d.case

    // 后端按当前主体权限 + 案件状态机算出来的可用动作。已结案/已撤回的案件是空数组。
    val actions = d.availableActions.orEmpty()

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

        // 通话**前**：作战手册 + AI 通话前策略（开场白/要点/异议/红线）。
        // 放在联系人上方——拨号之前该看的东西，不能排在拨号按钮后面。
        // 手册是 range scope、无权限点，催收员与协调员都看得到（服务商侧只见已发布版，后端裁）。
        PlaybookSection(
            caseId = c?.id.orEmpty(),
            projectId = c?.projectId,
            batchId = c?.batchId,
            strategy = d.preCallStrategy,
        )

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
                    TextButton(
                        onClick = {
                            // 先落库会话再拨号：拨出去之后 App 随时可能被系统杀掉
                            scope.launch { startCall(context, c?.id.orEmpty(), contact.phone!!) }
                        },
                    ) { Text("拨号") }
                }
            }
        }

        // 通话结束回到 App，第一眼要看到「录音上来没有」，紧接着是这通电话的 AI 复盘与结果标记。
        //
        // 相比拨号按钮的门控（CaseActions.canCall），这里只去掉「存在可拨的联系人」这一条 ——
        // 那是**拨号**的条件，不是**回看录音**的条件：号码被标记无效之后，之前那通电话的录音和复盘照样得看得到。
        // 但 `call in availableActions` 这道状态机闸**必须留着**：已结案/已撤回的案件 actions 是空数组，
        // 去掉它就会在一个只读归档页上照样打 /recordings/latest，然后画一行 403 红字。
        if ("call" in actions && Permissions.CASE_CALL in permissions) {
            HorizontalDivider()
            RecordingStatusCard(
                caseId = c?.id.orEmpty(),
                markCodes = d.markCodes.orEmpty(),
                canMark = Permissions.CASE_FOLLOW in permissions,
                // 「有效跟进重置释放倒计时」只对**持有人**成立（BR-M4-03/12），别对协调员乱许诺
                isHolder = c?.holderId != null && c.holderId == ServiceLocator.session.accountId(),
            )
        }

        // 拍照留痕：两个角色都要，但**能做的事不一样**，UI 直接把差异画出来而不是等 403。
        //
        //   · 物业协调员（有 evidence.create）→ 完整「送达存证」：选送达类型（律师函/催收单/诉讼文书）
        //     → 拍照 → 提交，可勾上链存证。上门送达文书本就是协调员的活（LEGAL_DELIVERY 待办只发给 PC）。
        //   · 催收员（只有 case.follow）→ 「现场拍照留痕」：没有送达类型、没有存证勾。
        //
        // 曾经这里只门控 case.follow，于是催收员也看到一整套送达类型，末尾的存证勾却因没权限而消失 ——
        // 半套 UI 配半套权限，这就是「交互逻辑很怪」的来源。但反过来把整节收窄到 evidence.create 也不对：
        // 那会把催收员上门拍照留痕的唯一入口一并删掉，日后起争议他拿不出到场证据。
        if ("follow" in actions && Permissions.CASE_FOLLOW in permissions) {
            HorizontalDivider()
            DeliveryEvidenceSection(
                caseId = c?.id.orEmpty(),
                isCoordinator = Permissions.EVIDENCE_CREATE in permissions,
                canEvidence = "evidence" in actions,
            )
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
