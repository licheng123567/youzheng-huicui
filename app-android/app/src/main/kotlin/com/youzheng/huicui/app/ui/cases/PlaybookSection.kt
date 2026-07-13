package com.youzheng.huicui.app.ui.cases

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.api.models.Playbook
import com.youzheng.huicui.app.api.models.PreCallStrategy
import com.youzheng.huicui.app.api.models.StrategyCard
import com.youzheng.huicui.app.ui.common.Pill
import com.youzheng.huicui.app.ui.theme.huicui

/**
 * 通话前话术辅助：**作战手册**（项目级·BR-M5-05）+ **通话前策略**（AI 生成·BR-M5-04）。
 *
 * 不需要任何新接口 —— 这两块本来就随 `GET /cases/{id}` 一起下发（CaseDetail.playbook /
 * CaseDetail.preCallStrategy），App 此前把它们**拿到手却扔了**。契约里手册是 range scope、
 * 无权限点，服务商/催收员只见已发布版；协调员同样看得到。所以两个角色都渲染，不做门控。
 *
 * 默认折叠：拨号前扫一眼开场白和红线就够了，全文很长，不该把联系人和录音卡挤到屏幕外。
 * 「红线」单独标红置底 —— 说错话是要吃质检罚单的，不能藏在正文里。
 */
@Composable
fun PlaybookSection(caseId: String, projectId: String?, batchId: String?, strategy: PreCallStrategy?) {
    // 后端 M2 读阶段把 CaseDetail.playbook 恒置 null，所以手册必须另外去项目/批次取（见 PlaybookRepository）。
    var playbook by remember(caseId) { mutableStateOf<Playbook?>(null) }
    LaunchedEffect(caseId, projectId, batchId) {
        playbook = ServiceLocator.playbookRepository.forCase(projectId, batchId)
    }

    if (playbook?.content.isNullOrBlank() && strategy == null) return

    var expanded by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "作战手册 · 通话前话术",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // 版本号后端已经自带 v 前缀（实测「v1.0-batch」），别再拼一个 → 会显示成 vv1.0-batch
                    playbook?.version?.takeIf { it.isNotBlank() }?.let {
                        Pill(it, MaterialTheme.colorScheme.primary)
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }

            // 折叠态就把最要紧的一句露出来：开场白。拨号前瞄一眼就能开口。
            if (!expanded) {
                val peek = strategy?.open?.takeIf { it.isNotBlank() }
                    ?: playbook?.content?.lineSequence()?.firstOrNull { it.isNotBlank() }
                peek?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }

            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    strategy?.open?.takeIf { it.isNotBlank() }?.let {
                        Labeled("开场白", it)
                    }

                    strategy?.points.orEmpty().takeIf { it.isNotEmpty() }?.let { points ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("要点", style = MaterialTheme.typography.labelLarge)
                            points.forEach {
                                Text("· $it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    strategy?.objections.orEmpty().takeIf { it.isNotEmpty() }?.let { cards ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("异议应对", style = MaterialTheme.typography.labelLarge)
                            cards.forEach { StrategyCardRow(it) }
                        }
                    }

                    // 红线：说了要吃质检罚单的，单独标红
                    strategy?.redlines.orEmpty().takeIf { it.isNotEmpty() }?.let { lines ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("红线（不可说）", style = MaterialTheme.typography.labelLarge, color = huicui.danger)
                            lines.forEach {
                                Text("· $it", style = MaterialTheme.typography.bodySmall, color = huicui.danger)
                            }
                        }
                    }

                    playbook?.content?.takeIf { it.isNotBlank() }?.let {
                        HorizontalDivider()
                        Text("手册全文", style = MaterialTheme.typography.labelLarge)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** 建议卡/异议卡：标题 + 正文 + 触发条件。采纳动作（联动弹窗）留在网页端，手机端先只读。 */
@Composable
fun StrategyCardRow(card: StrategyCard) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.title.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                card.confidence?.takeIf { it.isNotBlank() }?.let { Pill(it, huicui.teal) }
            }
            card.body?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            card.trigger?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "触发：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun Labeled(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
