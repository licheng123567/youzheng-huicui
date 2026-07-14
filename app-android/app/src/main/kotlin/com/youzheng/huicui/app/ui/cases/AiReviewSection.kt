package com.youzheng.huicui.app.ui.cases

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.youzheng.huicui.app.ServiceLocator
import com.youzheng.huicui.app.api.models.AiReview
import com.youzheng.huicui.app.api.models.CaseDetailMarkCodesInner
import com.youzheng.huicui.app.api.models.RiskLevelEnum
import com.youzheng.huicui.app.ui.common.Pill
import com.youzheng.huicui.app.ui.theme.huicui
import kotlinx.coroutines.launch

/**
 * 通话后处理区：**AI 复盘**（转写对话 + 质检风险 + 下一步建议）与 **通话结果标记**。
 *
 * 两个角色都能看：`GET /recordings/{id}/ai-review` 是 case-actor scope、不要额外权限点，
 * 催收员和物业协调员经手的案件都读得到。标记要 `case.follow`（两者也都有）。
 *
 * 复盘要等 ASR 转写跑完（status=READY）才有内容；没跑完/平台没开 AI 时后端返 404，
 * 仓库层已经把它翻译成 null —— 那是「还没有」，不是错误，别渲染成红字。
 *
 * 标记码**不硬编码**：取值来自 CFG-MARK-CODES，后端随 CaseDetail.markCodes 下发。
 * 标了「有效跟进」的码，服务端会重置 T_collector —— 这一下能把一个临近自动释放的案件救回来，
 * 所以标记按钮必须在手机上够得着，不能只有网页端有。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiReviewSection(
    recordingId: String,
    recordingStatus: String?,
    markCodes: List<CaseDetailMarkCodesInner>,
    canMark: Boolean,
    isHolder: Boolean,
) {
    val scope = rememberCoroutineScope()
    var review by remember(recordingId) { mutableStateOf<AiReview?>(null) }
    var loading by remember(recordingId) { mutableStateOf(true) }
    var marking by remember { mutableStateOf(false) }
    var marked by remember(recordingId) { mutableStateOf<String?>(null) }

    // 成功提示和错误分开存。合成一个 message 会出两种事故：
    // 复盘拉取失败（500）时，若当前用户没有标记权限，这条错误根本不渲染 —— 被彻底吞掉；
    // 若有标记权限，它又会以**绿色成功色**出现在标记芯片旁边，读起来像「标记成功了」。
    var markMessage by remember { mutableStateOf<String?>(null) }
    var error by remember(recordingId) { mutableStateOf<String?>(null) }

    LaunchedEffect(recordingId, recordingStatus) {
        loading = true
        error = null
        ServiceLocator.recordingRepository.aiReview(recordingId)
            .onSuccess { review = it }
            .onFailure { error = it.message }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // ── 通话结果标记：录音一上来就能标，不必等转写 ──
        if (canMark) {
            val enabled = markCodes.filter { it.enabled != false }
            if (enabled.isNotEmpty()) {
                Text("这通电话的结果", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    enabled.forEach { code ->
                        val c = code.code.orEmpty()
                        // FilterChip 而不是 AssistChip：标记是个「选中态」，标完要留着让人看见标了哪个
                        FilterChip(
                            selected = marked == c,
                            enabled = !marking,
                            onClick = {
                                marking = true
                                markMessage = null
                                error = null
                                scope.launch {
                                    ServiceLocator.recordingRepository.markCallResult(recordingId, c)
                                        .onSuccess {
                                            marked = c
                                            // 「有效跟进重置释放倒计时」**只对案件持有人成立**（BR-M4-03/12：
                                            // 服务端只在操作者就是 holder 时才重置 T_collector）。
                                            // 协调员等非持有人也能标记，但后端不会重置 —— 对他说「倒计时已重置」
                                            // 就是给了一个后端根本没做的保证，他会以为案件安全了，然后案件被自动释放。
                                            markMessage = if (code.effectiveFollowUp == true && isHolder) {
                                                "已标记「${code.label ?: c}」·计为有效跟进，释放倒计时已重置"
                                            } else {
                                                "已标记「${code.label ?: c}」"
                                            }
                                        }
                                        .onFailure { error = it.message ?: "标记失败" }
                                    marking = false
                                }
                            },
                            label = { Text(code.label ?: c) },
                        )
                    }
                }
                markMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = huicui.success)
                }
            }
        }

        // ── AI 复盘 ──
        HorizontalDivider()
        Text("AI 复盘", style = MaterialTheme.typography.titleSmall)

        // 错误一律红字、且**独立于标记区渲染**：否则没有标记权限的用户压根看不到它。
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        when {
            loading -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp))
                Text("读取中…", style = MaterialTheme.typography.bodySmall)
            }

            review == null -> Text(
                if (recordingStatus == "READY") "本通话暂无复盘结果。"
                else "转写完成后这里会出现对话记录、质检风险与下一步建议；平台未开启 AI 时不会生成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                val r = review!!

                r.summary?.takeIf { it.isNotBlank() }?.let {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("小结", style = MaterialTheme.typography.labelLarge)
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // 质检风险：违规了要挨罚，放在建议前面
                r.risks.orEmpty().takeIf { it.isNotEmpty() }?.let { risks ->
                    Text("质检风险（${risks.size}）", style = MaterialTheme.typography.labelLarge)
                    risks.forEach { risk ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Pill(riskLabel(risk.level), riskColor(risk.level))
                            Column {
                                Text(risk.desc.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                risk.segmentTs?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        "位置 $it",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }

                // 下一步建议
                r.suggestions.orEmpty().takeIf { it.isNotEmpty() }?.let { cards ->
                    Text("下一步建议", style = MaterialTheme.typography.labelLarge)
                    cards.forEach { StrategyCardRow(it) }
                }

                // 转写对话
                r.dialogue.orEmpty().takeIf { it.isNotEmpty() }?.let { lines ->
                    HorizontalDivider()
                    Text("通话转写（${lines.size} 句）", style = MaterialTheme.typography.labelLarge)
                    lines.forEach { line ->
                        // 催收员说的话靠左浅蓝、业主靠左浅灰——手机上不做气泡对话，一行一句更好扫读
                        val mine = line.speaker?.contains("催") == true ||
                            line.speaker.equals("AGENT", ignoreCase = true)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                line.speaker.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (mine) MaterialTheme.colorScheme.primary else huicui.textSec,
                            )
                            Text(
                                line.text.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(
                                        if (mine) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 质检风险等级是 HIGH/MID/LOW —— 注意跟待办紧急度的 HIGH/MED/LOW 不是同一套词，别抄错。 */
private fun riskLabel(level: RiskLevelEnum?): String = when (level) {
    RiskLevelEnum.HIGH -> "高危"
    RiskLevelEnum.MID -> "中风险"
    RiskLevelEnum.LOW -> "低风险"
    null -> "-"
}

@Composable
private fun riskColor(level: RiskLevelEnum?): Color = when (level) {
    RiskLevelEnum.HIGH -> huicui.danger
    RiskLevelEnum.MID -> huicui.warning
    else -> huicui.info
}
