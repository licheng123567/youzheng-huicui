package com.youzheng.huicui.app.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.api.models.WorkbenchData
import com.youzheng.huicui.app.api.models.WorkbenchKpi
import com.youzheng.huicui.app.api.models.WorkbenchTodo
import com.youzheng.huicui.app.ui.common.LoadState
import com.youzheng.huicui.app.ui.common.LoadStateBox
import com.youzheng.huicui.app.ui.common.Pill
import com.youzheng.huicui.app.ui.common.formatWan
import com.youzheng.huicui.app.ui.common.rememberCaseSummary
import com.youzheng.huicui.app.ui.common.todoReason
import com.youzheng.huicui.app.ui.common.urgencyColor
import com.youzheng.huicui.app.ui.common.urgencyLabel
import com.youzheng.huicui.app.ui.theme.huicui
import kotlinx.coroutines.launch

/**
 * 工作台。`WorkbenchData.layout` 是契约定义的两种形态：
 *   · `cockpit`   一线驾驶舱（催收员 CO / 物业协调员 PC）：KPI 一栏 + 待办一列，待办点进案件。
 *   · `dashboard` 管理看板（PL/VL/SA/SE）：只有 KPI，没有待办队列。
 *
 * 入口门禁只放 CO/PC 进来，所以实际恒为 cockpit。但仍显式分支 ——
 * 把管理者的看板硬套成驾驶舱、再渲染一个永远空的「待办」标题，是种沉默的谎言。
 *
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
        val isCockpit = data.layout == WorkbenchData.Layout.cockpit
        val todos = data.todos.orEmpty().filter { filter == null || it.category.value == filter }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // KPI 每行三个；实测 CO 有 6 个 KPI，只有部分带 filterKey（无 filterKey 的不可点）
            items(data.kpis.orEmpty().chunked(3)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { kpi ->
                        KpiCard(
                            kpi = kpi,
                            active = filter != null && filter == kpi.filterKey,
                            modifier = Modifier.weight(1f),
                            onClick = { filter = if (filter == kpi.filterKey) null else kpi.filterKey },
                        )
                    }
                    // 补齐末行，避免剩下的卡片被拉成整行宽（用 Spacer 而非空 Card，否则会画出一张空卡）
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            if (!isCockpit) {
                // 管理看板：后端本就不给待办，别渲染一个永远空的队列
                item {
                    Text(
                        "本角色没有待办队列，日常工作请在网页端进行。",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                return@LazyColumn
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("待办", style = MaterialTheme.typography.titleMedium)
                    filter?.let {
                        // 过滤态用中文说清「筛的是什么」，并给出取消路径；此前直接把 PROMISE_DUE 拼进标题
                        Pill("已筛：${todoReason(it)} ✕", MaterialTheme.colorScheme.primary, Modifier.clickable { filter = null })
                    }
                }
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

/**
 * KPI 卡。两件事此前是错的：
 *   · **本月回款后端给的是「分」**（repay_line.amount_cents），界面拿 Int 直接打 → 12,345.67 元显示成「1234567」。
 *     按用户要求统一换算成**万元**。判定靠 label 含「回款」——契约没有 unit 字段可认。
 *   · 所有 KPI 一个颜色，扫一眼分不出轻重。按语义上色（回款=绿、临近释放=红…）。
 */
@Composable
private fun KpiCard(kpi: WorkbenchKpi, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val money = kpi.label.contains("回款")
    val accent = kpiColor(kpi.label)
    Card(
        modifier = modifier.clickable(enabled = kpi.filterKey != null, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (active) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (money) formatWan(kpi.value) else "${kpi.value}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                if (money) {
                    Text(
                        " 万",
                        style = MaterialTheme.typography.bodySmall,
                        color = accent,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            Text(
                if (money) "${kpi.label}（万元）" else kpi.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun kpiColor(label: String): Color = when {
    label.contains("回款") -> huicui.success
    label.contains("释放") || label.contains("退回") || label.contains("超时") -> huicui.danger
    label.contains("承诺") || label.contains("待跟进") -> huicui.warning
    label.contains("通话") -> huicui.teal
    else -> MaterialTheme.colorScheme.primary
}

/** 待办类别 → 图标（material-icons-core 的可用集，别引不存在的图标名）。 */
private fun todoIcon(category: String): ImageVector = when (category) {
    "PROMISE_DUE" -> Icons.Filled.DateRange
    "RELEASE_WARN", "T2_RETURN_WARN", "T1_DISPATCH_WARN" -> Icons.Filled.Warning
    "TICKET_RECEIPT" -> Icons.Filled.List
    else -> Icons.Filled.Phone
}

@Composable
private fun TodoRow(todo: WorkbenchTodo, onOpenCase: (String) -> Unit) {
    val caseId = todo.caseId
    val category = todo.category.value
    val urgency = todo.urgency.value
    val accent = urgencyColor(urgency)
    // 待办只带 title（「承诺到期：张三」）和 caseId，不带小区/房号 ——
    // 但催收员出门要按小区跑，房号是他找门牌的唯一依据。按 caseId 补出来。
    val summary = caseId?.let { rememberCaseSummary(it) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 没有 caseId 的待办（如工单回执）点了也没地方去，索性不给点击反馈
            .clickable(enabled = caseId != null) { caseId?.let(onOpenCase) },
    ) {
        // IntrinsicSize.Min：让左侧色条能 fillMaxHeight 跟着内容长。
        // 写死高度的话，多一行小区地址就会露出一截白，色条短一块。
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧竖色条：紧急度一眼可辨，不用读字
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))

            Box(
                Modifier
                    .padding(start = 12.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(todoIcon(category), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }

            Column(
                Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(todo.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)

                // 小区 + 房号（拿不到就不占位，别显示「加载中」这种噪声）
                summary?.let {
                    Text(
                        listOf(it.projectName, it.room).filter { s -> s.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 「原因」——用户明确要求：待办要说清为什么待办，而不是甩一个 PROMISE_DUE
                    Pill(todoReason(category), accent)
                    Pill(urgencyLabel(urgency), huicui.info)
                    todo.deadline?.let {
                        Text(
                            "截至 ${it.toLocalDate()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (caseId != null) {
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}
